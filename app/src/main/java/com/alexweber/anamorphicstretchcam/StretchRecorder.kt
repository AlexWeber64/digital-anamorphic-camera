package com.alexweber.anamorphicstretchcam

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hand-rolled MediaCodec + MediaMuxer recording path. CameraX's `VideoCapture` use case is
 * deliberately not used (see CameraSource) because it owns its own internal recording Surface
 * with no hook for injecting the GL-transformed frame - only a MediaCodec input Surface created
 * here, and handed to StretchGLRenderer as a second render target, can receive the same
 * distorted pixels that hit the screen.
 *
 * Video comes in via [videoInputSurface] (Surface-backed encoder input - no queueInputBuffer
 * calls needed, the GL renderer draws directly into it). Audio, if enabled, is captured from
 * AudioRecord on its own thread and fed into a second MediaCodec AAC encoder via regular
 * buffer-based input. Both tracks are muxed into a single MP4 written through MediaStore so the
 * file lands in DCIM/Camera and shows up in the gallery with no extra storage permission needed
 * on API 29+.
 */
class StretchRecorder(private val context: Context) {

    data class Config(
        val outputWidth: Int,
        val outputHeight: Int,
        val frameRate: Int = 30,
        val videoBitRate: Int = 16_000_000,
        val useAudio: Boolean = true
    )

    interface Callback {
        fun onSaved(uri: Uri, displayName: String)
        fun onError(error: Throwable)
    }

    private var videoCodec: MediaCodec? = null
    private var cachedVideoSurface: Surface? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var muxerGate: MuxerGate? = null

    private var videoThread: Thread? = null
    private var audioCaptureThread: Thread? = null
    private var audioDrainThread: Thread? = null

    private var pendingUri: Uri? = null
    private var pendingFile: File? = null
    private var displayName: String = ""

    private val recording = AtomicBoolean(false)
    private val audioEos = AtomicBoolean(false)

    /** Call before [start]; obtains (and caches) the encoder's Surface for the GL renderer. */
    fun prepareVideoSurface(config: Config): Surface {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, config.outputWidth, config.outputHeight
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.videoBitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        videoCodec = codec
        cachedVideoSurface = surface
        return surface
    }

    fun start(config: Config, orientationHintDegrees: Int, callback: Callback) {
        val codec = requireNotNull(videoCodec) { "Call prepareVideoSurface() first" }
        codec.start()

        val (uri, file, name, pfdMuxer) = createOutputMuxer()
        pendingUri = uri
        pendingFile = file
        displayName = name
        muxer = pfdMuxer
        Log.d(TAG, "ROTATION_DEBUG muxer.setOrientationHint($orientationHintDegrees)")
        muxer!!.setOrientationHint(orientationHintDegrees)

        // Audio hardware (AudioSource.CAMCORDER in particular) can fail to initialize on some
        // devices/emulators; probe it *before* deciding expectedTracks below, otherwise a failure
        // discovered only inside the already-running capture thread would leave the MuxerGate
        // permanently waiting on a second track that never arrives, hanging finalizeWhenDone().
        var audioSetup: AudioSetup? = null
        if (config.useAudio) {
            audioSetup = try {
                prepareAudio()
            } catch (t: Throwable) {
                Log.e(TAG, "Audio capture unavailable, recording video only", t)
                null
            }
        }

        muxerGate = MuxerGate(pfdMuxer, expectedTracks = if (audioSetup != null) 2 else 1)
        audioEos.set(false)
        recording.set(true)

        videoThread = Thread({ drainVideoLoop(codec) }, "StretchRecorder-Video").apply { start() }

        if (audioSetup != null) {
            startAudioThreads(audioSetup)
        }

        Thread({ finalizeWhenDone(callback) }, "StretchRecorder-Finalizer").start()
    }

    fun stop() {
        if (!recording.getAndSet(false)) return
        videoCodec?.signalEndOfInputStream()
        audioEos.set(true)
    }

    private data class AudioSetup(val record: AudioRecord, val codec: MediaCodec, val sampleRate: Int, val minBufferSize: Int)

    private fun prepareAudio(): AudioSetup {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        val record = createAudioRecord(sampleRate, channelConfig, minBufferSize)
        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioCodec = codec
        audioRecord = record
        return AudioSetup(record, codec, sampleRate, minBufferSize)
    }

