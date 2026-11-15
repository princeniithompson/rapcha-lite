package com.princeniithompson.rapcha.websocket

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.princeniithompson.rapcha.core.ApiKeyVault
import com.princeniithompson.rapcha.data.ConnectionState
import com.princeniithompson.rapcha.ui.screens.onboarding.provisioning.AiStudioAutomationEngine
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiAuthAndReconnectTest {

    private class FakeWebSocket : WebSocket {
        override fun cancel() {}
        override fun close(code: Int, reason: String?): Boolean = true
        override fun queueSize(): Long = 0L
        override fun request(): Request = Request.Builder().url("https://generativelanguage.googleapis.com").build()
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
    }

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ApiKeyVault.resetForTesting()
    }

    @After
    fun tearDown() {
        ApiKeyVault.resetForTesting()
    }

    @Test
    fun `isNonRetryableApiKeyError identifies HTTP 401 403 and invalid key server errors`() {
        assertTrue(GeminiLiveWebSocketClient.isNonRetryableApiKeyError(401, "Unauthorized"))
        assertTrue(GeminiLiveWebSocketClient.isNonRetryableApiKeyError(403, "Forbidden"))
        assertTrue(
            GeminiLiveWebSocketClient.isNonRetryableApiKeyError(
                400,
                "INVALID_ARGUMENT API key not valid. Please pass a valid API key."
            )
        )
        assertTrue(
            GeminiLiveWebSocketClient.isNonRetryableApiKeyError(
                0,
                "PERMISSION_DENIED: Caller does not have permission"
            )
        )
        assertFalse(GeminiLiveWebSocketClient.isNonRetryableApiKeyError(503, "Service Unavailable"))
        assertFalse(GeminiLiveWebSocketClient.isNonRetryableApiKeyError(0, "Socket timeout"))
    }

    @Test
    fun `invalid api key handshake HTTP 403 fails fast with MissingApiKey without reconnecting`() {
        val socketsCreated = mutableListOf<FakeWebSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        val structuredErrors = mutableListOf<GeminiLiveError>()
        val states = mutableListOf<ConnectionState>()

        val client = GeminiLiveWebSocketClient(
            onSetupComplete = {},
            onInterimTranscription = {},
            onFinalizedTranscription = {},
            onStateChanged = { states.add(it) },
            onLog = { _, _, _, _ -> },
            onError = {},
            okHttpClient = object : OkHttpClient() {
                override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                    val ws = FakeWebSocket()
                    socketsCreated.add(ws)
                    listeners.add(listener)
                    return ws
                }
            },
            onStructuredError = { structuredErrors.add(it) },
            isSessionActive = { true }
        )

        client.connect(apiKey = "AIzaSyInvalidKeyTest12345678901234567890")
        val response403 = Response.Builder()
            .request(Request.Builder().url("https://generativelanguage.googleapis.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Forbidden")
            .build()
        listeners.first().onFailure(socketsCreated.first(), IOException("HTTP 403"), response403)
        shadowOf(Looper.getMainLooper()).idle()

        // Must NOT schedule retries or emit Reconnecting state
        assertFalse(client.isReconnecting)
        assertEquals(1, socketsCreated.size)
        assertTrue(structuredErrors.any { it is GeminiLiveError.MissingApiKey })
        assertTrue(states.last() is ConnectionState.Error)
        client.disconnect()
    }

    @Test
    fun `api key verification state invalidates on key change or rejection and blocks hidden browser once verified`() {
        val testKey = "AIzaSyVerifiedWorkingKey1234567890ABCDEFG"
        val prefs = app.getSharedPreferences(ApiKeyVault.SETTINGS_PREFS_NAME, Application.MODE_PRIVATE)
        prefs.edit().putString(ApiKeyVault.KEY_CUSTOM_API_KEY, testKey).commit()
        ApiKeyVault.init(app)

        assertFalse(ApiKeyVault.hasVerifiedWorkingKey())
        assertTrue(
            AiStudioAutomationEngine.shouldAllowHiddenBrowser(
                hasVerifiedWorkingKey = false,
                onboardingCompleted = true
            )
        )

        // Marking verified enables verified status and shuts down hidden browser eligibility on revisit
        ApiKeyVault.markApiKeyVerified(app, testKey)
        assertTrue(ApiKeyVault.currentApiKeyVerified)
        assertTrue(ApiKeyVault.hasVerifiedWorkingKey())
        assertFalse(
            AiStudioAutomationEngine.shouldAllowHiddenBrowser(
                hasVerifiedWorkingKey = true,
                onboardingCompleted = true
            )
        )

        // Even with verified key, explicit recovery / account switch allows browser, AND invalidating resets flag
        assertTrue(
            AiStudioAutomationEngine.shouldAllowHiddenBrowser(
                hasVerifiedWorkingKey = true,
                onboardingCompleted = true,
                forceRecovery = true
            )
        )
        ApiKeyVault.markApiKeyInvalid(app, "API key expired")
        assertFalse(ApiKeyVault.currentApiKeyVerified)
        assertFalse(ApiKeyVault.hasVerifiedWorkingKey())
        assertEquals("API key expired", ApiKeyVault.apiKeyErrorReason.value)
        assertTrue(
            AiStudioAutomationEngine.shouldAllowHiddenBrowser(
                hasVerifiedWorkingKey = false,
                onboardingCompleted = true
            )
        )
    }
}
