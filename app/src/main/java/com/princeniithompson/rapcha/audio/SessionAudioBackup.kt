package com.princeniithompson.rapcha.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.princeniithompson.rapcha.websocket.InterimPreviewMonitor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight, privacy-safe rolling backup of the active dictation's uncommitted PCM audio.
 *
 * Audio is written to a temporary file inside [Context.getCacheDir] while recording and deleted
 * immediately when the session finishes or cancels. If the live WebSocket stalls or drops on
 * slow/broken internet, [exportForFallback] compresses the saved PCM into compact AAC/M4A
 * (~8x smaller than raw WAV) — falling back to WAV if MediaCodec is unavailable — so Rapcha
 * can transcribe it via non-live `gemini-3.5-transcribe`.
 */
class SessionAudioBackup(private val context: Context) {

    data class ExportedAudio(
        val bytes: ByteArray,
        val mimeType: String,
        val durationMs: Long,
        val pcmBytes: Long
    )

    companion object {
        private const val TAG = "SessionAudioBackup"
        private const val FILE_PREFIX = "rapcha_session_backup_"
        private const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_MS = 32L
        private const val AAC_BITRATE_BPS = 32_000
        private const val CODEC_TIMEOUT_US = 10_000L
        /** Minimum 100 ms of PCM before attempting fallback upload. */
        private const val MIN_FALLBACK_PCM_BYTES = 3_200L
        /** Hard cap (~10 min of 16 kHz 16-bit mono PCM) so cache can never grow unbounded. */
        private const val MAX_BACKUP_PCM_BYTES = 16_000L * 2L * 600L
        private const val ORPHAN_MAX_AGE_MS = 30L * 60L * 1000L
        private val fileSeq = AtomicLong(0L)

        /** Wraps raw 16 kHz / 16-bit / mono PCM in a standard 44-byte RIFF/WAVE container. */
        fun wrapPcmAsWav(pcm: ByteArray): ByteArray {
            val dataSize = pcm.size
            val totalSize = 36 + dataSize
            val byteRate = SAMPLE_RATE_HZ * CHANNEL_COUNT * (BITS_PER_SAMPLE / 8)
            val blockAlign = (CHANNEL_COUNT * (BITS_PER_SAMPLE / 8)).toShort()

            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(totalSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16) // PCM fmt chunk size
                putShort(1) // AudioFormat = 1 (PCM)
                putShort(CHANNEL_COUNT.toShort())
                putInt(SAMPLE_RATE_HZ)
                putInt(byteRate)
                putShort(blockAlign)
                putShort(BITS_PER_SAMPLE.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
            }.array()

            val out = ByteArray(44 + dataSize)
            System.arraycopy(header, 0, out, 0, 44)
            System.arraycopy(pcm, 0, out, 44, dataSize)
            return out
        }
    }

    private val lock = Any()
    private var pcmFile: File? = null
    private var outputStream: FileOutputStream? = null

    @Volatile var bytesWritten: Long = 0L
        private set

    @Volatile var hasSpeech: Boolean = false
        private set

    fun startNewSession() {
        synchronized(lock) {
            closeAndDeleteLocked()
            sweepOrphanedFiles()
            openFreshFileLocked()
        }
    }

    /**
     * Called when an earlier long-dictation piece has already been cleanly committed over
     * the live WebSocket so any later fallback only uploads the uncommitted trailing audio.
     */
    fun resetForCommittedPiece() {
        synchronized(lock) {
            closeAndDeleteLocked()
            openFreshFileLocked()
        }
    }

