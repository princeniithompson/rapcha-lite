package com.princeniithompson.rapcha.websocket

import com.princeniithompson.rapcha.audio.SessionAudioBackup
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.data.FloatingTextActionsClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SlowNetworkAndStreamingTest {

    @Before
    fun setUp() {
        GeminiBatchTranscribeClient.httpExecutor = null
    }

    @After
    fun tearDown() {
        GeminiBatchTranscribeClient.httpExecutor = null
    }

    @Test
    fun `batch transcribe builds valid SMART payload with top 100 vocab cap`() {
        val vocab = (1..150).map { "Term$it" }
        val rawBody = GeminiBatchTranscribeClient.buildRequestBodyJson(
            audioBytes = byteArrayOf(1, 2, 3, 4),
            mimeType = "audio/mp4",
            smartMode = true,
            customVocabulary = vocab
        )
        val root = JSONObject(rawBody)
        val inlineData = root.getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")
            .getJSONObject(0)
            .getJSONObject("inlineData")
        assertEquals("audio/mp4", inlineData.getString("mimeType"))
        assertTrue(inlineData.getString("data").isNotBlank())

        val cfg = root.getJSONObject("generationConfig").getJSONObject("audioTranscriptionConfig")
        assertEquals("SMART", cfg.getString("mode"))
        assertEquals(
            CustomVocabularyRepository.MAX_GEMINI_ACTIVE_VOCAB_TERMS,
            cfg.getJSONArray("custom_vocabulary").length()
        )
    }

    @Test
    fun `batch transcribe retries once on 503 and returns text on recovery`() = runBlocking {
        var calls = 0
        var lastUrl = ""
        GeminiBatchTranscribeClient.httpExecutor = { url, _, _ ->
            calls++
            lastUrl = url
            if (calls == 1) {
                503 to """{"error":{"message":"overloaded"}}"""
            } else {
                200 to """{"candidates":[{"content":{"parts":[{"text":"Hello from fallback."}]}}]}"""
            }
        }

        val text = GeminiBatchTranscribeClient.transcribe(
            apiKey = "AIzaTestKey123456789",
            audioBytes = byteArrayOf(1, 2, 3, 4),
            mimeType = "audio/wav",
            smartMode = true,
            customVocabulary = listOf("Rapcha")
        )
        assertEquals(2, calls)
        assertTrue(lastUrl.contains("models/gemini-3.5-transcribe:generateContent"))
        assertEquals("Hello from fallback.", text)
    }

    @Test
    fun `batch transcribe does not retry on 401 unauthorized`() = runBlocking {
        var calls = 0
        GeminiBatchTranscribeClient.httpExecutor = { _, _, _ ->
            calls++
            401 to """{"error":{"message":"invalid key"}}"""
        }

        val text = GeminiBatchTranscribeClient.transcribe(
            apiKey = "AIzaTestKey123456789",
            audioBytes = byteArrayOf(1, 2, 3, 4),
            mimeType = "audio/wav",
            smartMode = false,
            customVocabulary = emptyList()
        )
        assertEquals(1, calls)
        assertNull(text)
    }

    @Test
    fun `live setup message caps customVocabulary to top 100 terms`() {
        val vocab = (1..140).map { "Word$it" }
        val setup = GeminiLiveWebSocketClient.buildSetupMessage(
            model = GeminiLiveWebSocketClient.DEFAULT_MODEL,
            smartMode = true,
            customVocabulary = vocab
        )
        val arr = setup.getJSONObject("setup")
            .getJSONObject("inputAudioTranscription")
            .getJSONArray("customVocabulary")
        assertEquals(CustomVocabularyRepository.MAX_GEMINI_ACTIVE_VOCAB_TERMS, arr.length())
    }

    @Test
    fun `session audio backup records speech, wraps valid WAV, resets per piece, and cleans up`() {
        val ctx = RuntimeEnvironment.getApplication()
        val backup = SessionAudioBackup(ctx)
        backup.startNewSession()

        // Pure silence alone does not mark hasSpeech
        backup.append(InterimPreviewMonitor.silencePcm(sampleCount = 1600))
        assertFalse(backup.hasSpeech)

        // Adding speech marks hasSpeech and exports valid audio
        backup.append(InterimPreviewMonitor.speechPcm(sample = 4000, sampleCount = 1600))
        assertTrue(backup.hasSpeech)
        assertTrue(backup.bytesWritten >= 6400L)

        val exported = backup.exportForFallback()
        assertNotNull(exported)
        assertTrue(exported!!.bytes.isNotEmpty())
        if (exported.mimeType == "audio/wav") {
            assertEquals('R', exported.bytes[0].toInt().toChar())
            assertEquals('I', exported.bytes[1].toInt().toChar())
            assertEquals('F', exported.bytes[2].toInt().toChar())
            assertEquals('F', exported.bytes[3].toInt().toChar())
        }

        backup.resetForCommittedPiece()
        assertEquals(0L, backup.bytesWritten)
        assertFalse(backup.hasSpeech)

        backup.cleanup()
    }

    @Test
    fun `telescope stream URL and SSE chunk parser work cleanly`() {
        val url = ApiConfig.geminiStreamGenerateContentUrl(FloatingTextActionsClient.MODEL)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:streamGenerateContent?alt=sse",
            url
        )

        val delta1 = FloatingTextActionsClient.parseStreamChunkText(
            """{"candidates":[{"content":{"parts":[{"text":"Hello "}]}}]}"""
        )
        val delta2 = FloatingTextActionsClient.parseStreamChunkText(
            """{"candidates":[{"content":{"parts":[{"text":"world!"}]}}]}"""
        )
        assertEquals("Hello ", delta1)
        assertEquals("world!", delta2)
    }

    @Test
    fun `session audio backup aligns near-silent threshold with TranscriptAcceptance`() {
        val ctx = RuntimeEnvironment.getApplication()
        val backup = SessionAudioBackup(ctx)
        backup.startNewSession()

        // Ambient room noise (~240 peak, < 160 mean): previously triggered hasSpeech=true at >=200
        // even though TranscriptAcceptance.isNearSilent dropped Live output, wasting batch quota.
        backup.append(InterimPreviewMonitor.silencePcm(sampleCount = 1600))
        backup.append(InterimPreviewMonitor.speechPcm(sample = 240, sampleCount = 1600))
        assertFalse(backup.hasSpeech)
        assertNull(backup.exportForFallback())

        // Soft speech peaking above NEAR_SILENT_MAX (320) still counts as speech
        backup.append(InterimPreviewMonitor.speechPcm(sample = 400, sampleCount = 1600))
        assertTrue(backup.hasSpeech)

        backup.cleanup()
    }

    @Test
    fun `dictation telemetry repository tracks ring buffer, reason codes, report text, and clearAll`() {
        val ctx = RuntimeEnvironment.getApplication()
        val repo = com.princeniithompson.rapcha.diagnostics.DictationTelemetryRepository
        repo.clearAll(ctx)

        repeat(160) { i ->
            val isFallback = i % 2 == 0
            repo.recordSession(
                context = ctx,
                event = com.princeniithompson.rapcha.diagnostics.DictationTelemetryEvent(
                    timestampMs = 1_700_000_000_000L + i * 1000L,
                    outcome = if (isFallback) {
                        com.princeniithompson.rapcha.diagnostics.DictationSessionOutcome.BATCH_FALLBACK
                    } else {
                        com.princeniithompson.rapcha.diagnostics.DictationSessionOutcome.LIVE_OK
                    },
                    reason = if (isFallback) {
                        com.princeniithompson.rapcha.diagnostics.DictationFallbackReason.LIVE_TIMEOUT_WITH_PREVIEW
                    } else {
                        com.princeniithompson.rapcha.diagnostics.DictationFallbackReason.NONE_LIVE_OK
                    },
                    audioSec = 12,
                    smartMode = true,
                    tailMs = 120L,
                    liveMs = if (isFallback) 5502L else 620L,
                    liveTimeoutBudgetMs = 5500L,
                    encodeMs = if (isFallback) 180L else 0L,
                    batchMs = if (isFallback) 2900L else 0L,
                    totalMs = if (isFallback) 8702L else 740L,
                    hadPreview = true,
                    socketState = "open",
                    wsReconnects = 0,
                    rmsMean = 900,
                    rmsMax = 2400,
                    batchMime = if (isFallback) "audio/mp4" else "none",
                    batchBytes = if (isFallback) 24000 else 0,
                    batchHttpCode = if (isFallback) 200 else 0,
                    batchAttempts = if (isFallback) 1 else 0,
                    networkType = "CELLULAR"
                )
            )
        }

        val snap = repo.snapshot.value
        assertEquals(160, snap.totalSessions)
        assertEquals(80, snap.liveOkCount)
        assertEquals(80, snap.batchFallbackCount)
        assertEquals(repo.MAX_EVENTS, snap.recentEvents.size)
        assertEquals(
            80,
            snap.reasonCounts[com.princeniithompson.rapcha.diagnostics.DictationFallbackReason.LIVE_TIMEOUT_WITH_PREVIEW]
        )

        val report = repo.buildReportText(ctx)
        assertTrue(report.contains("LIVE_TIMEOUT_WITH_PREVIEW: 80"))
        assertTrue(report.contains("Zero dictated text is recorded"))

        repo.clearAll(ctx)
        assertEquals(0, repo.snapshot.value.totalSessions)
        assertTrue(repo.snapshot.value.recentEvents.isEmpty())
    }
}
