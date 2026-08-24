package com.alexweber.anamorphicstretchcam.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * Owns the single shared EGL context and the camera's external-OES SurfaceTexture.
 * Every incoming camera frame is drawn to up to two destinations on this same GL
 * thread: the on-screen preview surface, and (while recording) the MediaCodec
 * encoder's input surface. The horizontal anamorphic distortion is applied as a real
 * vertex-matrix resample of the texture for each destination - never a viewport
 * resize alone - so it is physically baked into whatever ends up in the encoder.
 *
 * Mode semantics (see MainActivity for the user-facing toggle):
 *  - STRETCH: the encoder's own output buffer is created wider than the sensor
 *    (sensorWidth * scale), and the full texture is mapped across that wider quad
 *    with no extra shader scale - the wider buffer does the stretching. The on-screen
 *    preview surface keeps the device's normal size, so to *show* the same stretch we
 *    scale its quad by `scale` (extra content is clipped by the view bounds).
 *  - SQUEEZE: the encoder's output buffer stays at native sensor size, and the quad is
 *    scaled by 1/scale on both destinations, compressing the image toward the center -
 *    approximating raw (undesqueezed) anamorphic-lens footage.
 *
 * Device-rotation compensation is only applied to the *preview* destination, so the
 * monitor matches how the phone is being held. The encoder destination is left
 * untouched and MediaMuxer.setOrientationHint() (set by StretchRecorder) carries the
 * playback rotation instead - that's a standard orientation hint, unrelated to the
 * anamorphic distortion itself, which stays a real per-pixel transform.
 */
class StretchGLRenderer {

    enum class Mode { STRETCH, SQUEEZE }

