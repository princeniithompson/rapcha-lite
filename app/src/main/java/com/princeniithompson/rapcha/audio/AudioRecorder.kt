package com.princeniithompson.rapcha.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

sealed class AudioRecorderState {
    object Idle : AudioRecorderState()
    object Starting : AudioRecorderState()
    object Recording : AudioRecorderState()
    object Stopping : AudioRecorderState()
    data class Error(val message: String) : AudioRecorderState()
}

/**
 * Microphone capture for Gemini Live.
 *
 * v25:
 * - 16 kHz, mono, 16-bit little-endian PCM (matches audio/pcm;rate=16000).
 * - AudioSource.MIC first (3 tries), then VOICE_RECOGNITION.
 * - Platform NoiseSuppressor / AcousticEchoCanceler / AutomaticGainControl are switched
 *   off for the session when the device exposes them, so quiet speech is not gated away.
 * - A dedicated thread at THREAD_PRIORITY_URGENT_AUDIO reads the mic (no shared IO pool),
 *   with about one second of AudioRecord buffer so a busy CPU does not overrun it.
 * - Clean 100 ms (3200 byte) packets go to [onChunkReady]. The bytes here are raw;
 *   any gain is applied later on the transmitted copy only.
 * - [stopAndJoin] can keep reading a short tail and then flushes the last partial
 *   packet, so the last word before Complete is not cut.
 */
