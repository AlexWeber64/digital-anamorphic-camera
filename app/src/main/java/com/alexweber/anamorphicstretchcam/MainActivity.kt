package com.alexweber.anamorphicstretchcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import com.alexweber.anamorphicstretchcam.databinding.ActivityMainBinding
import com.alexweber.anamorphicstretchcam.gl.StretchGLRenderer
import com.google.android.material.snackbar.Snackbar
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val renderer = StretchGLRenderer()
    private lateinit var cameraSource: CameraSource
    private var recorder: StretchRecorder? = null

    private var captureSize: Size = Size(1920, 1080)
    private var isRecording = false
    private var recordStartElapsedMs = 0L

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
        val cameraGranted = grants[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            binding.permissionOverlay.visibility = android.view.View.GONE
            initPipeline()
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
                renderer.rotationDegrees = rotationDegrees
            }
        }

        setupControls()
        setupPreviewSurface()

        if (hasRequiredPermissions()) {
            initPipeline()
        } else {
            requestPermissions()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
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

        binding.recordButton.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        updateScaleReadout()
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

        newRecorder.start(config, renderer.rotationDegrees, object : StretchRecorder.Callback {
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

    override fun onPause() {
        super.onPause()
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