    interface Listener {
        fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture)
    }

    @Volatile var scale: Float = 2.0f
    @Volatile var mode: Mode = Mode.STRETCH
    @Volatile var rotationDegrees: Int = 0

    private var listener: Listener? = null

    private val thread = HandlerThread("StretchGLRenderer")
    private lateinit var handler: Handler

    private var eglCore: EglCore? = null
    private var program: StretchShaderProgram? = null
    private var cameraTexture: SurfaceTexture? = null

    private var screenSurface: WindowSurface? = null
    private var encoderSurface: WindowSurface? = null
    private var pbufferSurface: EGLSurface? = null

    private val texMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    /** Nanosecond timestamp of the first frame drawn to the encoder surface since it was last
     *  attached; subtracted from every subsequent frame's [SurfaceTexture.getTimestamp] so the
     *  encoder sees a stream starting at 0 instead of raw device-boot-relative nanos. */
    private var encoderTimestampBaseNanos: Long = NO_BASELINE

    fun start(listener: Listener) {
        this.listener = listener
        thread.start()
        handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        handler.post {
            setupGl()
            latch.countDown()
        }
        latch.await()
    }

    fun stop() {
        val latch = CountDownLatch(1)
        handler.post {
            releaseGl()
            latch.countDown()
        }
        latch.await()
        thread.quitSafely()
        thread.join()
    }

    /** Called from any thread; screen surface lifecycle tracks the SurfaceView holder. */
    fun setScreenSurface(surface: Surface?, width: Int, height: Int) {
        handler.post {
            screenSurface?.release()
            screenSurface = null
            if (surface != null) {
                screenSurface = WindowSurface(eglCore!!, surface, ownsSurface = false).apply {
                    setDimensions(width, height)
                }
            }
        }
    }

    /** Attaches the MediaCodec input surface as the second render target. [width]/[height] are the encoder's configured buffer size. */
    fun attachEncoderSurface(surface: Surface, width: Int, height: Int) {
        handler.post {
            encoderSurface?.release()
            encoderSurface = WindowSurface(eglCore!!, surface, ownsSurface = false).apply {
                setDimensions(width, height)
            }
            encoderTimestampBaseNanos = NO_BASELINE
        }
    }

    fun detachEncoderSurface() {
        val latch = CountDownLatch(1)
        handler.post {
            encoderSurface?.release()
            encoderSurface = null
            encoderTimestampBaseNanos = NO_BASELINE
            latch.countDown()
        }
        latch.await()
    }

    /**
     * Renders the current camera frame (with the same anamorphic stretch/squeeze transform used
     * for the encoder, but no rotation baked in) into an offscreen buffer of [width]x[height] and
     * hands the caller a [Bitmap]. Runs on the GL thread; [callback] is also invoked on the GL
     * thread, so callers must not do heavy work (rotation, file I/O) inside it directly.
     */
    fun capturePhoto(width: Int, height: Int, callback: (Bitmap?) -> Unit) {
        handler.post {
            val core = eglCore
            val prog = program
            val texture = cameraTexture
            if (core == null || prog == null || texture == null || width <= 0 || height <= 0) {
                callback(null)
                return@post
            }

            val pbuffer = core.createOffscreenPBuffer(width, height)
            core.makeCurrent(pbuffer)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            Matrix.setIdentityM(mvpMatrix, 0)
            // [width]/[height] is expected to already be the caller's computeOutputSize() result,
            // i.e. pre-widened for STRETCH the same way the video encoder's buffer is - so, like
            // the encoder target in drawToSurface(), STRETCH needs no extra matrix scale here.
            val sx = when (mode) {
                Mode.STRETCH -> 1f
                Mode.SQUEEZE -> 1f / scale
            }
            Matrix.scaleM(mvpMatrix, 0, sx, 1f, 1f)
            prog.draw(mvpMatrix, texMatrix)

            val pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)
            core.releaseSurface(pbuffer)

            // Restore whichever surface was current before the capture so preview/encoder
            // rendering on the next frame isn't left pointed at the now-destroyed pbuffer.
            (screenSurface ?: encoderSurface)?.makeCurrent()

            pixelBuffer.rewind()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(pixelBuffer)
            // glReadPixels rows come back bottom-to-top; Bitmap expects top-to-bottom.
            val flipMatrix = android.graphics.Matrix().apply { preScale(1f, -1f) }
            val flipped = Bitmap.createBitmap(bitmap, 0, 0, width, height, flipMatrix, true)
            bitmap.recycle()
            callback(flipped)
        }
    }

    private fun setupGl() {
        eglCore = EglCore()
        // Bind a throwaway 1x1 pbuffer so we have a current context before touching GLES.
        pbufferSurface = eglCore!!.createOffscreenPBuffer(1, 1)
        eglCore!!.makeCurrent(pbufferSurface!!)
        program = StretchShaderProgram()
        val texture = SurfaceTexture(program!!.textureId)
        // setOnFrameAvailableListener's callback already runs on `handler`'s thread (the GL
        // thread), so it's safe to touch GL state directly here without another post().
        texture.setOnFrameAvailableListener({ drawFrame() }, handler)
        cameraTexture = texture
        listener?.onSurfaceTextureReady(texture)
    }

    private fun drawFrame() {
        val texture = cameraTexture ?: return
        texture.updateTexImage()
        texture.getTransformMatrix(texMatrix)

        screenSurface?.let {
            drawToSurface(it, applyRotation = true)
            it.swapBuffers()
        }
        encoderSurface?.let {
            drawToSurface(it, applyRotation = false)
            // texture.timestamp is nanoseconds since device boot, not since recording start;
            // MediaCodec expects a stream starting at (or near) 0, so the first frame observed
            // after attaching the encoder surface becomes the baseline for every frame after it.
            if (encoderTimestampBaseNanos == NO_BASELINE) {
                encoderTimestampBaseNanos = texture.timestamp
            }
            // Presentation time must land on the EGL surface *before* eglSwapBuffers,
            // since the swap is what actually hands this buffer to the MediaCodec
            // consumer - setting it after would timestamp the next frame instead.
            it.setPresentationTime(texture.timestamp - encoderTimestampBaseNanos)
            it.swapBuffers()
        }
    }

    private fun drawToSurface(target: WindowSurface, applyRotation: Boolean) {
        val prog = program ?: return
        target.makeCurrent()
        GLES20.glViewport(0, 0, target.width, target.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        Matrix.setIdentityM(mvpMatrix, 0)
        if (applyRotation && rotationDegrees != 0) {
            Matrix.rotateM(mvpMatrix, 0, -rotationDegrees.toFloat(), 0f, 0f, 1f)
        }
        val sx = when (mode) {
            Mode.STRETCH -> if (applyRotation) scale else 1f
            Mode.SQUEEZE -> 1f / scale
        }
        Matrix.scaleM(mvpMatrix, 0, sx, 1f, 1f)

        prog.draw(mvpMatrix, texMatrix)
    }

    private fun releaseGl() {
        screenSurface?.release()
        screenSurface = null
        encoderSurface?.release()
        encoderSurface = null
        cameraTexture?.release()
        cameraTexture = null
        program?.release()
        program = null
        pbufferSurface?.let { eglCore?.releaseSurface(it) }
        pbufferSurface = null
        eglCore?.release()
        eglCore = null
    }

    companion object {
        private const val TAG = "StretchGLRenderer"
        private const val NO_BASELINE = Long.MIN_VALUE
    }
}
