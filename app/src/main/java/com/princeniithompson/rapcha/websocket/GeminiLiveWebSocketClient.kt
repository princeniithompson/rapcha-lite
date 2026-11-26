package com.princeniithompson.rapcha.websocket

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.ConnectionState
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.data.DictationEndedReason
import com.princeniithompson.rapcha.data.LogLevel
import com.princeniithompson.rapcha.data.TranscriptPrivacy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Structured error types for Gemini Live WebSocket communication.
 */
sealed class GeminiLiveError(open val message: String, open val cause: Throwable? = null) {
    data class MissingApiKey(override val message: String) : GeminiLiveError(message)
    data class NetworkFailure(override val message: String, override val cause: Throwable? = null) : GeminiLiveError(message, cause)
    data class ServerError(val code: Int, val status: String, override val message: String) : GeminiLiveError(message)
    data class ProtocolError(override val message: String, override val cause: Throwable? = null) : GeminiLiveError(message, cause)
    data class SetupFailed(override val message: String) : GeminiLiveError(message)
}

class GeminiLiveWebSocketClient(
    private val onSetupComplete: () -> Unit,
    private val onInterimTranscription: (String) -> Unit,
    private val onFinalizedTranscription: (String) -> Unit,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onLog: (LogLevel, String, String, String?) -> Unit,
    private val onError: (String) -> Unit,
    okHttpClient: OkHttpClient? = null,
    private val onStructuredError: ((GeminiLiveError) -> Unit)? = null,
    private val isSessionActive: () -> Boolean = { false },
    @Suppress("unused")
    private val onSessionLimit: (message: String, finalizeNow: Boolean) -> Unit = { _, _ -> },
    private val diagnosticSource: DiagnosticSource = DiagnosticSource.BUBBLE,
    private val onPreviewPhase: (InterimPreviewMonitor.Phase) -> Unit = {},
    private val onPieceCommitted: (String) -> Unit = {},
    private val onGoAway: (Long?) -> Unit = {},
    private val onAudiblePause: () -> Unit = {},
    private val isNetworkAvailable: (() -> Boolean)? = null
) {
    companion object {
        private const val TAG = "GeminiLiveWS"
        private const val SLOW_WS_HANDSHAKE_MS = 3_000L

        /**
         * The Live setup message for `gemini-3.5-transcribe-live`.
         * Per Google's official transcribe-live docs, this dedicated STT model does not use
         * conversational systemInstruction; configuration goes strictly through
         * `inputAudioTranscription` (`mode = "SMART"` and `customVocabulary` up to 1,000 terms).
         */
        fun buildSetupMessage(model: String, smartMode: Boolean, customVocabulary: List<String>): JSONObject {
            val transcriptionConfig = JSONObject().apply {
                if (smartMode) {
                    put("mode", "SMART")
                }
                if (customVocabulary.isNotEmpty()) {
                    val vocabArray = org.json.JSONArray()
                    customVocabulary
                        .asSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .take(com.princeniithompson.rapcha.data.CustomVocabularyRepository.MAX_GEMINI_ACTIVE_VOCAB_TERMS)
                        .forEach { vocabArray.put(it) }
                    if (vocabArray.length() > 0) {
                        put("customVocabulary", vocabArray)
                    }
                }
            }
            val setupJson = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", model)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", org.json.JSONArray().apply { put("TEXT") })
                    })
                    put("inputAudioTranscription", transcriptionConfig)
                    put("realtimeInputConfig", JSONObject().apply {
                        put("automaticActivityDetection", JSONObject().apply {
                            put("disabled", true)
                        })
                    })
                })
            }
            return setupJson
        }

        /**
         * v25: the old prompt told the model to ignore "background conversations", which made
         * it drop the user's own soft one- or two-word phrases when other people were near.
         * The speaker closest to the microphone is always transcribed, however quietly.
         */
        const val DICTATION_SYSTEM_PROMPT =
            "You are an ultra-accurate real-time voice dictation engine. The user is holding the phone and " +
                "speaking to it, often softly, quietly or in a near-whisper because other people are nearby. " +
                "Transcribe every word the user closest to the microphone says, even a single quiet word or a " +
                "short two-word phrase; never drop soft or short speech as background. " +
                "Only leave out speech that is clearly farther away than the user's voice, and non-speech sounds " +
                "such as music, clatter or traffic. Output only the transcribed text with proper capitalization " +
                "and punctuation. Do not reply conversationally, do not answer questions, and do not add " +
                "commentary; only output the verbatim transcription of what was said."
        const val DEFAULT_MODEL = "models/gemini-3.5-transcribe-live"
        const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val MAX_RECONNECT_ATTEMPTS = 10
        const val PING_INTERVAL_SECONDS = 45L
        private const val NETWORK_OFFLINE_RECHECK_MS = 30_000L

        /**
         * The 10 minute clock lives in [DictationSessionClock], outside this
         * socket, so piece splits and fresh connections do not restart it.
         */
        const val SESSION_LIMIT_WARNING_MS = DictationSessionClock.WARNING_MS
        const val SESSION_LIMIT_FINALIZE_MS = DictationSessionClock.SINGLE_CONNECTION_HARD_STOP_MS
        const val FINAL_TRANSCRIPT_TIMEOUT_MS = 8_000L
        /** Legacy baseline timeout kept for reference; live finalize now scales adaptively via [computeLiveFinalizeTimeoutMs]. */
        const val POST_COMMIT_FALLBACK_TIMEOUT_MS = 5_500L
        /** Fast-fail timeout when a local backup exists and Live never emitted any preview words. */
        const val STALLED_LIVE_FALLBACK_TIMEOUT_MS = 2_500L
        /** Timeout for Raw mode (no SMART rewrite pass) when a local backup is ready. */
        const val RAW_LIVE_FALLBACK_TIMEOUT_MS = 4_000L
        /** Patient rescue-split threshold so slow mobile data does not spawn extra WebSockets prematurely. */
        const val SLOW_NETWORK_RESCUE_GAP_MS = 12_000L
        const val GO_AWAY_FINALIZE_THRESHOLD_MS = 45_000L

        /**
         * Computes how long to wait for Gemini Live's `inputTranscription` after `activityEnd`
         * before falling back to batch `gemini-3.5-transcribe`:
         * - No local backup -> full 8s wait (nothing to fall back to).
         * - Zero live preview received -> fast-fail at 2.5s (likely a black-holed/stalled socket).
         * - Healthy live stream -> scale with utterance length so longer SMART polishes finish
         *   cleanly over the open WebSocket instead of timing out prematurely and re-uploading audio.
         */
        fun computeLiveFinalizeTimeoutMs(
            recordingSeconds: Int,
            smartMode: Boolean,
            hasLivePreview: Boolean,
            hasLocalBackup: Boolean
        ): Long {
            if (!hasLocalBackup) return FINAL_TRANSCRIPT_TIMEOUT_MS
            if (!hasLivePreview) return STALLED_LIVE_FALLBACK_TIMEOUT_MS
            if (!smartMode) return RAW_LIVE_FALLBACK_TIMEOUT_MS
            return when {
                recordingSeconds <= 15 -> 8_500L
                recordingSeconds <= 45 -> 10_000L
                else -> 11_500L
            }
        }

        // Single-connection enforcement and instance tracking
        private val activeSocketCount = AtomicInteger(0)
        private val currentActiveClient = AtomicReference<GeminiLiveWebSocketClient?>(null)

        fun getActiveSocketCount(): Int = activeSocketCount.get()

        internal fun releaseActiveSocket(): Int = activeSocketCount.updateAndGet { max(0, it - 1) }

        internal fun debugSetActiveSocketCount(value: Int) {
            activeSocketCount.set(value.coerceAtLeast(0))
        }

        fun terminateActiveLiveSockets() {
            val client = currentActiveClient.getAndSet(null)
            try {
                client?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error in terminateActiveLiveSockets", e)
            }
        }

        fun buildWebSocketRequest(apiKey: String): Request {
            val trimmedKey = apiKey.trim()
            if (ApiConfig.isPlaceholder(trimmedKey)) {
                throw IllegalArgumentException("Gemini API Key is missing or placeholder. Please provide a valid key in Secrets or Settings.")
            }
            return Request.Builder()
                .url(WS_BASE_URL)
                .addHeader("x-goog-api-key", trimmedKey)
                .build()
        }

        internal fun isNonRetryableApiKeyError(code: Int, statusOrMessage: String): Boolean {
            if (code == 401 || code == 403) return true
            val lower = statusOrMessage.lowercase(Locale.US)
            if (lower.contains("api key") ||
                lower.contains("apikey") ||
                lower.contains("api_key_invalid") ||
                lower.contains("invalid_api_key") ||
                lower.contains("expired") ||
                lower.contains("unauthenticated") ||
                lower.contains("permission_denied") ||
                lower.contains("permission denied")
            ) {
                return true
            }
            return false
        }
    }

    private val client: OkHttpClient = okHttpClient ?: ApiConfig.createOkHttpClient()
        .newBuilder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isSetupComplete = AtomicBoolean(false)
    private val isConnectingGuard = AtomicBoolean(false)
    private val isExplicitlyClosed = AtomicBoolean(false)
    private val hasDisconnected = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private var activeModel: String = DEFAULT_MODEL
    private var lastApiKey: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingReconnectRunnable: Runnable? = null
    @Volatile private var waitingForNetwork = false
    private val activeTurnText = StringBuilder()

    private val activityStarted = AtomicBoolean(false)
    private val activityEnded = AtomicBoolean(false)
    private val endpointSettled = AtomicBoolean(false)
    @Volatile private var lastInterimText: String = ""
    @Volatile private var resolvedEndpointText: String = ""
    private val endpointContinuation = AtomicReference<CancellableContinuation<String>?>(null)
    private var endpointTimeoutRunnable: Runnable? = null

    val setupComplete: Boolean
        get() = isSetupComplete.get()

    val isConnecting: Boolean
        get() = isConnectingGuard.get()

    val isReconnecting: Boolean
        get() = !isExplicitlyClosed.get() && reconnectAttempts.get() > 0 && !isSetupComplete.get()

    val activeSessionExists: Boolean
        get() = webSocket != null

    /** True when the Live socket is open, set up, and ready to accept activityEnd immediately. */
    val isLiveStreamHealthyForFinalize: Boolean
        get() = webSocket != null &&
            isSetupComplete.get() &&
            !isExplicitlyClosed.get() &&
            !waitingForNetwork &&
            !isReconnecting

    /** True once Gemini Live has emitted at least one interim preview or committed piece in this session. */
    val hasReceivedLivePreview: Boolean
        get() = lastInterimText.isNotBlank() || committedPieces > 0

    val isSplittingNow: Boolean
        get() = splitting.get()

    val currentSocketStateLabel: String
        get() = when {
            isExplicitlyClosed.get() -> "closed"
            waitingForNetwork -> "waiting_net"
            isReconnecting -> "reconnecting"
            webSocket == null -> "closed"
            !isSetupComplete.get() -> "connecting"
            else -> "open"
        }

    val totalReconnectAttemptsInSession: Int
        get() = sessionReconnectCount.get()

    private val sessionReconnectCount = AtomicInteger(0)
    @Volatile var wasDisconnectedDuringFinalize: Boolean = false
        private set
    @Volatile var lastPieceDroppedAsNearSilent: Boolean = false
        private set
    @Volatile var wsQueueBytesAtActivityEnd: Long = 0L
        private set
    @Volatile var wsQueueBytesAtFinalizeDone: Long = 0L
        private set
    @Volatile private var lastInterimReceivedAtMs: Long = 0L
    @Volatile var lastInterimAgeAtEndMs: Long = -1L
        private set
    private val postEndFrameTags = ArrayList<String>(8)

    val postEndFramesSummary: String
        get() = synchronized(postEndFrameTags) {
            if (postEndFrameTags.isEmpty()) "none" else postEndFrameTags.joinToString(",")
        }

    private fun recordPostEndFrameTag(tag: String) {
        synchronized(postEndFrameTags) {
            if (postEndFrameTags.size < 6) {
                postEndFrameTags.add(tag)
            }
        }
    }

    val interimPreviewMonitor = InterimPreviewMonitor()
    private val planner = DictationPiecePlanner(rescueGapMs = SLOW_NETWORK_RESCUE_GAP_MS)

    /** Tests advance this so a rescue split does not wait on the wall clock. */
    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() }

    private val holdQueue = ArrayDeque<ByteArray>()
    private val replayQueue = ArrayDeque<ByteArray>()
    private var replayBytes = 0
    @Volatile private var holdingAudio = false
    private val flushing = AtomicBoolean(false)
    private val splitting = AtomicBoolean(false)
    private val sessionFinalizing = AtomicBoolean(false)
    private var pendingSplit: DictationPiecePlanner.Split? = null
    private var pieceTimeout: Runnable? = null
    private var pieceEndAtMs = 0L
    private var pendingAfterSplit: (() -> Unit)? = null
    private var committedText: String = ""
    private var committedPieces = 0
    private var maxPieceMs = 0L
    private var socketsOpened = 0
    private var handshakeStartedMs = 0L
    private var companionHandshakeStartedMs = 0L
    private var sawInterimPast223 = false
    private var companionSocket: WebSocket? = null
    private val companionReady = AtomicBoolean(false)
    private val companionOpening = AtomicBoolean(false)
    @Volatile private var awaitingCompanion = false
    @Volatile private var resumeHeldOnSetup = false
    private var pendingReplayOnResume = false
    private var companionWait: Runnable? = null

    private var isSmartMode: Boolean = false
    private var customVocabularyList: List<String> = emptyList()
    /** v25: light adaptive gain on the transmitted copy of the audio only. */
    private val speechGain = com.princeniithompson.rapcha.audio.SpeechGain()
    @Volatile var endpointTimedOut: Boolean = false
        private set
    @Volatile var finalSettledFromServer: Boolean = false
        private set

    /**
     * Connect to Gemini Live WebSocket.
     * Guarded with AtomicBoolean to prevent race conditions and concurrent connection attempts.
     */
    fun connect(
        apiKey: String,
        model: String = DEFAULT_MODEL,
        smartMode: Boolean = false,
        customVocabulary: List<String> = emptyList()
    ) {
        val trimmedKey = apiKey.trim()
        if (ApiConfig.isPlaceholder(trimmedKey)) {
            val errMsg = "Gemini API Key is missing or placeholder. Please provide a valid key in Secrets or Settings."
            Log.e(TAG, "[ConnectionState] FAILED: $errMsg")
            onLog(LogLevel.ERROR, TAG, errMsg, null)
            notifyError(errMsg, GeminiLiveError.MissingApiKey(errMsg))
            notifyStateChanged(ConnectionState.Error(errMsg))
            throw IllegalArgumentException(errMsg)
        }

        // Concurrency guard: atomic check-and-set prevents duplicate or overlapping connections
        if (!isConnectingGuard.compareAndSet(false, true)) {
            Log.w(TAG, "Connection attempt ignored: connection is already in progress or session active")
            return
        }

        // Single-connection enforcement: Terminate any previously active client instance across the app
        val previousClient = currentActiveClient.getAndSet(this)
        if (previousClient != null && previousClient !== this) {
            Log.w(TAG, "[SingleConnection] Disconnecting prior Gemini Live client instance to enforce single live connection")
            try {
                previousClient.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error terminating previous client instance", e)
            }
        }

        // Cancel any pending auto-reconnect before initiating a new connection
        cancelPendingReconnect()

        // Ensure only one WebSocket session can exist at a time by closing any existing socket
        closeExistingWebSocket()

        val resolvedModel = model.ifBlank { DEFAULT_MODEL }.trim()

        lastApiKey = trimmedKey
        interimPreviewMonitor.start(System.currentTimeMillis())
        activeModel = resolvedModel
        isSmartMode = smartMode
        customVocabularyList = customVocabulary
        hasDisconnected.set(false)
        isExplicitlyClosed.set(false)
        reconnectAttempts.set(0)
        isSetupComplete.set(false)
        resetManualEndpointState()
        Log.i(TAG, "[ConnectionState] CONNECTING to $WS_BASE_URL ($activeModel)...")
        notifyStateChanged(ConnectionState.Connecting)

        connectInternal()
    }

    private fun connectInternal() {
        if (isExplicitlyClosed.get()) {
            isConnectingGuard.set(false)
            return
        }

        // Cancel any pending reconnect runnable and tear down any lingering socket
        cancelPendingReconnect()
        closeExistingWebSocket()

        val request = try {
            buildWebSocketRequest(lastApiKey)
        } catch (e: Exception) {
            isConnectingGuard.set(false)
            val errMsg = "Failed to build WebSocket request: ${e.message}"
            Log.e(TAG, "[ConnectionState] FAILED building request: $errMsg", e)
            notifyError(errMsg, GeminiLiveError.MissingApiKey(errMsg))
            notifyStateChanged(ConnectionState.Error(errMsg))
            return
        }

        val modeLabel = if (isSmartMode) "SMART (filler-cleanup + punctuation)" else "VERBATIM (raw fastest)"
        val vocabCount = customVocabularyList.size
        val attemptNum = reconnectAttempts.get()
        if (attemptNum == 0) {
            Log.i(TAG, "[ConnectionState] CONNECTING: Opening socket to $WS_BASE_URL ($activeModel) [mode=$modeLabel, vocab=$vocabCount, pingInterval=${PING_INTERVAL_SECONDS}s]")
            onLog(LogLevel.INFO, TAG, "Opening WebSocket connection to $WS_BASE_URL ($activeModel) in $modeLabel mode with $vocabCount custom vocabulary terms (pingInterval=${PING_INTERVAL_SECONDS}s)", null)
        } else {
            Log.i(TAG, "[ConnectionState] RECONNECTING: Attempt $attemptNum/$MAX_RECONNECT_ATTEMPTS to $WS_BASE_URL ($activeModel)...")
            onLog(LogLevel.INFO, TAG, "Reconnecting WebSocket (attempt $attemptNum/$MAX_RECONNECT_ATTEMPTS) to $WS_BASE_URL ($activeModel)...", null)
        }

        handshakeStartedMs = clock()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isExplicitlyClosed.get()) {
                    Log.d(TAG, "[ConnectionState] onOpen received but client explicitly closed, closing socket immediately")
                    webSocket.close(1000, "Closed by client")
                    isConnectingGuard.set(false)
                    return
                }

                val handshakeMs = (clock() - handshakeStartedMs).coerceAtLeast(0L)
                val netTag = ""
                val currentLiveCount = activeSocketCount.incrementAndGet()
                socketsOpened += 1
                planner.onConnectionOpened(clock())
                logDiag(DiagnosticType.SOCKET_OPEN, "connection_age_ms=0 sockets=$socketsOpened handshake_ms=$handshakeMs $netTag")
                if (handshakeMs >= SLOW_WS_HANDSHAKE_MS) {
                    logDiag(
                        DiagnosticType.NET_SLOW_WARN,
                        "stage=stage1_ws_handshake latency_ms=$handshakeMs sockets=$socketsOpened $netTag"
                    )
                }
                activeTurnText.clear()
                if (!activityEnded.get()) {
                    activityStarted.set(false)
                }
                val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                val openMsg = "[$timeStr] WebSocket open (HTTP ${response.code}). Active live sockets: $currentLiveCount"
                Log.i(TAG, "[ConnectionState] CONNECTED: $openMsg")
                onLog(LogLevel.INFO, TAG, "WebSocket connected successfully (HTTP ${response.code}). Active sockets: $currentLiveCount. Sending initial setup payload ($modeLabel, vocab=$vocabCount)...", null)
                notifyStateChanged(ConnectionState.ConnectedWaitingSetup)
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (this@GeminiLiveWebSocketClient.webSocket !== webSocket) return
                handleIncomingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                val reasonDetail = if (reason.isNotBlank()) reason else "Normal shutdown"
                Log.d(TAG, "[ConnectionState] DISCONNECTING: WebSocket closing (code: $code, reason: $reasonDetail)")
                onLog(LogLevel.INFO, TAG, "WebSocket closing (code: $code, reason: $reasonDetail)", null)
                if (!isExplicitlyClosed.get()) {
                    notifyStateChanged(ConnectionState.Stopping)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val drained = this@GeminiLiveWebSocketClient.webSocket != null &&
                    this@GeminiLiveWebSocketClient.webSocket !== webSocket
                if (drained) {
                    return
                }
                val currentLiveCount = releaseActiveSocket()
                val reasonDetail = if (reason.isNotBlank()) reason else "No reason provided"
                Log.i(TAG, "[LiveSocket] WebSocket closed (code: $code, reason: $reasonDetail). Active live sockets: $currentLiveCount")
                isSetupComplete.set(false)

                if (isExplicitlyClosed.get()) {
                    isConnectingGuard.set(false)
                    lastApiKey = ""
                    onLog(LogLevel.INFO, TAG, "WebSocket closed cleanly (code: $code, reason: $reasonDetail)", null)
                    notifyStateChanged(ConnectionState.Idle)
                } else {
                    val netTag = ""
                    logDiag(
                        DiagnosticType.SOCKET_CLOSE,
                        "connection_age_ms=${planner.connectionAge(clock())} remote=yes code=$code reason=${reasonDetail.replace(' ', '_').take(80)} $netTag"
                    )
                    handleDisconnectOrFailure("WebSocket closed (code: $code, reason: $reasonDetail)", null)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (isExplicitlyClosed.get()) {
                    isConnectingGuard.set(false)
                    return
                }
                if (this@GeminiLiveWebSocketClient.webSocket != null && this@GeminiLiveWebSocketClient.webSocket !== webSocket) {
                    return
                }
                val currentLiveCount = releaseActiveSocket()
                Log.i(TAG, "[LiveSocket] WebSocket failure: ${t.message}. Active live sockets: $currentLiveCount")

                val primaryMsg = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                val endedReason = DictationEndedReason.fromMessage(primaryMsg)
                val netTag = ""
                logDiag(
                    DiagnosticType.NET_FAILURE,
                    "stage=stage1_ws attempt=${reconnectAttempts.get()} exception=${t.javaClass.simpleName} http=${response?.code ?: 0} ended_reason=$endedReason session_active=${isSessionActive()} reason=${primaryMsg.replace('\n', ' ').take(140)} $netTag"
                )

                val httpCode = response?.code ?: 0
                val errorDetails = buildString {
                    append("ended_reason: $endedReason | WebSocket failure: ")
                    append(primaryMsg)
                    if (response != null) {
                        append(" [HTTP ${response.code}: ${response.message}]")
                        try {
                            val body = response.body?.string()
                            if (!body.isNullOrBlank()) {
                                append(" - Response: ${body.take(200)}")
                            }
                        } catch (_: Exception) {}
                    }
                    val cause = t.cause
                    if (cause != null && cause.message != t.message) {
                        append(" (Caused by: ${cause.message ?: cause.javaClass.simpleName})")
                    }
                }

                Log.w(TAG, "[ConnectionState] FAILURE detected: $errorDetails", t)
                handleDisconnectOrFailure(errorDetails, t, httpCode)
            }
        })
    }

    private fun handleDisconnectOrFailure(
        errorDetails: String,
        throwable: Throwable?,
        httpCode: Int = 0
    ) {
        if (isNonRetryableApiKeyError(httpCode, errorDetails)) {
            isExplicitlyClosed.set(true)
            isConnectingGuard.set(false)
            isSetupComplete.set(false)
            reconnectAttempts.set(MAX_RECONNECT_ATTEMPTS)
            cancelPendingReconnect()
            lastApiKey = ""
            if (activityEnded.get()) {
                settleEndpoint(lastInterimText, timedOut = true)
            }
            val authMsg = "Gemini API key invalid or unauthorized ($errorDetails)"
            Log.e(TAG, "[ConnectionState] AUTH_FAILED: $authMsg", throwable)
            onLog(LogLevel.ERROR, TAG, authMsg, null)
            AppLogRepository.logEvent(
                diagnosticSource,
                DiagnosticType.ERROR,
                authMsg.take(160)
            )
            notifyStateChanged(ConnectionState.Error(authMsg))
            notifyError(authMsg, GeminiLiveError.MissingApiKey(authMsg))
            return
        }

        if (splitting.get() && !sessionFinalizing.get()) {
            planner.armFreshMode()
            pendingSplit = pendingSplit?.copy(freshConnection = true)
            val orphan = lastInterimText
            mainHandler.post { completePieceSplit(orphan, timedOut = true) }
            return
        }
        if (!activityEnded.get() && !sessionFinalizing.get()) {
            preserveOpenPiece()
        }
        if (isExplicitlyClosed.get()) {
            isConnectingGuard.set(false)
            return
        }
        if (activityEnded.get()) {
            if (!endpointSettled.get()) {
                wasDisconnectedDuringFinalize = true
            }
            settleEndpoint(lastInterimText, timedOut = true)
            isConnectingGuard.set(false)
            return
        }

        isSetupComplete.set(false)

        if (!isSessionActive()) {
            isConnectingGuard.set(false)
            cancelPendingReconnect()
            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val idleMsg = "[$timeStr] Websocket closed while no dictation is active; reconnect suppressed | $errorDetails"
            Log.i(TAG, "[ConnectionState] IDLE: $idleMsg")
            onLog(LogLevel.INFO, TAG, idleMsg, null)
            notifyStateChanged(ConnectionState.Idle)
            return
        }

        val sessionStillActive = true
        val networkUp = isNetworkAvailable?.invoke() ?: true
        if (!networkUp && lastApiKey.isNotBlank()) {
            waitingForNetwork = true
            Log.i(TAG, "[ConnectionState] Network unavailable; deferring WebSocket reconnect until connectivity returns")
            notifyStateChanged(ConnectionState.Connecting)
            cancelPendingReconnect()
            val waitRunnable = Runnable {
                if (isExplicitlyClosed.get() || !isSessionActive()) {
                    waitingForNetwork = false
                    isConnectingGuard.set(false)
                    return@Runnable
                }
                if (isNetworkAvailable?.invoke() == true) {
                    waitingForNetwork = false
                    connectInternal()
                } else {
                    // Gently recheck in case a network callback was missed.
                    pendingReconnectRunnable?.let { mainHandler.postDelayed(it, NETWORK_OFFLINE_RECHECK_MS) }
                }
            }
            pendingReconnectRunnable = waitRunnable
            mainHandler.postDelayed(waitRunnable, NETWORK_OFFLINE_RECHECK_MS)
            return
        }

        waitingForNetwork = false
        val attempts = reconnectAttempts.incrementAndGet()
        sessionReconnectCount.incrementAndGet()

        if (lastApiKey.isNotBlank() && attempts <= MAX_RECONNECT_ATTEMPTS) {
            // True exponential backoff with jitter:
            // delay = min(30000, base * 2^(attempt-1)) + random(0..500 ms)
            // base starts at 1000 ms.
            val attemptExponent = (attempts - 1).coerceIn(0, 15)
            val rawBackoff = (1000L * (1L shl attemptExponent)).coerceAtMost(30000L)
            val jitter = Random.nextLong(0, 501)
            val backoffMs = (rawBackoff + jitter).coerceAtMost(30500L)

            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val reconnectLog = "[$timeStr][Reconnect] Attempt $attempts/$MAX_RECONNECT_ATTEMPTS (delay: ${backoffMs}ms, base: ${rawBackoff}ms, jitter: ${jitter}ms) | sessionActive: $sessionStillActive | activeSockets: ${activeSocketCount.get()} | reason: $errorDetails"
            Log.i(TAG, "[ConnectionState] RECONNECTING: $reconnectLog")
            onLog(LogLevel.INFO, TAG, reconnectLog, null)
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.SESSION_RECONNECT,
                reconnectLog
            )
            notifyStateChanged(ConnectionState.Connecting)

            cancelPendingReconnect()
            val runnable = Runnable {
                if (!isExplicitlyClosed.get() && isSessionActive()) {
                    connectInternal()
                } else {
                    isConnectingGuard.set(false)
                }
            }
            pendingReconnectRunnable = runnable
            mainHandler.postDelayed(runnable, backoffMs)
            return
        }

        // Retries exhausted or non-retryable fatal error
        isConnectingGuard.set(false)
        lastApiKey = ""
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val fatalMsg = "[$timeStr][Reconnect] Failed permanently after $attempts attempts: $errorDetails"
        Log.e(TAG, "[ConnectionState] FAILED: $fatalMsg", throwable)
        onLog(LogLevel.ERROR, TAG, fatalMsg, null)
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.ERROR,
            "WebSocket failed permanently after $attempts reconnect attempts: ${errorDetails.take(120)}"
        )
        notifyStateChanged(ConnectionState.Error(fatalMsg))
        notifyError(fatalMsg, GeminiLiveError.NetworkFailure(fatalMsg, throwable))
    }

    private fun handleIncomingMessage(rawJson: String) {
        try {
            val json = JSONObject(rawJson)

            // Step B: Check for setupComplete
            if (json.has("setupComplete")) {
                isSetupComplete.set(true)
                val wasReconnecting = reconnectAttempts.get() > 0
                val attemptsUsed = reconnectAttempts.get()
                reconnectAttempts.set(0)
                Log.i(TAG, "[ConnectionState] STREAMING: setupComplete acknowledged by Gemini Live server!")
                val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                val ackMsg = if (wasReconnecting) {
                    "[$timeStr][Reconnect] Successfully recovered and reconnected after $attemptsUsed attempts! Active sockets: ${activeSocketCount.get()}"
                } else {
                    "setupComplete acknowledged by Gemini Live server!"
                }
                onLog(LogLevel.RECEIVED, TAG, ackMsg, rawJson)
                if (wasReconnecting) {
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.SESSION_RECONNECT,
                        ackMsg
                    )
                }
                notifyStateChanged(ConnectionState.Streaming)
                if (resumeHeldOnSetup) {
                    resumeHeldOnSetup = false
                    val replay = pendingReplayOnResume
                    planner.onPieceStarted(clock())
                    activityStarted.set(false)
                    activityEnded.set(false)
                    endpointSettled.set(false)
                    signalActivityStart()
                    flushHeld(replay)
                    finishSplitHold()
                } else {
                    planner.onPieceStarted(clock())
                }
                notifySetupComplete()
                return
            }

            if (activityEnded.get()) {
                val frameTag = when {
                    json.has("error") -> "error"
                    json.has("goAway") -> "goAway"
                    json.has("serverContent") -> {
                        val sc = json.optJSONObject("serverContent")
                        val hasInputTrx = sc?.optJSONObject("inputTranscription")?.optString("text")?.isNotEmpty() == true
                        val hasTurnDone = sc?.optBoolean("turnComplete", false) == true
                        val hasInterrupted = sc?.optBoolean("interrupted", false) == true
                        val hasGenDone = sc?.optBoolean("generationComplete", false) == true
                        val hasInterim = sc?.optJSONObject("interimInputTranscription")?.optString("text")?.isNotEmpty() == true
                        val hasModelTurn = sc?.optJSONObject("modelTurn") != null
                        when {
                            hasInputTrx && hasTurnDone -> "inputTrx+turnDone"
                            hasInputTrx -> "inputTrx"
                            hasTurnDone -> "turnDone"
                            hasInterrupted -> "interrupted"
                            hasGenDone -> "genDone"
                            hasInterim -> "interim"
                            hasModelTurn -> "modelTurn"
                            else -> "scEmpty"
                        }
                    }
                    else -> "other"
                }
                recordPostEndFrameTag(frameTag)
            }

            val goAway = json.optJSONObject("goAway")
            if (goAway != null) {
                handleGoAway(goAway)
            }

            // Check for server errors
            if (json.has("error")) {
                val errorObj = json.optJSONObject("error")
                val code = errorObj?.optInt("code", 0) ?: 0
                val status = errorObj?.optString("status") ?: ""
                val errMsg = errorObj?.optString("message") ?: json.optString("error")
                val detailedError = buildString {
                    append("Gemini Live server error")
                    if (code != 0) append(" (code $code)")
                    if (status.isNotBlank()) append(" [$status]")
                    append(": $errMsg")
                }
                Log.e(TAG, "[ConnectionState] SERVER_ERROR: $detailedError")
                onLog(LogLevel.ERROR, TAG, detailedError, rawJson)
                val isKeyError = isNonRetryableApiKeyError(code, "$status $errMsg")
                if (isKeyError) {
                    isExplicitlyClosed.set(true)
                    isConnectingGuard.set(false)
                    isSetupComplete.set(false)
                    reconnectAttempts.set(MAX_RECONNECT_ATTEMPTS)
                    cancelPendingReconnect()
                    lastApiKey = ""
                    closeExistingWebSocket()
                    notifyStateChanged(ConnectionState.Error(detailedError))
                    notifyError(detailedError, GeminiLiveError.MissingApiKey(detailedError))
                } else {
                    notifyStateChanged(ConnectionState.Error(detailedError))
                    notifyError(detailedError, GeminiLiveError.ServerError(code, status, detailedError))
                }
                return
            }

            // Step C & frame text extraction
            val serverContent = json.optJSONObject("serverContent")
            if (serverContent != null) {
                // 1. Model text response parts (from live model turn)
                val modelTurn = serverContent.optJSONObject("modelTurn")
                val parts = modelTurn?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i)
                        val text = part?.optString("text")
                        if (!text.isNullOrEmpty()) {
                            activeTurnText.append(text)
                        }
                    }
                    val currentTurn = activeTurnText.toString()
                    if (currentTurn.isNotEmpty()) {
                        onLog(LogLevel.RECEIVED, TAG, "Live Text: ${TranscriptPrivacy.summary(currentTurn)}", null)
                        notifyInterimTranscription(currentTurn)
                    }
                }

                // Interim hypotheses stay in the live preview for the whole activity.
                // inputTranscription is authoritative only after the user presses Complete
                // (activityEnd). Silence must not commit or split the utterance.
                val interimObj = serverContent.optJSONObject("interimInputTranscription")
                val interimText = interimObj?.optString("text").orEmpty()
                if (interimText.isNotEmpty()) {
                    onLog(LogLevel.RECEIVED, TAG, "Interim: ${TranscriptPrivacy.summary(interimText)}", null)
                    noteLiveTranscription(interimText)
                }

                val finalizedObj = serverContent.optJSONObject("inputTranscription")
                val finalizedText = finalizedObj?.optString("text").orEmpty()
                val turnFinished = serverContent.optBoolean("turnComplete", false) ||
                    serverContent.optBoolean("interrupted", false)

                if (!activityEnded.get()) {
                    if (finalizedText.isNotEmpty()) {
                        onLog(LogLevel.RECEIVED, TAG, "Preview update before activityEnd: ${TranscriptPrivacy.summary(finalizedText)}", null)
                        noteLiveTranscription(finalizedText)
                    }
                    if (turnFinished) {
                        activeTurnText.clear()
                    }
                } else if (finalizedText.isNotEmpty() || turnFinished) {
                    val completedText = if (finalizedText.isNotEmpty()) {
                        finalizedText
                    } else {
                        activeTurnText.toString().trim()
                    }
                    activeTurnText.clear()
                    if (completedText.isNotEmpty()) {
                        onLog(LogLevel.RECEIVED, TAG, "Finalized: ${TranscriptPrivacy.summary(completedText)}", null)
                    }
                    if (sessionFinalizing.get()) {
                        settleEndpoint(completedText.ifBlank { lastInterimText }, timedOut = false)
                    } else if (splitting.get()) {
                        completePieceSplit(completedText.ifBlank { lastInterimText }, timedOut = false)
                    }
                }
            } else {
                onLog(
                    LogLevel.RECEIVED,
                    TAG,
                    "Server message: ${TranscriptPrivacy.maskSensitive(rawJson.take(120))}...",
                    null
                )
            }
        } catch (e: Exception) {
            val err = "Error parsing incoming frame JSON: ${e.message}"
            Log.e(TAG, err, e)
            onLog(LogLevel.ERROR, TAG, "JSON parse error: ${e.message}", TranscriptPrivacy.maskSensitive(rawJson).take(180))
            notifyError(err, GeminiLiveError.ProtocolError(err, e))
        }
    }

    /**
     * Sends one PCM chunk on the documented realtimeInput.audio field.
     * Past 2:30 of piece audio the chunk may be held locally while this piece
     * is finalized, then flushed into the next activity.
     */
    fun sendAudioChunk(pcmChunk: ByteArray): Boolean {
        if (flushing.get()) {
            return sendPcm(webSocket, pcmChunk, countReplay = false)
        }
        if (holdingAudio || splitting.get()) {
            hold(pcmChunk)
            return true
        }
        val socketUsable = webSocket != null && isSetupComplete.get() && !isExplicitlyClosed.get() && !activityEnded.get()
        if (!socketUsable) {
            if (!activityEnded.get() && isSetupComplete.get()) {
                interimPreviewMonitor.onPcm(pcmChunk, accepted = false, nowMs = clock())
                publishInterimPreview()
            }
            return false
        }
        val now = clock()
        val decision = planner.onAudio(pcmChunk, now)
        if (planner.consumePreconnect()) {
            openCompanionSocket()
        }
        logAge(planner.ageMarker(now))
        if (planner.consumeAudiblePause()) {
            dispatch { onAudiblePause() }
        }
        if (decision != null && !sessionFinalizing.get()) {
            hold(pcmChunk)
            beginPieceSplit(decision)
            return true
        }
        if (!signalActivityStart()) {
            return false
        }
        return sendPcm(webSocket, pcmChunk, countReplay = true)
    }

    /**
     * Send a 100ms zero-PCM padding chunk during audio recorder session restarts
     * to keep WebSocket VAD state continuously alive without dropouts.
     */
    fun sendZeroPaddingChunk(): Boolean {
        val zeroBuffer = ByteArray(3200) // 100ms at 16kHz mono 16-bit PCM = 3200 bytes
        return sendAudioChunk(zeroBuffer)
    }

    /**
     * Opens the manual activity. Safe to call more than once; audio chunks also call this
     * so the first frame is never sent outside an activity.
     */
    fun signalActivityStart(): Boolean {
        if (activityEnded.get() || isExplicitlyClosed.get() || !isSetupComplete.get()) return false
        if (activityStarted.get()) return true
        val ws = webSocket ?: return false
        return try {
            val startJson = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("activityStart", JSONObject())
                })
            }
            val sent = ws.send(startJson.toString())
            if (sent) {
                activityStarted.set(true)
                onLog(LogLevel.SENT, TAG, "Sent activityStart", startJson.toString())
            }
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Error sending activityStart", e)
            false
        }
    }

    /**
     * Ends the current manual activity. Piece splits and the final Complete
     * both use this. audioStreamEnd stays suppressed.
     */
    fun signalActivityEnd(): Boolean = signalActivityEnd(recordMonitor = true)

    private fun signalActivityEnd(recordMonitor: Boolean): Boolean {
        val firstEnd = activityEnded.compareAndSet(false, true)
        val ws = webSocket
        if (!firstEnd) {
            return ws != null && isSetupComplete.get() && !isExplicitlyClosed.get()
        }
        if (ws == null || !isSetupComplete.get() || isExplicitlyClosed.get()) return false
        return try {
            val endJson = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("activityEnd", JSONObject())
                })
            }
            val sent = ws.send(endJson.toString())
            if (sent) {
                if (recordMonitor) {
                    val now = clock()
                    wsQueueBytesAtActivityEnd = runCatching { ws.queueSize() }.getOrDefault(0L)
                    lastInterimAgeAtEndMs = if (lastInterimReceivedAtMs > 0L) {
                        (now - lastInterimReceivedAtMs).coerceAtLeast(0L)
                    } else {
                        -1L
                    }
                    interimPreviewMonitor.onActivityEnd(now)
                }
                onLog(LogLevel.SENT, TAG, "Sent activityEnd", endJson.toString())
            }
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Error sending activityEnd", e)
            false
        }
    }

    /**
     * Sends activityEnd and suspends until the authoritative transcript arrives.
     * If the server does not answer in time, the last interim preview is returned.
     */
    suspend fun endActivityAndAwaitFinal(timeoutMs: Long = FINAL_TRANSCRIPT_TIMEOUT_MS): String {
        if (splitting.get()) {
            suspendCancellableCoroutine<Unit> { cont ->
                pendingAfterSplit = {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
        if (sessionFinalizing.get() && endpointSettled.get()) {
            return committedText.ifBlank { resolvedEndpointText.ifBlank { lastInterimText.trim() } }
        }
        sessionFinalizing.set(true)
        return suspendCancellableCoroutine { cont ->
            endpointContinuation.set(cont)
            cont.invokeOnCancellation {
                endpointContinuation.compareAndSet(cont, null)
            }
            val audio = planner.pieceAudio()
            if (TranscriptAcceptance.isNearSilent(audio.rmsMean, audio.rmsMax, audio.samples)) {
                lastPieceDroppedAsNearSilent = true
                settleEndpoint("", timedOut = false)
                return@suspendCancellableCoroutine
            }
            val sent = signalActivityEnd(recordMonitor = true)
            if (!sent) {
                settleEndpoint(lastInterimText, timedOut = true)
                return@suspendCancellableCoroutine
            }
            val timeout = Runnable { settleEndpoint(lastInterimText, timedOut = true) }
            endpointTimeoutRunnable = timeout
            mainHandler.postDelayed(timeout, timeoutMs.coerceAtLeast(1_000L))
        }
    }

    /**
     * Kept so older stop paths cannot emit audioStreamEnd. Manual VAD must not
     * send that signal; it would let the server close the turn on its own.
     */
    fun signalStreamEnd() {
        Log.i(TAG, "audioStreamEnd suppressed; activityEnd is sent only when the user completes")
    }

    fun notifyNetworkAvailable() {
        if (!waitingForNetwork || isExplicitlyClosed.get() || !isSessionActive()) return
        waitingForNetwork = false
        cancelPendingReconnect()
        mainHandler.post {
            if (!isExplicitlyClosed.get() && isSessionActive() && !isSetupComplete.get()) {
                Log.i(TAG, "[ConnectionState] Connectivity restored; resuming WebSocket reconnect")
                connectInternal()
            }
        }
    }

    fun disconnect() {
        if (!hasDisconnected.compareAndSet(false, true)) {
            Log.d(TAG, "[ConnectionState] disconnect() already invoked; skipping duplicate call")
            return
        }
        Log.i(TAG, "[ConnectionState] DISCONNECT requested by client (deterministic teardown)")
        isExplicitlyClosed.set(true)
        waitingForNetwork = false
        reconnectAttempts.set(MAX_RECONNECT_ATTEMPTS)
        cancelPendingReconnect()
        pieceTimeout?.let { mainHandler.removeCallbacks(it) }
        pieceTimeout = null
        cancelCompanionWait()
        if (splitting.get()) {
            val audio = planner.pieceAudio()
            val rawOrphan = lastInterimText.trim()
            val orphan = TranscriptAcceptance.accept(
                rawOrphan,
                audio.rmsMean,
                audio.rmsMax,
                com.princeniithompson.rapcha.data.CustomVocabularyRepository.getVocabulary(),
                audio.samples
            )
            noteSilenceDrop(rawOrphan, orphan, audio.rmsMean, audio.rmsMax)
            if (orphan.isNotEmpty() && endpointSettled.compareAndSet(false, true)) {
                commitPiece(orphan, dedup = false)
            }
            splitting.set(false)
            holdingAudio = false
            awaitingCompanion = false
        }
        if (activityEnded.get()) {
            settleEndpoint(lastInterimText, timedOut = true)
        }
        closeExistingWebSocket()
        synchronized(holdQueue) {
            holdQueue.clear()
        }
        replayQueue.clear()
        replayBytes = 0

        interimPreviewMonitor.stop()
        currentActiveClient.compareAndSet(this, null)

        isSetupComplete.set(false)
        isConnectingGuard.set(false)
        lastApiKey = ""
        notifyStateChanged(ConnectionState.Idle)
    }

    private fun cancelPendingReconnect() {
        pendingReconnectRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        pendingReconnectRunnable = null
    }

    private fun closeExistingWebSocket() {
        val age = planner.connectionAge(clock())
        val existing = webSocket
        webSocket = null
        closeQuietly(existing, age)
        val companion = companionSocket
        companionSocket = null
        companionReady.set(false)
        companionOpening.set(false)
        closeQuietly(companion, 0L)
    }

    private fun notifyError(message: String, structuredError: GeminiLiveError? = null) {
        val structured = structuredError ?: GeminiLiveError.NetworkFailure(message)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onError(message)
            onStructuredError?.invoke(structured)
        } else {
            mainHandler.post {
                onError(message)
                onStructuredError?.invoke(structured)
            }
        }
    }

    private fun notifyStateChanged(state: ConnectionState) {
        val stateName = when (state) {
            is ConnectionState.Idle -> "IDLE"
            is ConnectionState.Connecting -> if (reconnectAttempts.get() > 0) "RECONNECTING" else "CONNECTING"
            is ConnectionState.ConnectedWaitingSetup -> "CONNECTED_WAITING_SETUP"
            is ConnectionState.Streaming -> "STREAMING"
            is ConnectionState.Stopping -> "STOPPING"
            is ConnectionState.Error -> "FAILED"
        }
        Log.i(TAG, "[ConnectionState] State updated: $stateName")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onStateChanged(state)
        } else {
            mainHandler.post { onStateChanged(state) }
        }
    }

    private fun notifySetupComplete() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onSetupComplete()
        } else {
            mainHandler.post { onSetupComplete() }
        }
    }

    fun pollInterimPreview() {
        publishInterimPreview()
    }

    private fun noteLiveTranscription(text: String) {
        val audio = planner.pieceAudio()
        if (TranscriptAcceptance.isNearSilent(audio.rmsMean, audio.rmsMax, audio.samples)) {
            return
        }
        val now = clock()
        planner.onInterim(now)
        logAge(planner.ageMarker(now))
        val resumed = interimPreviewMonitor.onInterim(now)
        if (resumed != null) {
            logMonitorEvent(resumed)
        }
        publishInterimPreview()
        notifyInterimTranscription(text)
    }

    private fun publishInterimPreview() {
        val tick = interimPreviewMonitor.poll(System.currentTimeMillis(), socketStateLabel())
        tick.events.forEach { logMonitorEvent(it) }
        if (tick.phaseChanged) {
            val phase = tick.phase
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onPreviewPhase(phase)
            } else {
                mainHandler.post { onPreviewPhase(phase) }
            }
        }
    }

    private fun socketStateLabel(): String {
        if (isExplicitlyClosed.get() || webSocket == null) return "closed"
        if (!isSetupComplete.get()) return "connecting"
        return "open"
    }

    private fun logMonitorEvent(event: InterimPreviewMonitor.Event) {
        val netTag = ""
        val (type, message) = when (event) {
            is InterimPreviewMonitor.Event.Stall -> DiagnosticType.INTERIM_STALL to
                "no interimInputTranscription or inputTranscription for ${event.gapMs}ms while speech audio was streaming $netTag"
            is InterimPreviewMonitor.Event.Resumed -> DiagnosticType.INTERIM_RESUMED to
                "live transcription resumed after ${event.gapMs}ms $netTag"
            is InterimPreviewMonitor.Event.Heartbeat -> DiagnosticType.SESSION_HEARTBEAT to
                "interim_count=${event.interimCount} last_interim_age_ms=${event.lastInterimAgeMs} bytes_sent=${event.bytesSent} socket=${event.socketState} $netTag"
        }
        AppLogRepository.logEvent(diagnosticSource, type, message)
    }

    private fun notifyInterimTranscription(text: String) {
        if (text.isNotEmpty()) {
            lastInterimText = text
            lastInterimReceivedAtMs = clock()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onInterimTranscription(text)
        } else {
            mainHandler.post { onInterimTranscription(text) }
        }
    }

    private fun resetManualEndpointState() {
        activityStarted.set(false)
        activityEnded.set(false)
        endpointSettled.set(false)
        lastInterimText = ""
        resolvedEndpointText = ""
        committedText = ""
        committedPieces = 0
        maxPieceMs = 0L
        socketsOpened = 0
        sawInterimPast223 = false
        holdingAudio = false
        splitting.set(false)
        sessionFinalizing.set(false)
        awaitingCompanion = false
        pendingSplit = null
        pendingAfterSplit = null
        holdQueue.clear()
        replayQueue.clear()
        replayBytes = 0
        planner.resetSession()
        endpointContinuation.set(null)
        endpointTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        endpointTimeoutRunnable = null
        pieceTimeout?.let { mainHandler.removeCallbacks(it) }
        pieceTimeout = null
        cancelCompanionWait()
        endpointTimedOut = false
        finalSettledFromServer = false
        sessionReconnectCount.set(0)
        wasDisconnectedDuringFinalize = false
        lastPieceDroppedAsNearSilent = false
        wsQueueBytesAtActivityEnd = 0L
        wsQueueBytesAtFinalizeDone = 0L
        lastInterimReceivedAtMs = 0L
        lastInterimAgeAtEndMs = -1L
        synchronized(postEndFrameTags) {
            postEndFrameTags.clear()
        }
    }

    private fun handleGoAway(goAway: JSONObject) {
        val timeLeftMs = parseDurationMillis(goAway.opt("timeLeft"))
        onLog(LogLevel.INFO, TAG, "goAway timeLeftMs=$timeLeftMs", goAway.toString())
        dispatch { onGoAway(timeLeftMs) }
    }

    private fun settleEndpoint(text: String, timedOut: Boolean) {
        if (!activityEnded.get() && !sessionFinalizing.get()) return
        if (!endpointSettled.compareAndSet(false, true)) return
        endpointTimedOut = timedOut
        finalSettledFromServer = !timedOut && text.isNotBlank()
        wsQueueBytesAtFinalizeDone = runCatching { webSocket?.queueSize() ?: 0L }.getOrDefault(0L)
        interimPreviewMonitor.onFinalTranscript(clock())
        endpointTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        endpointTimeoutRunnable = null
        val audio = planner.pieceAudio()
        val raw = text.trim().ifBlank { lastInterimText.trim() }
        val piece = TranscriptAcceptance.accept(
            raw,
            audio.rmsMean,
            audio.rmsMax,
            com.princeniithompson.rapcha.data.CustomVocabularyRepository.getVocabulary(),
            audio.samples
        )
        if (raw.isNotBlank() && piece.isEmpty()) {
            lastPieceDroppedAsNearSilent = true
        }
        noteSilenceDrop(raw, piece, audio.rmsMean, audio.rmsMax)
        val resultText = if (!timedOut) {
            if (piece.isNotEmpty()) {
                committedText = TranscriptSeam.append(committedText, piece)
                committedPieces += 1
                lastInterimText = ""
                notifyFinalizedTranscription(piece)
            }
            committedText
        } else {
            // Do NOT emit onFinalizedTranscription on a timeout or disconnect: this text is
            // unfinalized raw interim, not a server-committed piece. Returning it via the
            // continuation keeps it available as an emergency fallback without polluting
            // finalizedTranscript before batch fallback runs.
            TranscriptSeam.append(committedText, piece)
        }
        resolvedEndpointText = resultText
        if (timedOut && piece.isEmpty() && committedText.isEmpty()) {
            Log.i(TAG, "Session final timed out with no transcript")
        }
        val cont = endpointContinuation.getAndSet(null)
        if (cont != null && cont.isActive) {
            cont.resume(resultText)
        }
    }

    private fun parseDurationMillis(value: Any?): Long? {
        if (value == null || value == JSONObject.NULL) return null
        return when (value) {
            is Number -> (value.toDouble() * 1000.0).toLong()
            is String -> parseDurationString(value)
            is JSONObject -> {
                if (!value.has("seconds") && !value.has("nanos")) return null
                val seconds = value.optDouble("seconds", 0.0)
                val nanos = value.optDouble("nanos", 0.0)
                (seconds * 1000.0 + nanos / 1_000_000.0).toLong()
            }
            else -> null
        }
    }

    private fun parseDurationString(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.endsWith("ms") -> trimmed.removeSuffix("ms").trim().toDoubleOrNull()?.toLong()
            trimmed.endsWith("s") -> trimmed.removeSuffix("s").trim().toDoubleOrNull()?.times(1000.0)?.toLong()
            trimmed.endsWith("m") -> trimmed.removeSuffix("m").trim().toDoubleOrNull()?.times(60_000.0)?.toLong()
            else -> trimmed.toDoubleOrNull()?.times(1000.0)?.toLong()
        }
    }

    fun isSingleConnection(): Boolean = socketsOpened <= 1

    fun currentPauseMs(): Long = planner.quietMs

    fun fullTranscript(): String {
        val committed = committedText.trim()
        val live = lastInterimText.trim()
        if (live.isEmpty()) return committed
        if (committed.isEmpty() || committed == live || committed.endsWith(" $live")) return committed.ifBlank { live }
        return TranscriptSeam.append(committed, live)
    }

    fun sessionSummary(): String {
        val past = if (sawInterimPast223 || planner.interimPastDeathAge()) "yes" else "no"
        val fresh = if (planner.freshConnectionMode) "yes" else "no"
        return "pieces=$committedPieces max_piece_s=${maxPieceMs / 1000} interim_past_223s=$past sockets=$socketsOpened fresh_mode=$fresh | ${interimPreviewMonitor.summary()}"
    }

    fun requestFreshConnectionSplit() {
        if (isExplicitlyClosed.get() || sessionFinalizing.get()) return
        planner.armFreshMode()
        if (splitting.get()) return
        beginPieceSplit(planner.forceFreshSplit(clock()))
    }

    private fun sendSetup(socket: WebSocket) {
        val setupJson = buildSetupMessage(activeModel, isSmartMode, customVocabularyList)
        val payload = setupJson.toString()
        val sent = socket.send(payload)
        if (sent) {
            val vadDiagnostics = "[Manual VAD] automaticActivityDetection.disabled=true; activityEnd closes one piece, not a pause"
            Log.i(TAG, vadDiagnostics)
            onLog(LogLevel.SENT, TAG, "Setup message sent to server ($vadDiagnostics)", payload)
        } else {
            val err = "Failed to send setup message over socket"
            Log.e(TAG, "[ConnectionState] FAILED: $err")
            onLog(LogLevel.ERROR, TAG, err, payload)
            notifyStateChanged(ConnectionState.Error(err))
            notifyError(err, GeminiLiveError.SetupFailed(err))
        }
    }

    private fun sendPcm(ws: WebSocket?, pcm: ByteArray, countReplay: Boolean): Boolean {
        if (ws == null) return false
        return try {
            // Gain is applied to the transmitted copy only; the planner, replay buffer and
            // near-silence gate keep the raw level. Replayed audio does not retrain the gain.
            val out = speechGain.process(pcm, adapt = countReplay)
            val base64Data = Base64.encodeToString(out, Base64.NO_WRAP)
            val chunkJson = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Data)
                    })
                })
            }
            val sent = ws.send(chunkJson.toString())
            if (sent && countReplay && ws === webSocket) {
                rememberReplay(pcm)
            }
            interimPreviewMonitor.onPcm(pcm, accepted = sent, nowMs = clock())
            publishInterimPreview()
            sent
        } catch (e: Exception) {
            Log.w(TAG, "Error sending audio chunk (socket disconnected or buffer full): ${e.message}")
            interimPreviewMonitor.onPcm(pcm, accepted = false, nowMs = clock())
            publishInterimPreview()
            false
        }
    }

    private fun hold(pcm: ByteArray) {
        synchronized(holdQueue) {
            holdQueue.addLast(pcm.copyOf())
        }
    }

    private fun rememberReplay(pcm: ByteArray) {
        val copy = pcm.copyOf()
        replayQueue.addLast(copy)
        replayBytes += copy.size
        while (replayBytes > DictationPiecePlanner.REPLAY_BYTES && replayQueue.isNotEmpty()) {
            replayBytes -= replayQueue.removeFirst().size
        }
    }

    private fun beginPieceSplit(split: DictationPiecePlanner.Split) {
        if (sessionFinalizing.get()) return
        if (!splitting.compareAndSet(false, true)) return
        holdingAudio = true
        pendingSplit = split
        if (split.pieceAudioMs > maxPieceMs) maxPieceMs = split.pieceAudioMs
        logDiag(
            DiagnosticType.SPLIT,
            "piece=${split.pieceNumber} reason=${split.reason.name.lowercase()} piece_s=${split.pieceAudioMs / 1000} connection_age_s=${split.connectionAgeMs / 1000} pause_ms=${split.pauseLengthMs} rms_min=${fmt(split.rmsMin)} rms_max=${fmt(split.rmsMax)} rms_mean=${fmt(split.rmsMean)}"
        )
        pieceEndAtMs = clock()
        val nearSilent = TranscriptAcceptance.isNearSilent(split.rmsMean, split.rmsMax)
        if (nearSilent) {
            completePieceSplit("", timedOut = false)
            return
        }
        val sent = signalActivityEnd(recordMonitor = false)
        if (!sent) {
            completePieceSplit(lastInterimText, timedOut = true)
            return
        }
        val timeout = Runnable { completePieceSplit(lastInterimText, timedOut = true) }
        pieceTimeout = timeout
        mainHandler.postDelayed(timeout, DictationPiecePlanner.PIECE_FINAL_TIMEOUT_MS)
    }

    private fun completePieceSplit(text: String, timedOut: Boolean) {
        if (!splitting.get()) return
        if (!endpointSettled.compareAndSet(false, true)) return
        pieceTimeout?.let { mainHandler.removeCallbacks(it) }
        pieceTimeout = null
        val split = pendingSplit
        val audioMean = split?.rmsMean ?: planner.pieceAudio().rmsMean
        val audioMax = split?.rmsMax ?: planner.pieceAudio().rmsMax
        val raw = text.trim().ifBlank { lastInterimText.trim() }
        val accepted = TranscriptAcceptance.accept(
            raw,
            audioMean,
            audioMax,
            com.princeniithompson.rapcha.data.CustomVocabularyRepository.getVocabulary(),
            samples = if (split == null) planner.pieceAudio().samples else 1
        )
        noteSilenceDrop(raw, accepted, audioMean, audioMax)
        val overlap = if (split?.replay == true) {
            TranscriptSeam.dropOverlap(committedText, accepted)
        } else {
            TranscriptSeam.Overlap(accepted, 0)
        }
        if (overlap.wordsRemoved > 0) {
            logDiag(DiagnosticType.DEDUP, "words_removed=${overlap.wordsRemoved}")
        }
        val piece = overlap.text.trim()
        val latency = (clock() - pieceEndAtMs).coerceAtLeast(0L)
        if (piece.isNotEmpty()) {
            commitPiece(piece, dedup = false)
        }
        logDiag(
            DiagnosticType.PIECE_FINAL,
            "piece=${split?.pieceNumber ?: committedPieces} latency_ms=$latency words=${TranscriptPrivacy.wordCount(piece)} timed_out=${if (timedOut) "yes" else "no"}"
        )
        lastInterimText = ""
        val nearSilentPiece = TranscriptAcceptance.isNearSilent(audioMean, audioMax)
        resetPieceFlags(keepActivity = nearSilentPiece)
        planner.advancePieceNumber()
        planner.onPieceStarted(clock())
        if (split?.freshConnection == true) {
            handoffToFreshSocket(split.replay)
        } else {
            if (!nearSilentPiece) {
                signalActivityStart()
            }
            flushHeld(split?.replay == true)
            finishSplitHold()
        }
    }

    private fun commitPiece(piece: String, dedup: Boolean) {
        val resolved = if (dedup) TranscriptSeam.dropOverlap(committedText, piece) else TranscriptSeam.Overlap(piece.trim(), 0)
        if (resolved.wordsRemoved > 0) {
            logDiag(DiagnosticType.DEDUP, "words_removed=${resolved.wordsRemoved}")
        }
        val text = resolved.text.trim()
        if (text.isEmpty()) return
        committedText = TranscriptSeam.append(committedText, text)
        committedPieces += 1
        dispatch { onPieceCommitted(text) }
    }

    private fun preserveOpenPiece() {
        val audio = planner.pieceAudio()
        val rawOrphan = lastInterimText.trim()
        val orphan = TranscriptAcceptance.accept(
            rawOrphan,
            audio.rmsMean,
            audio.rmsMax,
            com.princeniithompson.rapcha.data.CustomVocabularyRepository.getVocabulary(),
            audio.samples
        )
        noteSilenceDrop(rawOrphan, orphan, audio.rmsMean, audio.rmsMax)
        if (orphan.isEmpty() || endpointSettled.get()) return
        commitPiece(orphan, dedup = false)
        lastInterimText = ""
        logDiag(
            DiagnosticType.PIECE_FINAL,
            "piece=${committedPieces} latency_ms=0 words=${TranscriptPrivacy.wordCount(orphan)} timed_out=yes"
        )
    }

    private fun handoffToFreshSocket(replay: Boolean) {
        if (!companionReady.get()) {
            awaitingCompanion = true
            openCompanionSocket()
            scheduleCompanionWait(replay)
            return
        }
        activateCompanion(replay)
    }

    private fun scheduleCompanionWait(replay: Boolean) {
        cancelCompanionWait()
        val timeout = Runnable {
            if (!splitting.get() || !awaitingCompanion) return@Runnable
            if (companionReady.get()) {
                activateCompanion(replay)
            } else {
                Log.w(TAG, "Next live socket was not ready; opening a replacement connection")
                fallbackReconnectForSplit(replay)
            }
        }
        companionWait = timeout
        mainHandler.postDelayed(timeout, 8_000L)
    }

    private fun cancelCompanionWait() {
        companionWait?.let { mainHandler.removeCallbacks(it) }
        companionWait = null
    }

    /**
     * The preconnected socket never became ready. Open a normal replacement
     * without clearing the pieces already committed.
     */
    private fun fallbackReconnectForSplit(replay: Boolean) {
        if (resumeHeldOnSetup || !splitting.get() || sessionFinalizing.get() || isExplicitlyClosed.get()) return
        cancelCompanionWait()
        awaitingCompanion = false
        resumeHeldOnSetup = true
        pendingReplayOnResume = replay
        isSetupComplete.set(false)
        connectInternal()
    }

    private fun activateCompanion(replay: Boolean) {
        if (!splitting.get() || resumeHeldOnSetup) return
        val next = companionSocket
        if (next == null || !companionReady.get()) {
            awaitingCompanion = true
            openCompanionSocket()
            scheduleCompanionWait(replay)
            return
        }
        cancelCompanionWait()
        awaitingCompanion = false
        val old = webSocket
        val age = planner.connectionAge(clock())
        webSocket = next
        companionSocket = null
        companionReady.set(false)
        companionOpening.set(false)
        isSetupComplete.set(true)
        planner.onConnectionOpened(clock())
        planner.onPieceStarted(clock())
        closeQuietly(old, age)
        activityStarted.set(false)
        activityEnded.set(false)
        endpointSettled.set(false)
        signalActivityStart()
        flushHeld(replay)
        finishSplitHold()
    }

    private fun openCompanionSocket() {
        if (companionSocket != null || isExplicitlyClosed.get() || lastApiKey.isBlank()) return
        if (!companionOpening.compareAndSet(false, true)) return
        val request = try {
            buildWebSocketRequest(lastApiKey)
        } catch (e: Exception) {
            companionOpening.set(false)
            Log.w(TAG, "Could not open the next live socket: ${e.message}")
            return
        }
        companionHandshakeStartedMs = clock()
        companionSocket = client.newWebSocket(request, object : WebSocketListener() {
            private val opened = AtomicBoolean(false)

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isExplicitlyClosed.get()) {
                    webSocket.close(1000, "Closed by client")
                    companionOpening.set(false)
                    return
                }
                val handshakeMs = (clock() - companionHandshakeStartedMs).coerceAtLeast(0L)
                val netTag = ""
                opened.set(true)
                activeSocketCount.incrementAndGet()
                socketsOpened += 1
                logDiag(DiagnosticType.SOCKET_OPEN, "connection_age_ms=0 sockets=$socketsOpened companion=yes handshake_ms=$handshakeMs $netTag")
                if (handshakeMs >= SLOW_WS_HANDSHAKE_MS) {
                    logDiag(
                        DiagnosticType.NET_SLOW_WARN,
                        "stage=stage1_companion_ws_handshake latency_ms=$handshakeMs sockets=$socketsOpened $netTag"
                    )
                }
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (this@GeminiLiveWebSocketClient.webSocket === webSocket) {
                    if (text.contains("setupComplete") && isSetupComplete.get() && !resumeHeldOnSetup) return
                    handleIncomingMessage(text)
                    return
                }
                if (!text.contains("setupComplete")) return
                if (!companionReady.compareAndSet(false, true)) return
                companionOpening.set(false)
                if (awaitingCompanion && !resumeHeldOnSetup) {
                    val replay = pendingSplit?.replay == true
                    mainHandler.post { activateCompanion(replay) }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val promoted = this@GeminiLiveWebSocketClient.webSocket === webSocket
                if (opened.compareAndSet(true, false)) releaseActiveSocket()
                val netTag = ""
                val detail = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                logDiag(
                    DiagnosticType.NET_FAILURE,
                    "stage=stage1_companion_ws promoted=$promoted exception=${t.javaClass.simpleName} http=${response?.code ?: 0} reason=${detail.replace('\n', ' ').take(140)} $netTag"
                )
                if (promoted) {
                    handleDisconnectOrFailure("WebSocket failure: $detail", t)
                    return
                }
                companionReady.set(false)
                companionOpening.set(false)
                if (companionSocket === webSocket) companionSocket = null
                Log.w(TAG, "Next live socket failed: ${t.message}")
                if (awaitingCompanion && splitting.get() && !resumeHeldOnSetup) {
                    val replay = pendingSplit?.replay == true
                    mainHandler.post { fallbackReconnectForSplit(replay) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val promoted = this@GeminiLiveWebSocketClient.webSocket === webSocket
                if (opened.compareAndSet(true, false)) releaseActiveSocket()
                if (promoted) {
                    if (!isExplicitlyClosed.get()) {
                        val detail = if (reason.isNotBlank()) reason else "No reason provided"
                        handleDisconnectOrFailure("WebSocket closed (code: $code, reason: $detail)", null)
                    }
                    return
                }
                if (companionSocket === webSocket) {
                    companionSocket = null
                    companionReady.set(false)
                    companionOpening.set(false)
                }
                if (awaitingCompanion && splitting.get() && !isExplicitlyClosed.get() && !resumeHeldOnSetup) {
                    val replay = pendingSplit?.replay == true
                    mainHandler.post { fallbackReconnectForSplit(replay) }
                }
            }
        })
    }

    private fun flushHeld(replay: Boolean) {
        val replayChunks = if (replay) replayQueue.toList() else emptyList()
        val held = synchronized(holdQueue) {
            val copy = holdQueue.toList()
            holdQueue.clear()
            copy
        }
        flushing.set(true)
        val previousHold = holdingAudio
        holdingAudio = false
        try {
            for (chunk in replayChunks) {
                sendPcm(webSocket, chunk, countReplay = false)
            }
            for (chunk in held) {
                planner.onAudio(chunk, clock())
                sendPcm(webSocket, chunk, countReplay = true)
            }
        } finally {
            flushing.set(false)
            holdingAudio = previousHold && splitting.get()
        }
    }

    private fun finishSplitHold() {
        cancelCompanionWait()
        holdingAudio = false
        awaitingCompanion = false
        splitting.set(false)
        pendingSplit = null
        val after = pendingAfterSplit
        pendingAfterSplit = null
        after?.invoke()
    }

    private fun resetPieceFlags(keepActivity: Boolean = false) {
        if (!keepActivity) {
            activityStarted.set(false)
            activityEnded.set(false)
        }
        endpointSettled.set(false)
        lastInterimText = ""
        resolvedEndpointText = ""
    }

    private fun closeQuietly(socket: WebSocket?, ageMs: Long) {
        if (socket == null) return
        if (ageMs > 0L) {
            val netTag = ""
            logDiag(DiagnosticType.SOCKET_CLOSE, "connection_age_ms=$ageMs code=1000 reason=session_ended $netTag")
        }
        try {
            val count = releaseActiveSocket()
            Log.i(TAG, "[LiveSocket] WebSocket closed. Active live sockets: $count")
            try {
                socket.close(1000, "Session ended")
            } catch (_: Exception) {}
            socket.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebSocket: ${e.message}")
        }
    }

    private fun logAge(marker: DictationPiecePlanner.AgeMarker?) {
        if (marker == null) return
        if (marker.interimPastLimit) sawInterimPast223 = true
        val flag = if (marker.interimPastLimit) "yes" else "no"
        logDiag(
            DiagnosticType.CONNECTION_AGE,
            "interim_past_223s=$flag connection_age_ms=${marker.connectionAgeMs}"
        )
    }

    private fun noteSilenceDrop(raw: String, accepted: String, rmsMean: Double, rmsMax: Double) {
        val message = TranscriptAcceptance.silenceDroppedMessage(raw, accepted, rmsMean, rmsMax) ?: return
        logDiag(DiagnosticType.SILENCE_DROPPED, message)
    }

    private fun logDiag(type: DiagnosticType, message: String) {
        AppLogRepository.logEvent(diagnosticSource, type, message)
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.0f", value)

    private fun notifyFinalizedTranscription(text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onFinalizedTranscription(text)
        } else {
            mainHandler.post { onFinalizedTranscription(text) }
        }
    }
}
