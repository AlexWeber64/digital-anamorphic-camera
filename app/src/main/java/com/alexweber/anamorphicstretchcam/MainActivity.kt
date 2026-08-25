package com.alexweber.anamorphicstretchcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import com.alexweber.anamorphicstretchcam.databinding.ActivityMainBinding
import com.alexweber.anamorphicstretchcam.gl.StretchGLRenderer
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class CaptureMode { VIDEO, PHOTO }

    private lateinit var binding: ActivityMainBinding

    private val renderer = StretchGLRenderer()
    private lateinit var cameraSource: CameraSource
    private var recorder: StretchRecorder? = null

    private var captureSize: Size = Size(1920, 1080)
    private var isRecording = false
    private var recordStartElapsedMs = 0L
    private var captureMode = CaptureMode.VIDEO
    private var pipelineInitialized = false

    /** Clockwise device rotation from natural orientation, tracked live from the accelerometer.
     *  The app's window is locked to portrait (see AndroidManifest.xml) precisely so the OS
     *  never re-flows the control chrome on rotation - this is the only rotation signal in the
     *  app, driving both the live preview's GL rotation and the individual chrome glyphs'
     *  counter-rotation, entirely independent of any window/display-rotation event. */
    private var deviceOrientationDegrees = 0

    /** Last chrome-glyph rotation angle actually applied, so glyph views are only re-animated
     *  when the snapped-to-90 orientation actually changes, not on every accelerometer tick. */
    private var lastAppliedChromeRotation = 0

    /** TEMPORARY diagnostic: last renderer.rotationDegrees value we logged, so the live-preview
     *  investigation log only fires on actual changes instead of every accelerometer tick. */
    private var lastLoggedRendererRotationHint = Int.MIN_VALUE

    /** Chrome glyph views that must counter-rotate in place to stay upright as the device
     *  physically rotates, while their containing chrome panel stays docked. Text-bearing
     *  controls (scale readout, mode-toggle labels) are deliberately excluded - only icon-only
     *  controls (the camera-flip icon) rotate, matching native camera apps; text stays upright
     *  in its normal screen position and is never rotated. */
    private val rotatingChromeViews = mutableListOf<android.view.View>()

    private val orientationEventListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceOrientationDegrees = orientation
                val rounded = roundToNearest90(orientation)
                val hint = rawOrientationHint(rounded)
                renderer.rotationDegrees = hint
                // TEMPORARY diagnostic for the "live preview doesn't rotate before recording"
                // investigation: logs every time the value actually pushed into the renderer
                // changes, regardless of recording state, so we can see whether this callback
                // keeps firing/updating during idle preview or only around record-press time.
                if (hint != lastLoggedRendererRotationHint) {
                    lastLoggedRendererRotationHint = hint
                    Log.d(
                        TAG,
                        "ROTATION_DEBUG[orientationListener] isRecording=$isRecording rawOrientation=$orientation " +
                            "roundedOrientation=$rounded -> renderer.rotationDegrees=$hint"
                    )
                }
                if (rounded != lastAppliedChromeRotation) {
                    lastAppliedChromeRotation = rounded
                    applyChromeCounterRotation(rounded)
                }
            }
        }
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - recordStartElapsedMs
            val totalSeconds = elapsed / 1000
            binding.timerText.text = String.format(
                Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60
            )
            uiHandler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] ?: hasCameraPermission()
        if (cameraGranted) {
            initPipelineIfNeeded()
        } else {
            binding.permissionOverlay.visibility = android.view.View.VISIBLE
            binding.permissionText.setText(R.string.permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraSource = CameraSource(this, this)

        setupControls()
        setupPreviewSurface()
        setupChromeRotation()

        initPipelineIfNeeded()
        // Camera permission alone is enough to start the pipeline (see initPipelineIfNeeded),
        // but RECORD_AUDIO must be requested independently - without this, a device that already
        // granted CAMERA in an earlier install would never see the mic prompt at all, silently
        // leaving every recording without audio.
        if (!hasCameraPermission() || !hasAudioPermission()) {
            requestPermissions()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun initPipelineIfNeeded() {
        if (pipelineInitialized || !hasCameraPermission()) return
        pipelineInitialized = true
        binding.permissionOverlay.visibility = android.view.View.GONE
        initPipeline()
    }

    private fun setupPreviewSurface() {
        binding.previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                renderer.setScreenSurface(holder.surface, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                renderer.setScreenSurface(null, 0, 0)
            }
        })

        binding.permissionRetryButton.setOnClickListener { requestPermissions() }
    }

    private fun setupControls() {
        binding.scaleSlider.addOnChangeListener { _, value, _ ->
            renderer.scale = value
            updateScaleReadout()
        }

        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            renderer.mode = if (checkedId == binding.modeStretchButton.id) {
                StretchGLRenderer.Mode.STRETCH
            } else {
                StretchGLRenderer.Mode.SQUEEZE
            }
            updateScaleReadout()
        }
        binding.modeStretchButton.isChecked = true

        binding.captureModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            captureMode = if (checkedId == binding.captureModeVideoButton.id) {
                CaptureMode.VIDEO
            } else {
                CaptureMode.PHOTO
            }
            updateCaptureModeUi()
        }
        binding.captureModeVideoButton.isChecked = true

        binding.recordButton.setOnClickListener {
            when (captureMode) {
                CaptureMode.PHOTO -> capturePhoto()
                CaptureMode.VIDEO -> if (isRecording) stopRecording() else startRecording()
            }
        }

        binding.cameraSwitchButton.setOnClickListener {
            cameraSource.switchCamera()
        }

        updateScaleReadout()
        updateCaptureModeUi()
    }

    private fun updateCaptureModeUi() {
        if (captureMode == CaptureMode.PHOTO) {
            binding.recordButton.setBackgroundResource(R.drawable.record_button_photo)
            binding.recordButton.contentDescription = getString(R.string.capture_photo)
        } else {
            binding.recordButton.setBackgroundResource(
                if (isRecording) R.drawable.record_button_recording else R.drawable.record_button_idle
            )
            binding.recordButton.contentDescription = getString(R.string.record_start)
        }
    }

    private fun updateScaleReadout() {
        val modeLabel = if (renderer.mode == StretchGLRenderer.Mode.STRETCH) {
            getString(R.string.mode_stretch)
        } else {
            getString(R.string.mode_squeeze)
        }
        binding.scaleReadout.text = String.format(Locale.US, "%.1fx %s", renderer.scale, modeLabel.uppercase())
    }

    private fun initPipeline() {
        renderer.start(object : StretchGLRenderer.Listener {
            override fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture) {
                runOnUiThread { startCamera(surfaceTexture) }
            }
        })
    }

    private fun startCamera(surfaceTexture: SurfaceTexture) {
        cameraSource.start(surfaceTexture) { size ->
            captureSize = size
            renderer.facingFront = cameraSource.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
            Log.i(TAG, "Camera bound at native sensor size $size")
        }
    }

    /** Computes the encoder's output buffer size for the currently selected mode/scale. */
    private fun computeOutputSize(): Size {
        return when (renderer.mode) {
            StretchGLRenderer.Mode.STRETCH -> {
                var widened = (captureSize.width * renderer.scale).roundToInt()
                if (widened % 2 != 0) widened += 1
                Size(widened, captureSize.height)
            }
            StretchGLRenderer.Mode.SQUEEZE -> captureSize
        }
    }

    /** Snaps a raw 0-359 accelerometer orientation reading to the nearest multiple of 90. */
    private fun roundToNearest90(orientation: Int): Int {
        var rounded = ((orientation + 45) / 90 * 90) % 360
        if (rounded < 0) rounded += 360
        return rounded
    }

    /**
     * Raw (uncalibrated) clockwise rotation needed to display a sensor-orientation buffer
     * correctly, from a snapped accelerometer orientation plus the active camera's
     * SENSOR_ORIENTATION/LENS_FACING - the same formula documented on
     * [CameraCharacteristics.SENSOR_ORIENTATION]. Fed into [StretchGLRenderer.rotationDegrees]
     * live on every orientation change; [StretchGLRenderer.correctedRotationDegrees] then applies
     * the on-device-calibrated correction on top (see that function's doc for why).
     */
    private fun rawOrientationHint(roundedDeviceOrientation: Int): Int {
        val facingFront = cameraSource.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        val signedOrientation = if (facingFront) -roundedDeviceOrientation else roundedDeviceOrientation
        return ((cameraSource.sensorOrientation + signedOrientation) % 360 + 360) % 360
    }

    /**
     * The calibrated hint used for the MediaMuxer orientation hint and the still-photo rotation -
     * delegates to [StretchGLRenderer.correctedRotationDegrees] (the same value already driving
     * the live preview's GL rotation) rather than duplicating the calibration formula, so both
     * stay correct and consistent even if the display's own rotation is locked (e.g. holding the
     * phone sideways to record landscape footage from this portrait-locked UI).
     */
    private fun computeOrientationHint(): Int {
        val hint = renderer.correctedRotationDegrees()
        Log.d(
            TAG,
            "ROTATION_DEBUG computeOrientationHint(): rawDeviceOrientation=$deviceOrientationDegrees " +
                "sensorOrientation=${cameraSource.sensorOrientation} lensFacing=${cameraSource.lensFacing} " +
                "rendererRotationDegrees(raw)=${renderer.rotationDegrees} -> calibratedHint=$hint"
        )
        return hint
    }

    /** Registers the chrome glyph views that must counter-rotate to stay upright in place while
     *  their containing chrome panel stays docked to the physical device edge. Only the
     *  icon-only camera-flip button rotates - text-bearing controls (scale readout, mode-toggle
     *  labels) are intentionally not registered here; they stay upright in their normal screen
     *  position, matching native camera apps (e.g. Samsung's), which never rotate on-screen
     *  text, only icon glyphs. */
    private fun setupChromeRotation() {
        rotatingChromeViews.addAll(listOf(binding.cameraSwitchButton))
    }

    /** Counter-rotates every registered chrome glyph by the angle documented on
     *  [OrientationEventListener.onOrientationChanged]: content should be rotated by
     *  `360 - orientation` degrees to appear upright regardless of physical device orientation -
     *  equivalently, `-roundedDeviceOrientation` on [android.view.View.animate]'s CW-positive
     *  rotation convention. */
    private fun applyChromeCounterRotation(roundedDeviceOrientation: Int) {
        val angle = -roundedDeviceOrientation.toFloat()
        rotatingChromeViews.forEach { view ->
            view.animate().rotation(angle).setDuration(250).start()
        }
    }

    private fun capturePhoto() {
        animateCaptureButtonPress()
        val outputSize = computeOutputSize()
        renderer.logRotationDebugSnapshot("photoCapture")
        renderer.capturePhoto(outputSize.width, outputSize.height) { bitmap ->
            if (bitmap == null) {
                runOnUiThread {
                    Snackbar.make(binding.root, getString(R.string.photo_save_failed, ""), Snackbar.LENGTH_LONG).show()
                }
                return@capturePhoto
            }
            val hint = computeOrientationHint()
            val oriented = if (hint == 0) {
                bitmap
            } else {
                val matrix = android.graphics.Matrix().apply { postRotate(hint.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                rotated
            }
            savePhoto(oriented)
        }
    }

    /** Shrinks the shutter button slightly and back to full size, as a shutter-press cue. */
    private fun animateCaptureButtonPress() {
        val button = binding.recordButton
        button.animate().cancel()
        button.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(80)
            .withEndAction {
                button.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }

    private fun savePhoto(bitmap: Bitmap) {
        Thread {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "ANAMORPHIC_$timestamp.jpg"
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("MediaStore insert failed")
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    } ?: throw IllegalStateException("openOutputStream failed")
                    contentResolver.update(
                        uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null
                    )
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, name)
                    file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
                    MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
                }
                bitmap.recycle()
                runOnUiThread {
                    Snackbar.make(binding.root, getString(R.string.saved_to, name), Snackbar.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Photo save failed", t)
                runOnUiThread {
                    Snackbar.make(binding.root, getString(R.string.photo_save_failed, t.message ?: ""), Snackbar.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun startRecording() {
        val outputSize = computeOutputSize()
        val useAudio = hasAudioPermission()
        val config = StretchRecorder.Config(
            outputWidth = outputSize.width,
            outputHeight = outputSize.height,
            frameRate = 30,
            videoBitRate = 16_000_000,
            useAudio = useAudio
        )

        val newRecorder = StretchRecorder(this)
        val encoderSurface = newRecorder.prepareVideoSurface(config)
        renderer.attachEncoderSurface(encoderSurface, outputSize.width, outputSize.height)
        recorder = newRecorder

        val orientationHint = computeOrientationHint()
        renderer.logRotationDebugSnapshot("recordingStart")

        newRecorder.start(config, orientationHint, object : StretchRecorder.Callback {
            override fun onSaved(uri: Uri, displayName: String) {
                runOnUiThread {
                    Log.i(TAG, "Saved $displayName at encoded size ${config.outputWidth}x${config.outputHeight} (mode=${renderer.mode})")
                    Snackbar.make(binding.root, getString(R.string.saved_to, displayName), Snackbar.LENGTH_LONG).show()
                }
            }

            override fun onError(error: Throwable) {
                Log.e(TAG, "Recording failed", error)
                runOnUiThread {
                    Snackbar.make(binding.root, getString(R.string.save_failed, error.message ?: ""), Snackbar.LENGTH_LONG).show()
                }
            }
        })

        isRecording = true
        setRecordingUiState(true)
    }

    private fun stopRecording() {
        recorder?.stop()
        renderer.detachEncoderSurface()
        recorder = null
        isRecording = false
        setRecordingUiState(false)
    }

    private fun setRecordingUiState(recording: Boolean) {
        binding.modeToggleGroup.children.forEach { it.isEnabled = !recording }
        binding.captureModeToggleGroup.children.forEach { it.isEnabled = !recording }
        binding.cameraSwitchButton.isEnabled = !recording
        binding.recordButton.setBackgroundResource(
            if (recording) R.drawable.record_button_recording else R.drawable.record_button_idle
        )
        if (recording) {
            recordStartElapsedMs = SystemClock.elapsedRealtime()
            binding.recIndicatorContainer.visibility = android.view.View.VISIBLE
            uiHandler.post(timerTick)
        } else {
            uiHandler.removeCallbacks(timerTick)
            binding.recIndicatorContainer.visibility = android.view.View.INVISIBLE
            binding.timerText.text = "00:00"
        }
    }

    override fun onResume() {
        super.onResume()
        orientationEventListener.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationEventListener.disable()
        if (isRecording) stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(timerTick)
        cameraSource.stop()
        renderer.stop()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