    fun append(pcmChunk: ByteArray) {
        if (pcmChunk.isEmpty()) return
        val rms = InterimPreviewMonitor.pcm16leRms(pcmChunk)
        synchronized(lock) {
            if (bytesWritten + pcmChunk.size > MAX_BACKUP_PCM_BYTES) return
            val stream = outputStream ?: return
            try {
                stream.write(pcmChunk)
                bytesWritten += pcmChunk.size
                if (!hasSpeech && rms >= InterimPreviewMonitor.SPEECH_RMS_THRESHOLD) {
                    hasSpeech = true
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed writing session backup chunk: ${e.message}")
            }
        }
    }

    /**
     * Flushes the recorded PCM and returns a compact M4A/AAC payload (or WAV fallback) ready
     * for single-POST transcription with `gemini-3.5-transcribe`.
     */
    fun exportForFallback(): ExportedAudio? {
        val snapshotFile: File
        val capturedBytes: Long
        val heardSpeech: Boolean
        synchronized(lock) {
            try {
                outputStream?.flush()
                outputStream?.close()
            } catch (_: Throwable) {
            }
            outputStream = null
            snapshotFile = pcmFile ?: return null
            capturedBytes = bytesWritten
            heardSpeech = hasSpeech
        }

        if (!heardSpeech || capturedBytes < MIN_FALLBACK_PCM_BYTES || !snapshotFile.exists()) {
            return null
        }

        val durationMs = capturedBytes / BYTES_PER_MS
        val m4aFile = File(context.cacheDir, "${FILE_PREFIX}${fileSeq.incrementAndGet()}.m4a")
        try {
            if (encodePcmToM4a(snapshotFile, m4aFile) && m4aFile.exists() && m4aFile.length() > 128L) {
                val encodedBytes = m4aFile.readBytes()
                if (encodedBytes.isNotEmpty() && encodedBytes.size < capturedBytes) {
                    Log.i(
                        TAG,
                        "Compressed fallback audio: ${capturedBytes / 1024}KB PCM -> ${encodedBytes.size / 1024}KB M4A (${durationMs}ms)"
                    )
                    return ExportedAudio(
                        bytes = encodedBytes,
                        mimeType = "audio/mp4",
                        durationMs = durationMs,
                        pcmBytes = capturedBytes
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AAC encoding unavailable; falling back to WAV (${t.javaClass.simpleName}: ${t.message})")
        } finally {
            runCatching { if (m4aFile.exists()) m4aFile.delete() }
        }

        return try {
            val rawPcm = snapshotFile.readBytes()
            if (rawPcm.size < MIN_FALLBACK_PCM_BYTES) return null
            val wav = wrapPcmAsWav(rawPcm)
            Log.i(TAG, "Prepared WAV fallback audio: ${wav.size / 1024}KB (${durationMs}ms)")
            ExportedAudio(
                bytes = wav,
                mimeType = "audio/wav",
                durationMs = durationMs,
                pcmBytes = capturedBytes
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed reading fallback PCM file: ${t.message}")
            null
        }
    }

    fun cleanup() {
        synchronized(lock) {
            closeAndDeleteLocked()
        }
    }

    private fun openFreshFileLocked() {
        bytesWritten = 0L
        hasSpeech = false
        try {
            val dir = context.cacheDir
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${FILE_PREFIX}${System.currentTimeMillis()}_${fileSeq.incrementAndGet()}.pcm")
            pcmFile = file
            outputStream = FileOutputStream(file)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not open session backup file: ${t.message}")
            pcmFile = null
            outputStream = null
        }
    }

    private fun closeAndDeleteLocked() {
        try {
            outputStream?.flush()
            outputStream?.close()
        } catch (_: Throwable) {
        }
        outputStream = null
        try {
            pcmFile?.let { if (it.exists()) it.delete() }
        } catch (_: Throwable) {
        }
        pcmFile = null
        bytesWritten = 0L
        hasSpeech = false
    }

    private fun sweepOrphanedFiles() {
        try {
            val now = System.currentTimeMillis()
            context.cacheDir?.listFiles { file ->
                file.name.startsWith(FILE_PREFIX) && (now - file.lastModified() > ORPHAN_MAX_AGE_MS)
            }?.forEach { stale ->
                runCatching { stale.delete() }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Encodes raw 16 kHz / 16-bit / mono PCM into a standard MPEG-4 AAC (.m4a) file using
     * Android's built-in MediaCodec + MediaMuxer (zero external native libraries).
     */
    private fun encodePcmToM4a(pcmSource: File, m4aDest: File): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1

        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE_HZ,
                CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE_BPS)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(m4aDest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            val readBuf = ByteArray(4_096)

            var inputDone = false
            var outputDone = false
            var totalBytesFed = 0L

            FileInputStream(pcmSource).use { fis ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                inBuf.clear()
                                val maxRead = minOf(readBuf.size, inBuf.remaining())
                                val read = fis.read(readBuf, 0, maxRead)
                                if (read <= 0) {
                                    val ptsUs = (totalBytesFed * 1_000_000L) / (SAMPLE_RATE_HZ * 2L)
                                    codec.queueInputBuffer(
                                        inIdx,
                                        0,
                                        0,
                                        ptsUs,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    inBuf.put(readBuf, 0, read)
                                    val ptsUs = (totalBytesFed * 1_000_000L) / (SAMPLE_RATE_HZ * 2L)
                                    codec.queueInputBuffer(inIdx, 0, read, ptsUs, 0)
                                    totalBytesFed += read
                                }
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                trackIndex = muxer.addTrack(codec.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (!isConfig && bufferInfo.size > 0 && outBuf != null && muxerStarted) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
            muxerStarted
        } catch (t: Throwable) {
            Log.d(TAG, "encodePcmToM4a failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }
    }
}
