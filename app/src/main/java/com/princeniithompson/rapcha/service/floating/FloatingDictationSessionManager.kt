package com.princeniithompson.rapcha.service.floating

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.princeniithompson.rapcha.audio.AudioRecorder
import com.princeniithompson.rapcha.data.ConnectionState
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.websocket.DictationPiecePlanner
import com.princeniithompson.rapcha.websocket.DictationSessionClock
import com.princeniithompson.rapcha.websocket.GeminiLiveWebSocketClient
import com.princeniithompson.rapcha.websocket.InterimPreviewMonitor
import com.princeniithompson.rapcha.websocket.TranscriptSeam
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

/**
 * One SMART Gemini Live session: mic -> ordered send queue -> WebSocket -> transcript.
 *
 * v25: no retry WAV, no Stage-2 polish hooks. Audio is sent strictly in capture order
 * under one lock (the old direct-send + delayed drain could reorder chunks around a
 * reconnect). Up to 15 s is held while the socket connects or reconnects. Complete keeps
 * reading the mic for a short tail and flushes the last partial packet before activityEnd.
 */
class FloatingDictationSessionManager(
    private val context: Context,
    private val onTranscriptUpdated: (fullText: String) -> Unit,
    private val onInterimReceived: (interim: String) -> Unit,
    private val onFinalSegmentReceived: (segment: String) -> Unit,
    private val onConnectionStateChanged: (state: ConnectionState) -> Unit,
    private val onAmplitudeChanged: (amplitude: Float) -> Unit,
    private val onError: (errorMsg: String) -> Unit,
    private val onDurationTicked: (seconds: Int) -> Unit,
    private val onSessionLimit: (message: String, finalizeNow: Boolean) -> Unit = { _, _ -> },
    private val onPreviewPhase: (InterimPreviewMonitor.Phase) -> Unit = {}
) {
    companion object {
        private const val TAG = "FloatingDictationMgr"
        /** ~15 s of 100 ms chunks held while the socket connects or reconnects. */
        const val MAX_AUDIO_QUEUE_CHUNKS = 150
        /** Mic keeps reading this long after Complete so the last word is not clipped. */
        const val COMPLETE_TAIL_MS = 180L

        internal fun elapsedSeconds(startedAtElapsed: Long, nowElapsed: Long): Int {
            if (startedAtElapsed <= 0L) return 0
            return ((nowElapsed - startedAtElapsed).coerceAtLeast(0L) / 1000L).toInt()
        }

        internal fun reconcileFinalTranscript(accumulated: String, endpointFinal: String): String {
            val acc = accumulated.trim()
            val fin = endpointFinal.trim()
            if (fin.isEmpty()) return acc
            if (acc.isEmpty()) return fin
            if (fin == acc || acc.endsWith(fin)) return acc
            if (fin.startsWith(acc) || fin.length >= acc.length) return fin
            return TranscriptSeam.append(acc, fin)
        }
    }

    private var sessionStartedAtElapsed = 0L

    /** Ordered audio sender; see [OrderedAudioSender]. */
    private val sender = OrderedAudioSender(MAX_AUDIO_QUEUE_CHUNKS)

    @Volatile private var webSocketClient: GeminiLiveWebSocketClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var sessionJob: CompletableJob? = null
    private var durationJob: Job? = null
    private var stopJob: Job? = null

    var durationSeconds: Int = 0
        private set

    @Volatile
    var isRecording: Boolean = false

    val sessionChunksSent: Int get() = sender.chunksSent
    val sessionBytesSent: Long get() = sender.bytesSent

    private val finalizedTranscript = StringBuilder()
    private var interimTranscript = ""
    private var sessionClock = DictationSessionClock()
    private var finishAtNextPause = false

    private val audioRecorder = AudioRecorder(
        onChunkReady = { chunk ->
            if (isRecording) sender.submit(chunk, webSocketClient?.let(::LiveSink))
        },
        onAmplitudeChanged = { amp -> onAmplitudeChanged(if (isRecording) amp.coerceIn(0f, 1f) else 0f) },
        onError = { err ->
            Log.e(TAG, "AudioRecorder error: $err")
            DictationAlerts.microphoneFailed(context)
        }
    )

    /** Adapts the Live client to the sender's sink interface. */
    private class LiveSink(private val ws: GeminiLiveWebSocketClient) : OrderedAudioSender.Sink {
        override val ready: Boolean get() = ws.setupComplete
        override fun send(chunk: ByteArray): Boolean = ws.sendAudioChunk(chunk)
    }

    fun startSession(
        context: Context,
        scope: CoroutineScope,
        apiKey: String,
        model: String,
        smartMode: Boolean
    ) {
        if (isRecording) return
        isRecording = true

        sessionJob?.cancel()
        val sJob = SupervisorJob()
        sessionJob = sJob
        val sessionScope = CoroutineScope(Dispatchers.Main + sJob)

        FloatingBubbleManager.setNetworkProblem(false)

        finalizedTranscript.clear()
        interimTranscript = ""
        sessionClock = DictationSessionClock()
        finishAtNextPause = false
        sessionStartedAtElapsed = SystemClock.elapsedRealtime()
        durationSeconds = 0
        sender.reset()

        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld == true) {
                try { wakeLock?.release() } catch (_: Exception) {}
            }
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "voxstream:floating_dictation_wakelock"
            ).apply { acquire(30 * 60 * 1000L) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WakeLock", e)
        }

        audioRecorder.start()

        webSocketClient = GeminiLiveWebSocketClient(
            onSetupComplete = {
                FloatingBubbleManager.setNetworkProblem(false)
                val ws = webSocketClient
                if (ws != null && ws.setupComplete) {
                    ws.signalActivityStart()
                    sessionScope.launch(Dispatchers.IO) { sender.flush(LiveSink(ws)) }
                }
            },
            onInterimTranscription = { text ->
                interimTranscript = text
                onInterimReceived(text)
                onTranscriptUpdated(getFullTranscriptText())
            },
            onPieceCommitted = { piece ->
                appendPiece(piece)
                interimTranscript = ""
                onTranscriptUpdated(getFullTranscriptText())
            },
            onGoAway = {
                refreshDurationFromClock()
                if (sessionClock.shouldSplitOnGoAway(durationSeconds * 1000L)) {
                    webSocketClient?.requestFreshConnectionSplit()
                } else if (isRecording) {
                    onSessionLimit("Session ended at 10 minutes.", true)
                }
            },
            onAudiblePause = {
                if (finishAtNextPause && isRecording) {
                    onSessionLimit("Session ended at 10 minutes.", true)
                }
            },
            onFinalizedTranscription = { text ->
                val trimmed = text.trim()
                appendPiece(trimmed)
                interimTranscript = ""
                onFinalSegmentReceived(trimmed)
                onTranscriptUpdated(getFullTranscriptText())
            },
            onStateChanged = { state ->
                val isProblem = (state is ConnectionState.Error) ||
                    (state is ConnectionState.Connecting && (webSocketClient?.isReconnecting == true))
                FloatingBubbleManager.setNetworkProblem(isProblem)
                onConnectionStateChanged(state)
            },
            onLog = { _, _, _, _ -> },
            onError = { err ->
                Log.e(TAG, "WebSocket error: $err")
                FloatingBubbleManager.setNetworkProblem(true)
                onError(err)
            },
            isSessionActive = { isRecording },
            diagnosticSource = DiagnosticSource.BUBBLE,
            onPreviewPhase = onPreviewPhase
        ).apply {
            val customVocab = CustomVocabularyRepository.getVocabulary()
            try {
                connect(apiKey = apiKey, model = model, smartMode = smartMode, customVocabulary = customVocab)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect WebSocket: ${e.message}")
                FloatingBubbleManager.setNetworkProblem(true)
                onError(e.message ?: "Connection error")
            }
        }

        durationJob?.cancel()
        durationJob = sessionScope.launch {
            while (isActive && isRecording) {
                delay(1000)
                refreshDurationFromClock()
                webSocketClient?.pollInterimPreview()
                onSessionClockTick()
                onDurationTicked(durationSeconds)
            }
        }
    }

    private fun refreshDurationFromClock() {
        durationSeconds = elapsedSeconds(sessionStartedAtElapsed, SystemClock.elapsedRealtime())
    }

    /** Cancel path: drop the mic and the socket without waiting for a transcript. */
    fun stopSession(scope: CoroutineScope, endedReason: String = "completed") {
        if (!isRecording) return
        isRecording = false
        FloatingBubbleManager.setNetworkProblem(false)
        durationJob?.cancel()
        durationJob = null

        val ws = webSocketClient
        webSocketClient = null
        val wl = wakeLock
        wakeLock = null
        sessionJob?.cancel()
        sessionJob = null
        sender.reset()

        stopJob = scope.launch(Dispatchers.IO) {
            try {
                audioRecorder.stopAndJoin()
                ws?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio/websocket ($endedReason)", e)
            } finally {
                try {
                    if (wl?.isHeld == true) wl.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing WakeLock", e)
                }
            }
        }
    }

    /**
     * Stop the microphone. With [tailMs] the recorder reads a little longer and flushes its
     * last partial packet through the normal send path before the mic is released.
     */
    suspend fun releaseMicrophoneNow(tailMs: Long = 0L) {
        durationJob?.cancel()
        durationJob = null
        audioRecorder.stopAndJoin(tailMs)
        isRecording = false
    }

    /**
     * User pressed Complete. Capture the tail, flush every queued chunk in order, send
     * activityEnd, and wait for the authoritative transcript.
     */
    suspend fun finishActivityAndAwaitTranscript(): String {
        if (!isRecording && webSocketClient == null) {
            return getFullTranscriptText()
        }
        releaseMicrophoneNow(COMPLETE_TAIL_MS)

        val ws = webSocketClient
        val wl = wakeLock
        wakeLock = null

        return try {
            if (ws != null) {
                withContext(Dispatchers.IO) { sender.flush(LiveSink(ws)) }
            }
            val finalText = ws?.endActivityAndAwaitFinal().orEmpty().trim()
            val reconciled = reconcileFinalTranscript(finalizedTranscript.toString(), finalText)
            if (reconciled.isNotEmpty()) {
                finalizedTranscript.clear()
                finalizedTranscript.append(reconciled)
            }
            interimTranscript = ""
            val text = getFullTranscriptText().ifBlank { finalText }
            onTranscriptUpdated(text)
            text
        } catch (e: Exception) {
            Log.e(TAG, "Error finishing manual activity", e)
            getFullTranscriptText()
        } finally {
            try {
                ws?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting after activityEnd", e)
            }
            webSocketClient = null
            sessionJob?.cancel()
            sessionJob = null
            sender.reset()
            try {
                if (wl?.isHeld == true) wl.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing WakeLock", e)
            }
        }
    }

    private fun onSessionClockTick() {
        val client = webSocketClient
        val tick = sessionClock.onTick(
            sessionMs = durationSeconds * 1000L,
            singleConnection = client?.isSingleConnection() != false
        )
        if (tick.warn) {
            onSessionLimit("1 minute left in this session", false)
        }
        if (tick.armFinishAtPause) {
            finishAtNextPause = true
            if ((client?.currentPauseMs() ?: 0L) >= DictationPiecePlanner.PAUSE_MS) {
                onSessionLimit("Session ended at 10 minutes.", true)
            }
        }
        if (tick.hardStop) {
            onSessionLimit("Session ended at 10 minutes.", true)
        }
    }

    private fun appendPiece(piece: String) {
        val trimmed = piece.trim()
        if (trimmed.isEmpty()) return
        val current = finalizedTranscript.toString().trim()
        if (current == trimmed || current.endsWith(" $trimmed")) return
        finalizedTranscript.clear()
        finalizedTranscript.append(TranscriptSeam.append(current, trimmed))
    }

    fun getFullTranscriptText(): String {
        val sb = StringBuilder()
        if (finalizedTranscript.isNotEmpty()) sb.append(finalizedTranscript)
        if (interimTranscript.isNotEmpty()) {
            if (sb.isNotEmpty() && !sb.endsWith(" ")) sb.append(" ")
            sb.append(interimTranscript)
        }
        return sb.toString().trim()
    }

    fun clearTranscripts() {
        finalizedTranscript.clear()
        interimTranscript = ""
        onTranscriptUpdated("")
    }

    fun release() {
        isRecording = false
        FloatingBubbleManager.setNetworkProblem(false)
        durationJob?.cancel()
        durationJob = null
        sessionJob?.cancel()
        sessionJob = null
        sender.reset()
        try {
            audioRecorder.stop()
            webSocketClient?.disconnect()
            webSocketClient = null
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing session manager", e)
        }
    }
}

