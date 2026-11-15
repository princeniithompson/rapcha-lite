package com.princeniithompson.rapcha.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.Service
import android.content.Context
import com.princeniithompson.rapcha.RapchaApplication
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.data.DictationEndedReason
import com.princeniithompson.rapcha.data.TranscriptPrivacy
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.service.FloatingBubbleService
import com.princeniithompson.rapcha.service.MicrophoneForegroundPolicy
import com.princeniithompson.rapcha.service.floating.FloatingDictationSessionManager
import com.princeniithompson.rapcha.websocket.GeminiLiveWebSocketClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashAndDiagnosticsTest {

    @Test
    fun transcriptSummaryMasksEmailAndPhone() {
        val summary = TranscriptPrivacy.summary("Email ada@example.com or call 555-123-4567 today")
        assertFalse(summary.contains("ada@example.com"))
        assertFalse(summary.contains("555-123-4567"))
        assertTrue(summary.contains("[email]"))
        assertTrue(summary.contains("[phone]"))
        assertTrue(summary.contains("words"))
        assertTrue(summary.contains("chars"))
    }

    @Test
    fun missingApiKeyIsNotAConnectionDrop() {
        assertEquals(
            DictationEndedReason.MISSING_API_KEY,
            DictationEndedReason.fromMessage("Gemini API Key is missing or placeholder. Please provide a valid key.")
        )
        assertEquals(
            DictationEndedReason.PING_TIMEOUT,
            DictationEndedReason.fromMessage("sent ping but didn't receive pong")
        )
        assertEquals(
            DictationEndedReason.CONNECTION_DROP,
            DictationEndedReason.fromMessage("connection reset")
        )
    }

    @Test
    fun activeSocketCountNeverGoesNegative() {
        GeminiLiveWebSocketClient.debugSetActiveSocketCount(2)
        assertEquals(1, GeminiLiveWebSocketClient.releaseActiveSocket())
        assertEquals(0, GeminiLiveWebSocketClient.releaseActiveSocket())
        assertEquals(0, GeminiLiveWebSocketClient.releaseActiveSocket())
        GeminiLiveWebSocketClient.debugSetActiveSocketCount(0)
    }

    @Test
    fun idleSocketDoesNotReconnect() {
        GeminiLiveWebSocketClient.debugSetActiveSocketCount(0)
        val attempts = AtomicInteger(0)
        val active = AtomicBoolean(false)
        val client = failingClient(attempts, active)
        client.connect("AIzaSyFakeKeyForCrashDiagnostics12345")
        ShadowLooper.shadowMainLooper().idleFor(8, TimeUnit.SECONDS)
        assertEquals(1, attempts.get())
        assertEquals(0, GeminiLiveWebSocketClient.getActiveSocketCount())
        client.disconnect()
    }

    @Test
    fun activeDictationSchedulesReconnect() {
        GeminiLiveWebSocketClient.debugSetActiveSocketCount(0)
        val attempts = AtomicInteger(0)
        val active = AtomicBoolean(true)
        val client = failingClient(attempts, active)
        client.connect("AIzaSyFakeKeyForCrashDiagnostics12345")
        ShadowLooper.shadowMainLooper().idleFor(2500, TimeUnit.MILLISECONDS)
        assertTrue("expected a reconnect while dictation is active, attempts=${attempts.get()}", attempts.get() >= 2)
        active.set(false)
        client.disconnect()
        ShadowLooper.shadowMainLooper().idleFor(5, TimeUnit.SECONDS)
        GeminiLiveWebSocketClient.debugSetActiveSocketCount(0)
    }

    @Test
    fun bubbleInitDoesNotAutoStartFromBackground() {
        assertFalse(FloatingBubbleManager.shouldAutoStartBubbleFromInit(enabled = true, canOverlay = true, uiForeground = false))
        assertTrue(FloatingBubbleManager.shouldAutoStartBubbleFromInit(enabled = true, canOverlay = true, uiForeground = true))
        assertFalse(FloatingBubbleManager.shouldAutoStartBubbleFromInit(enabled = false, canOverlay = true, uiForeground = true))
    }

    @Test
    fun bubbleServiceIsNotSticky() {
        val controller = Robolectric.buildService(FloatingBubbleService::class.java).create()
        val result = controller.get().onStartCommand(null, 0, 1)
        assertEquals(Service.START_NOT_STICKY, result)
        controller.destroy()
    }

    @Test
    fun microphoneForegroundRequiresAnAllowedProcessState() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val activityManager = app.getSystemService(ActivityManager::class.java)
        val background = ActivityManager.RunningAppProcessInfo().apply {
            pid = android.os.Process.myPid()
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        }
        shadowOf(activityManager).setProcesses(listOf(background))
        assertFalse(MicrophoneForegroundPolicy.isAllowed(app))

        val foregroundService = ActivityManager.RunningAppProcessInfo().apply {
            pid = android.os.Process.myPid()
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        }
        shadowOf(activityManager).setProcesses(listOf(foregroundService))
        assertTrue(MicrophoneForegroundPolicy.isAllowed(app))
    }

    private fun failingClient(attempts: AtomicInteger, sessionActive: AtomicBoolean): GeminiLiveWebSocketClient {
        val http = object : OkHttpClient() {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                attempts.incrementAndGet()
                val socket = object : WebSocket {
                    override fun cancel() {}
                    override fun close(code: Int, reason: String?): Boolean = true
                    override fun queueSize(): Long = 0L
                    override fun request(): Request = request
                    override fun send(text: String): Boolean = true
                    override fun send(bytes: okio.ByteString): Boolean = true
                }
                listener.onFailure(socket, java.io.IOException("connection reset"), null)
                return socket
            }
        }
        return GeminiLiveWebSocketClient(
            onSetupComplete = {},
            onInterimTranscription = {},
            onFinalizedTranscription = {},
            onStateChanged = {},
            onLog = { _, _, _, _ -> },
            onError = {},
            okHttpClient = http,
            isSessionActive = { sessionActive.get() }
        )
    }
}
