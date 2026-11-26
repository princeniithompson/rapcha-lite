package com.princeniithompson.rapcha.websocket

import android.os.Looper
import com.princeniithompson.rapcha.audio.AudioRecorder
import com.princeniithompson.rapcha.service.floating.FloatingDictationSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationDeduplicationAndLatencyTest {

    @Test
    fun `raw live preview followed by SMART polished batch replaces instead of duplicating`() {
        val rawLivePreview =
            "The text from the image. The text from the image are not arranged well, " +
                "especially the section that says \"Your Daily Usage\". If you had to rearrange " +
                "the text so it fills nice, font size, font style, and many others. How is it going to look like?"
        val smartPolishedFallback =
            "The text from the image are not arranged well, especially the section that says " +
                "\"Your Daily Usage\". If you were to rearrange the text so it feels nice, font size, " +
                "font style, and many others, how is it going to look like?"

        val result = TranscriptSeam.appendDeduplicated(rawLivePreview, smartPolishedFallback)
        assertEquals(smartPolishedFallback, result)
    }

    @Test
    fun `exact duplicate utterance returns single clean copy`() {
        val sentence = "I am ready and I have granted you the permission. Go ahead and implement the build."
        val result = TranscriptSeam.appendDeduplicated(sentence, sentence)
        assertEquals(sentence, result)
    }

    @Test
    fun `distinct multi-piece dictation joins cleanly without dropping piece one`() {
        val pieceOne = "First paragraph covering the hamburger drawer redesign and profile photo sync."
        val pieceTwo = "Second paragraph explaining how daily API rate limits reset at midnight Pacific time."
        val result = TranscriptSeam.appendDeduplicated(pieceOne, pieceTwo)
        assertEquals("$pieceOne $pieceTwo", result)
    }

    @Test
    fun `multi-piece dictation with overlapping seam drops only the repeated boundary words`() {
        val pieceOne = "We should keep the adaptive timeout generous for long recordings"
        val pieceTwo = "generous for long recordings so healthy smart polishes finish directly"
        val result = TranscriptSeam.appendDeduplicated(pieceOne, pieceTwo)
        assertEquals(
            "We should keep the adaptive timeout generous for long recordings so healthy smart polishes finish directly",
            result
        )
    }

    @Test
    fun `adaptive finalize timeout fast-fails stalled sockets and gives healthy smart sessions room`() {
        // No backup -> full 8s wait
        assertEquals(
            GeminiLiveWebSocketClient.FINAL_TRANSCRIPT_TIMEOUT_MS,
            GeminiLiveWebSocketClient.computeLiveFinalizeTimeoutMs(
                recordingSeconds = 10,
                smartMode = true,
                hasLivePreview = true,
                hasLocalBackup = false
            )
        )
        // Backup exists, zero live preview -> fast-fail at 2.5s
        assertEquals(
            GeminiLiveWebSocketClient.STALLED_LIVE_FALLBACK_TIMEOUT_MS,
            GeminiLiveWebSocketClient.computeLiveFinalizeTimeoutMs(
                recordingSeconds = 20,
                smartMode = true,
                hasLivePreview = false,
                hasLocalBackup = true
            )
        )
        // Raw mode with live preview -> 4s
        assertEquals(
            GeminiLiveWebSocketClient.RAW_LIVE_FALLBACK_TIMEOUT_MS,
            GeminiLiveWebSocketClient.computeLiveFinalizeTimeoutMs(
                recordingSeconds = 10,
                smartMode = false,
                hasLivePreview = true,
                hasLocalBackup = true
            )
        )
        // Healthy SMART sessions scale cleanly with utterance duration (+3s peak-hour headroom)
        assertEquals(
            8_500L,
            GeminiLiveWebSocketClient.computeLiveFinalizeTimeoutMs(
                recordingSeconds = 12,
                smartMode = true,
                hasLivePreview = true,
                hasLocalBackup = true
            )
        )
        assertEquals(
            10_000L,
            GeminiLiveWebSocketClient.computeLiveFinalizeTimeoutMs(
                recordingSeconds = 30,
                smartMode = true,
                hasLivePreview = true,
                hasLocalBackup = true
            )
        )
        assertEquals(
            11_500L,
            GeminiLiveWebSocketClient.computeLiveFinalizeTimeoutMs(
                recordingSeconds = 60,
                smartMode = true,
                hasLivePreview = true,
                hasLocalBackup = true
            )
        )
    }

    @Test
    fun `endpoint timeout does not fire onFinalizedTranscription so fallback cannot concatenate raw and polished`() {
        val finalizedCallbacks = mutableListOf<String>()
        lateinit var capturedSocket: WebSocket
        lateinit var capturedListener: WebSocketListener

        val client = GeminiLiveWebSocketClient(
            onSetupComplete = {},
            onInterimTranscription = {},
            onFinalizedTranscription = { finalizedCallbacks.add(it) },
            onStateChanged = {},
            onLog = { _, _, _, _ -> },
            onError = {},
            okHttpClient = object : OkHttpClient() {
                override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                    capturedListener = listener
                    capturedSocket = object : WebSocket {
                        override fun cancel() {}
                        override fun close(code: Int, reason: String?): Boolean = true
                        override fun queueSize(): Long = 0L
                        override fun request(): Request = request
                        override fun send(text: String): Boolean = true
                        override fun send(bytes: ByteString): Boolean = true
                    }
                    return capturedSocket
                }
            }
        )

        client.connect(apiKey = "test-api-key-valid-12345", smartMode = true)
        val okResponse = Response.Builder()
            .request(Request.Builder().url("https://generativelanguage.googleapis.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
        capturedListener.onOpen(capturedSocket, okResponse)
        capturedListener.onMessage(capturedSocket, """{"setupComplete":{}}""")
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(client.isLiveStreamHealthyForFinalize)
        assertFalse(client.hasReceivedLivePreview)

        client.sendAudioChunk(InterimPreviewMonitor.speechPcm(sample = 4000, sampleCount = 160))
        capturedListener.onMessage(
            capturedSocket,
            """{"serverContent":{"interimInputTranscription":{"text":"raw unpolished live preview"}}}"""
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(client.hasReceivedLivePreview)

        var returned = ""
        CoroutineScope(Dispatchers.Main).launch {
            returned = client.endActivityAndAwaitFinal(timeoutMs = 8_500L)
        }
        shadowOf(Looper.getMainLooper()).idleFor(8_500L, TimeUnit.MILLISECONDS)

        assertTrue(client.endpointTimedOut)
        assertFalse(client.finalSettledFromServer)
        assertFalse(client.wasDisconnectedDuringFinalize)
        // Emergency fallback text is returned directly from endActivityAndAwaitFinal...
        assertEquals("raw unpolished live preview", returned)
        // ...but onFinalizedTranscription MUST NOT fire on timeout!
        assertTrue(finalizedCallbacks.isEmpty())

        // Explicit disconnect + subsequent OkHttp socket-cancel onFailure must NOT flip wasDisconnectedDuringFinalize
        client.disconnect()
        capturedListener.onFailure(capturedSocket, java.net.SocketException("Socket closed"), null)
        assertFalse(client.wasDisconnectedDuringFinalize)
    }

    @Test
    fun `pcm streaming chunk size is 50ms with 15 second reconnect queue`() {
        assertEquals(1600, AudioRecorder.CHUNK_SIZE_BYTES)
        assertEquals(300, FloatingDictationSessionManager.MAX_AUDIO_QUEUE_CHUNKS)
    }
}