/**
 * Sends audio chunks strictly in capture order. A chunk goes straight out only when the
 * socket is ready and nothing is waiting; otherwise it joins the queue, and the queue is
 * flushed in order under the same lock. When the queue is full the oldest chunk is dropped.
 */
class OrderedAudioSender(private val capacity: Int) {
    interface Sink {
        val ready: Boolean
        fun send(chunk: ByteArray): Boolean
    }

    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>()

    @Volatile var chunksSent: Int = 0
        private set
    @Volatile var bytesSent: Long = 0L
        private set
    @Volatile var dropped: Int = 0
        private set

    val queued: Int get() = synchronized(lock) { queue.size }

    fun reset() = synchronized(lock) {
        queue.clear()
        chunksSent = 0
        bytesSent = 0L
        dropped = 0
    }

    fun submit(chunk: ByteArray, sink: Sink?) = synchronized(lock) {
        if (sink != null && sink.ready) {
            drainLocked(sink)
            if (queue.isEmpty() && sink.send(chunk)) {
                chunksSent++
                bytesSent += chunk.size
                return@synchronized
            }
        }
        enqueueLocked(chunk)
    }

    fun flush(sink: Sink) = synchronized(lock) {
        if (sink.ready) drainLocked(sink)
    }

    private fun enqueueLocked(chunk: ByteArray) {
        while (queue.size >= capacity) {
            queue.pollFirst()
            dropped++
        }
        queue.addLast(chunk)
    }

    private fun drainLocked(sink: Sink) {
        while (true) {
            val next = queue.peekFirst() ?: return
            if (!sink.send(next)) return
            queue.pollFirst()
            chunksSent++
            bytesSent += next.size
        }
    }
}
