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

    /** Clockwise device rotation from natural orientation, tracked live from the accelerometer
     *  so recorded orientation stays correct even if the activity's display rotation is locked. */
    private var deviceOrientationDegrees = 0
    private val orientationEventListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) {
                    deviceOrientationDegrees = orientation
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
        cameraSource.rotationListener = object : CameraSource.RotationListener {
            override fun onRotationChanged(rotationDegrees: Int) {
                Log.d(TAG, "ROTATION_DEBUG CameraX Preview TransformationInfo.rotationDegrees -> $rotationDegrees")
                renderer.rotationDegrees = rotationDegrees
            }
        }

        setupControls()
        setupPreviewSurface()

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

    /**
     * Computes the clockwise rotation (0/90/180/270) needed to display a sensor-orientation
     * buffer correctly, from the live accelerometer-based device orientation plus the active
     * camera's SENSOR_ORIENTATION/LENS_FACING - the same formula documented on
     * [CameraCharacteristics.SENSOR_ORIENTATION]. Used for both the MediaMuxer orientation hint
     * and the still-photo rotation, so both stay correct even if the display's own rotation is
     * locked (e.g. holding the phone sideways to record landscape footage from a portrait-locked
     * UI).
     *
     * The raw formula result is then adjusted per [StretchGLRenderer.correctedRotationDegrees] -
     * on-device testing (logcat ROTATION_DEBUG trace, not a formula guess) showed a *constant*
     * offset error for the back camera (fixed by [StretchGLRenderer.ROTATION_CALIBRATION_OFFSET_DEGREES])
     * and a differently-signed error for the front camera (fixed by mirroring around
     * [StretchGLRenderer.FRONT_ROTATION_CALIBRATION_BASE_DEGREES]) - both confirmed correct in
     * portrait and after a live rotation to landscape.
     */
    private fun computeOrientationHint(): Int {
        var rounded = ((deviceOrientationDegrees + 45) / 90 * 90) % 360
        if (rounded < 0) rounded += 360
        val facingFront = cameraSource.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        val signedOrientation = if (facingFront) -rounded else rounded
        val rawHint = ((cameraSource.sensorOrientation + signedOrientation) % 360 + 360) % 360
        val hint = if (facingFront) {
            ((StretchGLRenderer.FRONT_ROTATION_CALIBRATION_BASE_DEGREES - rawHint) % 360 + 360) % 360
        } else {
            ((rawHint - StretchGLRenderer.ROTATION_CALIBRATION_OFFSET_DEGREES) % 360 + 360) % 360
        }
        Log.d(
            TAG,
            "ROTATION_DEBUG computeOrientationHint(): rawDeviceOrientation=$deviceOrientationDegrees " +
                "roundedDeviceOrientation=$rounded sensorOrientation=${cameraSource.sensorOrientation} " +
                "lensFacing=${cameraSource.lensFacing} facingFront=$facingFront " +
                "cameraXPreviewRotationDegrees(unused-for-hint)=${renderer.rotationDegrees} " +
                "rawHint=$rawHint -> calibratedHint=$hint"
        )
        return hint
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
