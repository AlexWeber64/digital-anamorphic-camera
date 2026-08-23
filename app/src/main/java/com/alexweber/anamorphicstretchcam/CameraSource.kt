package com.alexweber.anamorphicstretchcam

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

/**
 * Owns the CameraX camera lifecycle and bridges raw camera frames into a caller-supplied
 * external-OES [SurfaceTexture] (see StretchGLRenderer). CameraX's own `VideoCapture` use
 * case is intentionally never used here - it records straight from an internal Surface
 * that the app cannot intercept with a GL transform, which would make the horizontal
 * stretch a preview-only effect. CameraX is used only for `Preview` (camera session +
 * lifecycle + permission-friendly setup); we hand it our own Surface backed by the GL
 * renderer's SurfaceTexture instead of a PreviewView, and pull frames out of it ourselves.
 */
class CameraSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    interface RotationListener {
        fun onRotationChanged(rotationDegrees: Int)
    }

    var rotationListener: RotationListener? = null

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null

    /** Native sensor-reported size chosen for capture; set once [start] resolves it. */
    var captureSize: Size = Size(1920, 1080)
        private set

    fun start(surfaceTexture: SurfaceTexture, onReady: (Size) -> Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            // Preferred size comes straight from the sensor's own StreamConfigurationMap, not
            // from AndroidX's AspectRatio.RATIO_16_9 / RATIO_4_3 constants. We still hand it to
            // CameraX as a ResolutionStrategy target so Preview negotiates a matching buffer -
            // the actual SurfaceRequest.resolution below is what's authoritative either way.
            val preferredSize = pickNativeSensorSize(provider, cameraSelector)
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(preferredSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()

            var reportedOnce = false
            val previewUseCase = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
            previewUseCase.setSurfaceProvider { request: SurfaceRequest ->
                request.setTransformationInfoListener(mainExecutor) { info ->
                    rotationListener?.onRotationChanged(info.rotationDegrees)
                }
                val negotiatedSize = request.resolution
                captureSize = negotiatedSize
                surfaceTexture.setDefaultBufferSize(negotiatedSize.width, negotiatedSize.height)

                val surface = Surface(surfaceTexture)
                request.provideSurface(surface, mainExecutor) {
                    surface.release()
                }

                if (!reportedOnce) {
                    reportedOnce = true
                    onReady(negotiatedSize)
                }
            }
            preview = previewUseCase

            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, previewUseCase)
        }, mainExecutor)
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        preview = null
    }

    /**
     * Queries the sensor's native supported sizes via Camera2CameraInfo / StreamConfigurationMap
     * directly (not AndroidX's AspectRatio.RATIO_16_9 / RATIO_4_3 constants), per the app's
     * requirement to drive capture resolution from what the hardware actually reports. Picks the
     * largest SurfaceTexture-compatible size that doesn't exceed a sane cap so the GL pipeline
     * and encoder stay real-time on device.
     */
    private fun pickNativeSensorSize(provider: ProcessCameraProvider, selector: CameraSelector): Size {
        val cameraInfo = selector.filter(provider.availableCameraInfos).firstOrNull()
            ?: return Size(1920, 1080)
        val cameraId = Camera2CameraInfo.from(cameraInfo).cameraId
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map: StreamConfigurationMap =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1920, 1080)

        val maxArea = 1920L * 1080L
        val candidate = sizes
            .filter { it.width.toLong() * it.height.toLong() <= maxArea }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }

        val chosen = candidate ?: sizes.minByOrNull { it.width.toLong() * it.height.toLong() }
        Log.i(TAG, "Native sensor sizes available: ${sizes.joinToString()}; chosen=$chosen")
        return chosen ?: Size(1920, 1080)
    }

    companion object {
        private const val TAG = "CameraSource"
    }
}
