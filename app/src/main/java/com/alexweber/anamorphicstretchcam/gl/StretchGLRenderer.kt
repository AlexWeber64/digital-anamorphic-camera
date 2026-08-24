package com.alexweber.anamorphicstretchcam.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
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
 *
 * The app's window is locked to portrait (see AndroidManifest.xml) so its control chrome
 * never re-flows when the device physically rotates - only individual chrome glyphs
 * counter-rotate in place (see MainActivity). [rotationDegrees] is therefore driven by
 * MainActivity's live [android.view.OrientationEventListener] reading, not by any
 * display-rotation signal (which would never change once the window is locked) - this is
 * what lets the *preview content itself* still visually rotate to match physical device
 * orientation even though the SurfaceView's own on-screen bounds never change shape.
 */
class StretchGLRenderer {

    enum class Mode { STRETCH, SQUEEZE }

    interface Listener {
        fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture)
    }

    @Volatile var scale: Float = 2.0f
    @Volatile var mode: Mode = Mode.STRETCH
    /** Raw (uncalibrated) sensor-orientation-adjusted hint, updated live from MainActivity's
     *  OrientationEventListener on every accelerometer-reported orientation change. See
     *  [correctedRotationDegrees] for the actually-applied, calibrated value. */
    @Volatile var rotationDegrees: Int = 0
    /** Whether the currently-bound camera is front-facing - the calibration in
     *  [correctedRotationDegrees] is mirrored for front cameras (confirmed on-device: the same
     *  offset that fixed the back camera left the front camera upside-down in portrait). */
    @Volatile var facingFront: Boolean = false

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

    /**
     * TEMPORARY diagnostic: dumps the exact GL-side state feeding both destinations, so we can
     * see whether the SurfaceTexture transform itself (not just the rotationDegrees hint) is
     * contributing an unexpected rotation. Runs on the GL thread; safe to call from any thread.
     */
    fun logRotationDebugSnapshot(callSite: String) {
        handler.post {
            val corrected = correctedRotationDegrees()
            val screenMvp = FloatArray(16)
            Matrix.setIdentityM(screenMvp, 0)
            if (corrected != 0) {
                Matrix.rotateM(screenMvp, 0, -corrected.toFloat(), 0f, 0f, 1f)
            }
            val screenSx = when (mode) {
                Mode.STRETCH -> scale
                Mode.SQUEEZE -> 1f / scale
            }
            Matrix.scaleM(screenMvp, 0, screenSx, 1f, 1f)

            val encoderMvp = FloatArray(16)
            Matrix.setIdentityM(encoderMvp, 0)
            val encoderSx = when (mode) {
                Mode.STRETCH -> 1f
                Mode.SQUEEZE -> 1f / scale
            }
            Matrix.scaleM(encoderMvp, 0, encoderSx, 1f, 1f)

            fun fmt(m: FloatArray) = m.joinToString(prefix = "[", postfix = "]") { "%.3f".format(it) }

            // Logged as separate single-line calls - a single Log.d with embedded '\n' only has
            // the literal "ROTATION_DEBUG" text on its first line, so line-filtering tools like
            // `findstr`/`grep` silently drop every continuation line.
            Log.d(
                TAG,
                "ROTATION_DEBUG[$callSite] rotationDegrees=$rotationDegrees facingFront=$facingFront correctedRotationDegrees=$corrected " +
                    "mode=$mode scale=$scale screenSurfaceAttached=${screenSurface != null} encoderSurfaceAttached=${encoderSurface != null}"
            )
            Log.d(TAG, "ROTATION_DEBUG[$callSite] texMatrix (SurfaceTexture.getTransformMatrix(), shared by both destinations) = ${fmt(texMatrix)}")
            Log.d(TAG, "ROTATION_DEBUG[$callSite] screen mvpMatrix (applyRotation=true, rotateM(-correctedRotationDegrees) then scaleM(sx,1,1)) = ${fmt(screenMvp)}")
            Log.d(TAG, "ROTATION_DEBUG[$callSite] encoder mvpMatrix (applyRotation=false, no rotateM, scaleM(sx,1,1) only) = ${fmt(encoderMvp)}")
            Log.d(TAG, "ROTATION_DEBUG[$callSite] vertex shader applies: gl_Position = mvpMatrix * aPosition; vTextureCoord = (texMatrix * aTextureCoord).xy")
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
        val corrected = correctedRotationDegrees()
        if (applyRotation && corrected != 0) {
            Matrix.rotateM(mvpMatrix, 0, -corrected.toFloat(), 0f, 0f, 1f)
        }
        val sx = when (mode) {
            Mode.STRETCH -> if (applyRotation) scale else 1f
            Mode.SQUEEZE -> 1f / scale
        }
        Matrix.scaleM(mvpMatrix, 0, sx, 1f, 1f)

        prog.draw(mvpMatrix, texMatrix)
    }

    /**
     * Confirmed on-device (real recording + logcat trace, not a formula guess): for the back
     * camera, the live preview and the recorded file were both rotated a *constant* 90 degrees
     * too far clockwise regardless of physical device orientation - even though [rotationDegrees]
     * itself was verified to correctly track physical rotation (90 at portrait, 180 after a live
     * ~90 degree physical turn). A constant, orientation-independent error while the tracked value
     * itself moves correctly means the raw camera texture already carries an extra ~90 degrees of
     * rotation before our own compensation is applied - consistent with the logged texMatrix for
     * this device mapping (u,v) -> (1-v, 1-u) (a transpose-like transform), not the plain
     * vertical flip most SurfaceTexture/GL sample code assumes. [ROTATION_CALIBRATION_OFFSET_DEGREES]
     * removes that fixed double-count for the back camera.
     *
     * The front camera needed a *different* correction, also confirmed from two independent
     * on-device data points (portrait: rotationDegrees=270, observed upside-down i.e. correct=0;
     * a live turn to landscape: rotationDegrees=180, observed correct i.e. correct=90). Both fit
     * `(FRONT_ROTATION_CALIBRATION_BASE_DEGREES - rotationDegrees) % 360`, not the back camera's
     * subtractive offset - consistent with CameraX's own rotationDegrees formula also flipping
     * sign for front vs. back (mirrored sensor mounting).
     *
     * See [MainActivity.computeOrientationHint] for the matching correction applied to the
     * recorded file/photo orientation. Public because MainActivity also calls this directly
     * as the single source of truth for the calibrated hint (rather than duplicating the
     * calibration formula), now that [rotationDegrees] is accelerometer-driven rather than
     * display-rotation-driven.
     */
    fun correctedRotationDegrees(): Int {
        return if (facingFront) {
            ((FRONT_ROTATION_CALIBRATION_BASE_DEGREES - rotationDegrees) % 360 + 360) % 360
        } else {
            ((rotationDegrees - ROTATION_CALIBRATION_OFFSET_DEGREES) % 360 + 360) % 360
        }
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

        /** See [correctedRotationDegrees] - empirically-calibrated constant offset for the *back*
         *  camera, not derived from the standard sensor-orientation formula alone. Shared with
         *  MainActivity.computeOrientationHint() so the preview and the recorded file/photo stay
         *  consistent with each other. */
        const val ROTATION_CALIBRATION_OFFSET_DEGREES = 90

        /** See [correctedRotationDegrees] - empirically-calibrated base for the *front* camera's
         *  mirrored correction: `(FRONT_ROTATION_CALIBRATION_BASE_DEGREES - rotationDegrees) % 360`. */
        const val FRONT_ROTATION_CALIBRATION_BASE_DEGREES = 270
    }
}
