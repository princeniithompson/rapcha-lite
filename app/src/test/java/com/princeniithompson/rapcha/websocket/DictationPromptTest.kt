package com.princeniithompson.rapcha.websocket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationPromptTest {

    @Test
    fun `the prompt keeps soft short speech from the closest speaker`() {
        val prompt = GeminiLiveWebSocketClient.DICTATION_SYSTEM_PROMPT
        assertFalse(prompt.contains("background conversations"))
        assertTrue(prompt.contains("even a single quiet word"))
        assertTrue(prompt.contains("never drop soft or short speech"))
        assertTrue(prompt.contains("Do not reply conversationally"))
    }
}