class AudioRecorder(
    private val onChunkReady: (ByteArray) -> Unit,
    private val onAmplitudeChanged: (Float) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        /** 100 ms at 16 kHz 16-bit mono PCM. */
        const val CHUNK_SIZE_BYTES = 3200
        /** 20 ms read slices. */
        private const val SLICE_SIZE_BYTES = 640
        /** About one second of AudioRecord buffer. */
        const val TARGET_BUFFER_BYTES = SAMPLE_RATE * 2
        private const val MIC_OPEN_ATTEMPTS = 3
        private const val MIC_RETRY_DELAY_MS = 150L
        /** Shortest partial packet worth sending at stop (10 ms). */
        private const val MIN_TAIL_BYTES = 320

        /** AudioRecord buffer size: at least 4x the minimum and about one second. */
        fun bufferSizeFor(minBufferSize: Int): Int =
            maxOf(minBufferSize * 4, TARGET_BUFFER_BYTES, CHUNK_SIZE_BYTES * 2)

        /** The partial packet left at stop, or null when it is too short to matter. */
        fun tailPacket(buffer: ByteArray, filled: Int): ByteArray? {
            val even = filled - (filled % 2)
            if (even < MIN_TAIL_BYTES) return null
            return buffer.copyOf(even)
        }
    }

    @Volatile
    private var activeSourceName: String = "MIC"

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var finished: CompletableDeferred<Unit>? = null
    private val isRecording = AtomicBoolean(false)

    /** Elapsed-realtime deadline for a graceful stop; 0 means keep recording. */
    @Volatile private var drainDeadlineMs = 0L
    @Volatile private var hardStop = false

    private val effects = ArrayList<AudioEffect>()

    private val _state = MutableStateFlow<AudioRecorderState>(AudioRecorderState.Idle)
    val state: StateFlow<AudioRecorderState> = _state.asStateFlow()

    fun isFullyReleased(): Boolean = _state.value is AudioRecorderState.Idle && !isRecording.get()

    fun start() {
        if (isRecording.getAndSet(true)) {
            Log.w(TAG, "Recording already in progress, restarting session cleanly")
            stop()
            isRecording.set(true)
        }
        _state.value = AudioRecorderState.Starting
        drainDeadlineMs = 0L
        hardStop = false
        val done = CompletableDeferred<Unit>()
        finished = done
        val t = Thread({ runCapture(done) }, "rapcha-mic")
        t.isDaemon = true
        thread = t
        t.start()
    }

    private fun runCapture(done: CompletableDeferred<Unit>) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }
        var recorder: AudioRecord? = null
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufferSize <= 0) {
                val err = "AudioRecord initialization error: Invalid minBufferSize ($minBufferSize)"
                _state.value = AudioRecorderState.Error(err)
                onError(err)
                return
            }
            recorder = openStartedRecorder(bufferSizeFor(minBufferSize))
            if (recorder == null) {
                if (isRecording.get()) {
                    val err = "Microphone initialization failed"
                    _state.value = AudioRecorderState.Error(err)
                    onError(err)
                }
                return
            }
            audioRecord = recorder
            disablePlatformEffects(recorder.audioSessionId)
            _state.value = AudioRecorderState.Recording

            val chunkBuffer = ByteArray(CHUNK_SIZE_BYTES)
            var filled = 0
            while (true) {
                if (hardStop) break
                val deadline = drainDeadlineMs
                if (deadline > 0L && SystemClock.elapsedRealtime() >= deadline) break
                if (!isRecording.get() && deadline == 0L) break

                val toRead = minOf(SLICE_SIZE_BYTES, CHUNK_SIZE_BYTES - filled)
                val read = recorder.read(chunkBuffer, filled, toRead)
                if (read < 0) {
                    if (isRecording.get() && !hardStop) {
                        val name = when (read) {
                            AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION (-3)"
                            AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE (-2)"
                            AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT (-6)"
                            AudioRecord.ERROR -> "ERROR (-1)"
                            else -> "Code $read"
                        }
                        onError("AudioRecord read failure: $name")
                    }
                    break
                }
                filled += read
                if (filled >= CHUNK_SIZE_BYTES) {
                    val packet = chunkBuffer.copyOf()
                    filled = 0
                    onAmplitudeChanged(meterValue(packet))
                    onChunkReady(packet)
                }
            }
            // Graceful stop: the last partial packet still carries speech.
            if (!hardStop) {
                tailPacket(chunkBuffer, filled)?.let { onChunkReady(it) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception in capture thread: ${t.message}", t)
            if (isRecording.get() && !hardStop) {
                onError("Microphone error: ${t.message ?: t.javaClass.simpleName}")
            }
        } finally {
            releaseRecorderSafely(recorder)
            done.complete(Unit)
        }
    }

    /**
     * Opens and starts an AudioRecord. Tries MIC up to [MIC_OPEN_ATTEMPTS] times (150 ms apart),
     * then VOICE_RECOGNITION once. MIC stays first: on budget phones VOICE_RECOGNITION is often
     * much quieter, and the near-silence gate in TranscriptAcceptance was calibrated on MIC.
     */
    @SuppressLint("MissingPermission")
    private fun openStartedRecorder(bufferSize: Int): AudioRecord? {
        val plan = ArrayList<Pair<Int, String>>()
        repeat(MIC_OPEN_ATTEMPTS) { plan.add(MediaRecorder.AudioSource.MIC to "MIC") }
        plan.add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION")
        var lastProblem = "unknown"
        for ((index, attempt) in plan.withIndex()) {
            if (!isRecording.get() || hardStop) return null
            if (index > 0) {
                try { Thread.sleep(MIC_RETRY_DELAY_MS) } catch (_: InterruptedException) { return null }
            }
            var candidate: AudioRecord? = null
            try {
                candidate = AudioRecord(attempt.first, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                    lastProblem = "${attempt.second}:not_initialized"
                    candidate.release()
                    continue
                }
                candidate.startRecording()
                if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    lastProblem = "${attempt.second}:start_refused(busy)"
                    try { candidate.stop() } catch (_: Throwable) {}
                    candidate.release()
                    continue
                }
                activeSourceName = attempt.second
                return candidate
            } catch (t: Throwable) {
                lastProblem = "${attempt.second}:${t.javaClass.simpleName}"
                try { candidate?.release() } catch (_: Throwable) {}
            }
        }
        Log.e(TAG, "Microphone could not be opened: $lastProblem")
        return null
    }

    /**
     * Switches off platform pre-processing on this capture session when the device exposes it.
     * Creating the effect and setting enabled=false overrides an OEM default that applies
     * noise suppression or AGC to AudioSource.MIC.
     */
    private fun disablePlatformEffects(sessionId: Int) {
        releaseEffects()
        fun keepDisabled(effect: AudioEffect?) {
            if (effect == null) return
            try {
                effect.enabled = false
                effects.add(effect)
            } catch (_: Throwable) {
                try { effect.release() } catch (_: Throwable) {}
            }
        }
        try { if (NoiseSuppressor.isAvailable()) keepDisabled(NoiseSuppressor.create(sessionId)) } catch (_: Throwable) {}
        try { if (AcousticEchoCanceler.isAvailable()) keepDisabled(AcousticEchoCanceler.create(sessionId)) } catch (_: Throwable) {}
        try { if (AutomaticGainControl.isAvailable()) keepDisabled(AutomaticGainControl.create(sessionId)) } catch (_: Throwable) {}
    }

    private fun releaseEffects() {
        for (e in effects) {
            try { e.release() } catch (_: Throwable) {}
        }
        effects.clear()
    }

    /** Immediate stop (cancel / teardown). The partial packet is dropped. */
    fun stop() {
        if (!isRecording.getAndSet(false)) return
        hardStop = true
        _state.value = AudioRecorderState.Stopping
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
    }

    /**
     * Stops and waits for the capture thread to release the microphone.
     * With [tailMs] > 0 the thread keeps reading for that long first and then sends the
     * last partial packet, so the end of the final word is kept.
     */
    suspend fun stopAndJoin(tailMs: Long = 0L) {
        val done = finished
        if (tailMs > 0L && isRecording.get() && !hardStop) {
            drainDeadlineMs = SystemClock.elapsedRealtime() + tailMs
            isRecording.set(false)
            _state.value = AudioRecorderState.Stopping
        } else {
            stop()
        }
        try {
            done?.await()
        } catch (_: Throwable) {
        }
        thread = null
        _state.value = AudioRecorderState.Idle
    }

    private fun releaseRecorderSafely(recorder: AudioRecord?) {
        isRecording.set(false)
        releaseEffects()
        try {
            recorder?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
            _state.value = AudioRecorderState.Idle
            onAmplitudeChanged(0f)
        }
    }

    /** 0..1 level of a PCM16 LE packet, for the session's speech tracking. */
    private fun meterValue(pcm: ByteArray): Float {
        val samples = pcm.size / 2
        if (samples <= 0) return 0f
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        val rms = sqrt(sum / samples)
        val normalized = (rms / 3200.0).coerceIn(0.0, 1.0)
        return (sqrt(normalized).toFloat() * 1.25f).coerceIn(0f, 1f)
    }
}