    private fun startAudioThreads(setup: AudioSetup) {
        val (record, codec, sampleRate, minBufferSize) = setup
        record.startRecording()

        var totalSamples = 0L
        audioCaptureThread = Thread({
            val chunk = ByteArray(minBufferSize)
            while (recording.get()) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(chunk, 0, read)
                    val presentationTimeUs = totalSamples * 1_000_000L / sampleRate
                    totalSamples += read / 2 // 16-bit mono samples
                    codec.queueInputBuffer(inputIndex, 0, read, presentationTimeUs, 0)
                }
            }
            // Drain any remaining PCM, then signal EOS on the audio encoder.
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val presentationTimeUs = totalSamples * 1_000_000L / sampleRate
                codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }, "StretchRecorder-AudioCapture").apply { start() }

        audioDrainThread = Thread({ drainAudioLoop(codec) }, "StretchRecorder-AudioDrain").apply { start() }
    }

    /**
     * AudioSource.CAMCORDER isn't guaranteed on every device (emulators in particular often
     * fail to initialize it); if construction leaves the AudioRecord in STATE_UNINITIALIZED,
     * fall back to the universally-supported MIC source rather than silently recording nothing.
     */
    private fun createAudioRecord(sampleRate: Int, channelConfig: Int, minBufferSize: Int): AudioRecord {
        val bufferSizeBytes = minBufferSize * 4
        var record = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeBytes
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioSource.CAMCORDER failed to initialize, falling back to AudioSource.MIC")
            record.release()
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes
            )
        }
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
        return record
    }

    private fun drainVideoLoop(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var trackIndex = -1
        loop@ while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* keep polling */ }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxerGate!!.addTrack(codec.outputFormat)
                }
                index >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(index)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0 && outputBuffer != null && trackIndex >= 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxerGate!!.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break@loop
                    }
                }
            }
        }
        codec.stop()
        codec.release()
        cachedVideoSurface?.release()
    }

    private fun drainAudioLoop(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var trackIndex = -1
        loop@ while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* keep polling */ }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxerGate!!.addTrack(codec.outputFormat)
                }
                index >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(index)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0 && outputBuffer != null && trackIndex >= 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxerGate!!.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break@loop
                    }
                }
            }
        }
        codec.stop()
        codec.release()
        audioRecord?.stop()
        audioRecord?.release()
    }

    private fun finalizeWhenDone(callback: Callback) {
        videoThread?.join()
        audioDrainThread?.join()
        audioCaptureThread?.join()
        try {
            muxerGate?.stop()
            muxer?.release()
        } catch (t: Throwable) {
            callback.onError(t)
            return
        }

        val uri = pendingUri
        val file = pendingFile
        try {
            if (uri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    context.contentResolver.update(uri, values, null, null)
                }
                callback.onSaved(uri, displayName)
            } else if (file != null) {
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                callback.onSaved(Uri.fromFile(file), displayName)
            }
        } catch (t: Throwable) {
            callback.onError(t)
        }
    }

    private data class MuxerCreationResult(
        val uri: Uri?,
        val file: File?,
        val displayName: String,
        val muxer: MediaMuxer
    )

    private fun createOutputMuxer(): MuxerCreationResult {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "ANAMORPHIC_$timestamp.mp4"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("openFileDescriptor failed")
            val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            MuxerCreationResult(uri, null, name, muxer)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name)
            val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            MuxerCreationResult(null, file, name, muxer)
        }
    }

    /**
     * Serializes MediaMuxer access across the independent video/audio drain threads and gates
     * muxer.start() until every expected track (video, plus audio when enabled) has registered
     * its format - MediaMuxer requires all tracks added before the first writeSampleData call.
     */
    private class MuxerGate(private val muxer: MediaMuxer, private val expectedTracks: Int) {
        private val lock = Object()
        private var addedTracks = 0
        private var started = false

        fun addTrack(format: MediaFormat): Int {
            synchronized(lock) {
                val index = muxer.addTrack(format)
                addedTracks++
                if (addedTracks >= expectedTracks) {
                    muxer.start()
                    started = true
                    lock.notifyAll()
                }
                return index
            }
        }

        fun writeSampleData(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            synchronized(lock) {
                while (!started) lock.wait()
                muxer.writeSampleData(trackIndex, buffer, info)
            }
        }

        fun stop() {
            synchronized(lock) {
                if (started) muxer.stop()
            }
        }
    }

    companion object {
        private const val TAG = "StretchRecorder"
        private const val TIMEOUT_US = 10_000L
    }
}
