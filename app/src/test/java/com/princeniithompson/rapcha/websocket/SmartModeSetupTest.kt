package com.princeniithompson.rapcha.websocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** v26: the Smart Mode switch must change what is sent to the Live API. */
@RunWith(RobolectricTestRunner::class)
class SmartModeSetupTest {
    private fun transcription(smart: Boolean) =
        GeminiLiveWebSocketClient.buildSetupMessage("models/test", smart, listOf("Rapcha"))
            .getJSONObject("setup").getJSONObject("inputAudioTranscription")

    @Test
    fun smart_on_sends_smart_mode() {
        assertEquals("SMART", transcription(true).getString("mode"))
    }

    @Test
    fun smart_off_sends_raw_transcription_without_mode() {
        assertFalse(transcription(false).has("mode"))
    }

    @Test
    fun only_the_mode_differs_between_on_and_off() {
        val on = GeminiLiveWebSocketClient.buildSetupMessage("models/test", true, listOf("Rapcha"))
        val off = GeminiLiveWebSocketClient.buildSetupMessage("models/test", false, listOf("Rapcha"))
        on.getJSONObject("setup").getJSONObject("inputAudioTranscription").remove("mode")
        assertEquals(on.toString(), off.toString())
    }

    @Test
    fun setup_keeps_the_v25_prompt_vocabulary_and_manual_vad() {
        val json = GeminiLiveWebSocketClient.buildSetupMessage("models/test", true, listOf("Rapcha"))
        val setup = json.toString()
        assertEquals("models/test", json.getJSONObject("setup").getString("model"))
        assertTrue(setup.contains("Rapcha"))
        assertTrue(setup.contains("automaticActivityDetection"))
    }
}
