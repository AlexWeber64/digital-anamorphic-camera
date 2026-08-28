package com.alexweber.anamorphicstretchcam

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.graphics.Typeface
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    /** MediaStore URI of the most recent photo/video this app saved, shown in the last-capture
     *  thumbnail button; null until [refreshLastCaptureThumbnail] finds one. */
    private var lastCaptureUri: Uri? = null

    /** Horizontal distance (px) the inactive Video/Photo label sits from center, so the active
     *  one always lands exactly centered - computed once from both labels' measured widths
     *  (see [setupControls]), 0 until then. */
    private var captureModeOffsetPx = 0f

    /** Clockwise device rotation from natural orientation, tracked live from the accelerometer.
     *  The app's window is locked to portrait (see AndroidManifest.xml) precisely so the OS
     *  never re-flows the control chrome on rotation - this is the only rotation signal in the
     *  app. It drives the calibrated hint used for the muxer orientation hint / still-photo
     *  rotation (see [computeOrientationHint]) and the icon-only chrome glyphs' counter-rotation
     *  (see [applyChromeCounterRotation]); it deliberately does NOT drive any rotation of the
     *  live camera feed itself, which is meant to tilt with the physical device like a real
     *  optical viewfinder rather than auto-level (see StretchGLRenderer's class doc). */
    private var deviceOrientationDegrees = 0

    /** Last chrome-glyph rotation angle actually applied, so glyph views are only re-animated
     *  when the snapped-to-90 orientation actually changes, not on every accelerometer tick. */
    private var lastAppliedChromeRotation = 0

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
                // Kept live regardless of recording state: this only feeds the calibrated
                // muxer/photo-rotation hint (computeOrientationHint()/correctedRotationDegrees()),
                // never the live preview, which never rotates - see StretchGLRenderer's class doc.
                renderer.rotationDegrees = rawOrientationHint(rounded)
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

    /** Fires only after the user has already agreed, via [showGalleryPermissionDialog], to be
     *  asked for whole-camera-roll access. Either outcome (grant or deny) marks the flow as
     *  resolved so we never show that dialog or this system prompt again. */
    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        markGalleryPermissionAsked()
        val allGranted = mediaReadPermissions().all { grants[it] == true }
        if (!allGranted) {
            Snackbar.make(binding.root, getString(R.string.gallery_permission_denied), Snackbar.LENGTH_LONG).show()
        }
        refreshLastCaptureThumbnail()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraSource = CameraSource(this, this)

        setupControls()
        setupPreviewSurface()
        setupChromeRotation()
        refreshLastCaptureThumbnail()
        showGalleryPermissionDialogIfNeeded()

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

        binding.captureModeVideoLabel.setOnClickListener { setCaptureMode(CaptureMode.VIDEO) }
        binding.captureModePhotoLabel.setOnClickListener { setCaptureMode(CaptureMode.PHOTO) }
        // Both labels are gravity="center" in the same FrameLayout (see activity_main.xml), so
        // translationX=0 always means true center regardless of which label that currently is.
        // The offset needed to push the *inactive* label fully clear of the centered one depends
        // on both labels' actual rendered widths, which aren't known until after layout - measure
        // once post-layout, then apply the now-known offset via the usual updateCaptureModeUi().
        binding.captureModeRow.post {
            val gapPx = resources.displayMetrics.density * 16f
            captureModeOffsetPx = (binding.captureModeVideoLabel.width + binding.captureModePhotoLabel.width) / 2f + gapPx
            updateCaptureModeUi()
        }

        binding.recordButton.setOnClickListener {
            when (captureMode) {
                CaptureMode.PHOTO -> capturePhoto()
                CaptureMode.VIDEO -> if (isRecording) stopRecording() else startRecording()
            }
        }

        binding.cameraSwitchButton.setOnClickListener {
            cameraSource.switchCamera()
        }

        binding.lastCaptureThumbnail.setOnClickListener {
            openLastCapture()
        }

        updateScaleReadout()
        updateCaptureModeUi()
    }

    private fun setCaptureMode(mode: CaptureMode) {
        captureMode = mode
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

        // Plain-text mode labels: active is bold/white, inactive is dimmed - no border on either.
        val videoActive = captureMode == CaptureMode.VIDEO
        binding.captureModeVideoLabel.setTypeface(null, if (videoActive) Typeface.BOLD else Typeface.NORMAL)
        binding.captureModeVideoLabel.setTextColor(
            getColor(if (videoActive) R.color.white else R.color.mode_label_inactive)
        )
        binding.captureModePhotoLabel.setTypeface(null, if (videoActive) Typeface.NORMAL else Typeface.BOLD)
        binding.captureModePhotoLabel.setTextColor(
            getColor(if (videoActive) R.color.mode_label_inactive else R.color.white)
        )

        // Slide whichever label is active to dead center (translationX=0) and push the other
        // one out to the side by the measured offset - Video sits left of Photo when inactive,
        // matching their fixed left-to-right order in the layout.
        binding.captureModeVideoLabel.animate().translationX(if (videoActive) 0f else -captureModeOffsetPx).start()
        binding.captureModePhotoLabel.animate().translationX(if (videoActive) captureModeOffsetPx else 0f).start()
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
     *  their containing chrome panel stays docked to the physical device edge. Only icon-only
     *  controls (the camera-flip button, the last-capture thumbnail) rotate - text-bearing
     *  controls (scale readout, mode-toggle labels) are intentionally not registered here; they
     *  stay upright in their normal screen position, matching native camera apps (e.g.
     *  Samsung's), which never rotate on-screen text, only icon glyphs. */
    private fun setupChromeRotation() {
        rotatingChromeViews.addAll(listOf(binding.cameraSwitchButton, binding.lastCaptureThumbnail))
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

    private data class RecentCapture(val uri: Uri, val isVideo: Boolean, val dateAdded: Long)

    private fun mediaReadPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasMediaReadPermission(): Boolean {
        return mediaReadPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasAskedGalleryPermission(): Boolean {
        return getPreferences(MODE_PRIVATE).getBoolean(PREF_ASKED_GALLERY_PERMISSION, false)
    }

    private fun markGalleryPermissionAsked() {
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_ASKED_GALLERY_PERMISSION, true).apply()
    }

    /** Explains the whole-camera-roll vs. app-only tradeoff up front, once ever, before the
     *  actual system permission prompt fires - the user picks Yes/No here rather than being
     *  surprised by a bare "allow photos and videos?" system dialog. A "No" here, or a "Yes"
     *  followed by the user denying the system prompt, both permanently resolve this as "app-only
     *  captures" and are never asked again ([markGalleryPermissionAsked]). */
    private fun showGalleryPermissionDialogIfNeeded() {
        if (hasMediaReadPermission() || hasAskedGalleryPermission()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.gallery_permission_dialog_title)
            .setMessage(R.string.gallery_permission_dialog_message)
            .setCancelable(false)
            .setPositiveButton(R.string.gallery_permission_yes) { _, _ ->
                mediaPermissionLauncher.launch(mediaReadPermissions())
            }
            .setNegativeButton(R.string.gallery_permission_no) { _, _ ->
                markGalleryPermissionAsked()
            }
            .show()
    }

    /** Re-queries MediaStore in the background for the most recent photo/video and refreshes the
     *  last-capture thumbnail button. Safe to call any time (app start, or right after a
     *  photo/video finishes saving) since it re-derives the answer from MediaStore rather than
     *  trusting caller-passed state. Scope depends on [hasMediaReadPermission] - the whole camera
     *  roll if the user granted it, otherwise only this app's own "ANAMORPHIC_"-prefixed saves. */
    private fun refreshLastCaptureThumbnail() {
        Thread {
            val latest = try {
                queryMostRecentCapture()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to query last capture", t)
                null
            } ?: return@Thread
            val bitmap = try {
                loadThumbnailBitmap(latest.uri, latest.isVideo)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load last-capture thumbnail", t)
                null
            } ?: return@Thread
            runOnUiThread {
                lastCaptureUri = latest.uri
                binding.lastCaptureThumbnail.setImageBitmap(bitmap)
                binding.lastCaptureThumbnail.visibility = android.view.View.VISIBLE
            }
        }.start()
    }

    /** Finds the newer of the most recent image/video. With gallery permission granted, this
     *  scans the whole camera roll like a native camera app; otherwise it's filtered to our own
     *  "ANAMORPHIC_" filename prefix (which works uniformly pre/post scoped storage without
     *  needing the Q+-only OWNER_PACKAGE_NAME column). */
    private fun queryMostRecentCapture(): RecentCapture? {
        val ownCapturesOnly = !hasMediaReadPermission()
        val image = queryMostRecentIn(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false, ownCapturesOnly)
        val video = queryMostRecentIn(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true, ownCapturesOnly)
        return listOfNotNull(image, video).maxByOrNull { it.dateAdded }
    }

    private fun queryMostRecentIn(collection: Uri, isVideo: Boolean, ownCapturesOnly: Boolean): RecentCapture? {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
        val selection = if (ownCapturesOnly) "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" else null
        val selectionArgs = if (ownCapturesOnly) arrayOf("ANAMORPHIC_%") else null
        contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                return RecentCapture(ContentUris.withAppendedId(collection, id), isVideo, dateAdded)
            }
        }
        return null
    }

    private fun loadThumbnailBitmap(uri: Uri, isVideo: Boolean): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.loadThumbnail(uri, Size(96, 96), null)
        } else if (isVideo) {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(contentResolver, ContentUris.parseId(uri), MediaStore.Video.Thumbnails.MINI_KIND, null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(contentResolver, ContentUris.parseId(uri), MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    }

    /** Hands the last capture off to the device's default gallery/photos app - this app has no
     *  in-app viewer by design. */
    private fun openLastCapture() {
        val uri = lastCaptureUri ?: return
        val mimeType = contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(binding.root, getString(R.string.no_gallery_app), Snackbar.LENGTH_LONG).show()
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
                refreshLastCaptureThumbnail()
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
                refreshLastCaptureThumbnail()
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
        binding.captureModeVideoLabel.isEnabled = !recording
        binding.captureModePhotoLabel.isEnabled = !recording
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
        private const val PREF_ASKED_GALLERY_PERMISSION = "asked_gallery_permission"
    }
}
