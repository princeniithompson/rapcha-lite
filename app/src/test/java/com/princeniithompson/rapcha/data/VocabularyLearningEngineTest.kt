package com.princeniithompson.rapcha.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VocabularyLearningEngineTest {

    private fun wrapGeminiResponse(innerJson: String): String {
        return JSONObject().apply {
            put("candidates", JSONArray().put(JSONObject().apply {
                put("content", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", innerJson)))
                })
            }))
        }.toString()
    }

    @Test
    fun `request payload includes systemInstruction for transcribe live and strict JSON responseSchema`() {
        val payload = VocabularyLearningEngine.buildRequestPayload(
            currentSession = "You can chat me on princetompson@gmail.com and ask about grokbot.",
            earlierTodaySessions = listOf("Earlier I talked about rockbot."),
            heardOnceBefore = listOf("30FRST"),
            alreadyDone = listOf("my phone number = 0557526553", "rejected: Lite")
        )

        val sysText = payload
            .getJSONObject("systemInstruction")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        assertTrue(sysText.contains("gemini-3.5-transcribe-live"))
        assertTrue(sysText.contains("Smart Polish ON"))
        assertTrue(sysText.contains("rockbot"))

        val genConfig = payload.getJSONObject("generationConfig")
        assertEquals("application/json", genConfig.getString("responseMimeType"))
        val schemaProps = genConfig.getJSONObject("responseSchema").getJSONObject("properties")
        assertTrue(schemaProps.has("ask_now"))
        assertTrue(schemaProps.has("still_waiting"))

        val userText = payload
            .getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        val userJson = JSONObject(userText)
        assertEquals("30FRST", userJson.getJSONArray("heard_once_before").getString(0))
        assertEquals("Earlier I talked about rockbot.", userJson.getJSONArray("earlier_today_sessions").getString(0))
    }

    @Test
    fun `parseFlashLiteResponse parses shortcuts words matched_waiting_term and enforces max 2 plus local dedupe`() {
        val modelJson = JSONObject().apply {
            put("ask_now", JSONArray().apply {
                // Already saved shortcut -> must be filtered out locally even if model returns it
                put(JSONObject().put("kind", "shortcut").put("trigger", "my phone number").put("value", "0550526553"))
                // Valid new email shortcut
                put(JSONObject().put("kind", "shortcut").put("trigger", "my email").put("value", "princetompson@gmail.com"))
                // Valid repeated word matching earlier phonetic mishearing
                put(JSONObject().put("kind", "word").put("value", "grokbot").put("matched_waiting_term", "rockbot"))
                // 3rd item -> must be dropped by MAX_SUGGESTIONS_PER_SESSION = 2
                put(JSONObject().put("kind", "word").put("value", "Feebo 5"))
            })
            put("still_waiting", JSONArray().put("Gemini 4 algon").put("0550526553"))
        }.toString()

        val result = VocabularyLearningEngine.parseFlashLiteResponse(
            responseBody = wrapGeminiResponse(modelJson),
            existingVocabulary = listOf("30FRST"),
            existingShortcuts = mapOf("my phone number" to "0550526553"),
            dismissedKeys = setOf("word:lite")
        )

        assertEquals(2, result.candidates.size)
        val email = result.candidates[0] as LearningCandidate.Shortcut
        assertEquals("my email", email.triggerPhrase)
        assertEquals("princetompson@gmail.com", email.replacementValue)

        val word = result.candidates[1] as LearningCandidate.CustomWord
        assertEquals("grokbot", word.term)
        assertEquals("rockbot", word.matchedWaitingTerm)

        assertEquals(listOf("Gemini 4 algon"), result.stillWaiting)
        assertTrue(result.clearedWaitingTerms.contains("rockbot"))
    }

    @Test
    fun `phone digits are spaced for TTS and batch prompt reads all items together`() {
        assertEquals("0 5 5, 7 5 2, 6 5 5 3", VocabularyLearningEngine.formatValueForSpeech("0557526553"))
        assertEquals("plus 2 3 3, 5 5 7, 5 2 6, 5 5 3", VocabularyLearningEngine.formatValueForSpeech("+233557526553"))
        assertEquals("30FRST", VocabularyLearningEngine.formatValueForSpeech("30FRST"))

        val single = listOf(LearningCandidate.CustomWord("Feebo 5"))
        assertEquals(
            "We noticed you said Feebo 5. Should we save it?",
            VocabularyLearningEngine.buildBatchSpokenPrompt(single)
        )

        val batch = listOf(
            LearningCandidate.Shortcut("my phone number", "0557526553"),
            LearningCandidate.CustomWord("Claude")
        )
        val spoken = VocabularyLearningEngine.buildBatchSpokenPrompt(batch)
        assertTrue(spoken.contains("two things"))
        assertTrue(spoken.contains("0 5 5, 7 5 2, 6 5 5 3"))
        assertTrue(spoken.contains("Claude"))
    }

    @Test
    fun `personal shortcuts expand with natural aliases and edit auto-corrections stay safe`() {
        val shortcuts = mapOf(
            "my phone number" to "0557526553",
            "my email" to "princeniithompson@gmail.com",
            "my sister's phone number" to "02440526558"
        )
        assertEquals(
            "Please call 0557526553 right now.",
            VocabularyLearningEngine.expandPersonalShortcuts("Please call my number right now.", shortcuts)
        )
        assertEquals(
            "Send it to princeniithompson@gmail.com please.",
            VocabularyLearningEngine.expandPersonalShortcuts("Send it to my email address please.", shortcuts)
        )
        assertEquals(
            "This is my phone number: 0557526553.",
            VocabularyLearningEngine.expandPersonalShortcuts("This is my phone number: 0557526553.", shortcuts)
        )

        // Safe Edit auto-corrections fix misheard phrases/emails without ever touching ordinals like 31st
        assertFalse(VocabularyLearningEngine.isSafeAutoCorrectionKey("31st"))
        assertTrue(VocabularyLearningEngine.isSafeAutoCorrectionKey("Gemini 4 algon"))
        assertTrue(VocabularyLearningEngine.isSafeAutoCorrectionKey("princetompson@gmail.com"))

        val corrected = VocabularyLearningEngine.applyAutoCorrections(
            text = "Email princetompson@gmail.com about Gemini 4 algon on May 31st.",
            corrections = mapOf(
                "princetompson@gmail.com" to "princeniithompson@gmail.com",
                "Gemini 4 algon" to "Gemini 4 Argon",
                "31st" to "30FRST" // unsafe ordinal key must be ignored
            )
        )
        assertEquals(
            "Email princeniithompson@gmail.com about Gemini 4 Argon on May 31st.",
            corrected
        )
    }

    @Test
    fun `trivial filler utterances skip network while custom single words do not`() {
        assertTrue(VocabularyLearningEngine.shouldSkipNetworkAnalysis("Okay."))
        assertTrue(VocabularyLearningEngine.shouldSkipNetworkAnalysis("Yes"))
        assertTrue(VocabularyLearningEngine.shouldSkipNetworkAnalysis("Thanks!"))
        assertFalse(VocabularyLearningEngine.shouldSkipNetworkAnalysis("grokbot"))
        assertFalse(VocabularyLearningEngine.shouldSkipNetworkAnalysis("30FRST"))
        assertFalse(VocabularyLearningEngine.shouldSkipNetworkAnalysis("0557526553"))
    }
}
