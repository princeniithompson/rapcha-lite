package com.princeniithompson.rapcha.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.data.LearningCandidate
import com.princeniithompson.rapcha.data.VocabularyLearningEngine
import com.princeniithompson.rapcha.service.floating.FloatingNotificationManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VocabularyLearningPipelineTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("custom_vocabulary_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        CustomVocabularyRepository.clearForTest()
        CustomVocabularyRepository.init(app)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun wrapFlashLiteJson(inner: JSONObject): String {
        return JSONObject().apply {
            put("candidates", JSONArray().put(JSONObject().apply {
                put("content", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", inner.toString())))
                })
            }))
        }.toString()
    }

    @Test
    fun `midnight reset clears daily sessions while waiting list survives and triggers on 2nd session`() = runBlocking {
        // Day 1, Session 1: user says "rockbot" (misheard "grokbot") -> Flash-Lite puts it in still_waiting
        val day1Response = wrapFlashLiteJson(JSONObject().apply {
            put("ask_now", JSONArray())
            put("still_waiting", JSONArray().put("rockbot"))
        })
        val day1Candidates = VocabularyLearningEngine.analyzeTranscriptWithContext(
            context = app,
            transcript = "I am building rockbot rockbot rockbot today.",
            dateKeyOverride = "2026-11-19",
            httpExecutor = { url, _, _ ->
                assertTrue(url.contains(VocabularyLearningEngine.LEARNING_MODEL))
                day1Response
            }
        )
        assertTrue("1st session must not notify even if repeated in same session", day1Candidates.isEmpty())
        assertEquals(listOf("rockbot"), CustomVocabularyRepository.getWaitingTerms())
        assertEquals(1, CustomVocabularyRepository.getDailySessionsForDate("2026-11-19").size)

        // Day 4 (3 days later after midnight resets): daily sessions must be fresh, waiting list still has "rockbot"
        var capturedUserPayload: JSONObject? = null
        val day4Response = wrapFlashLiteJson(JSONObject().apply {
            put("ask_now", JSONArray().put(
                JSONObject()
                    .put("kind", "word")
                    .put("value", "grokbot")
                    .put("matched_waiting_term", "rockbot")
            ))
            put("still_waiting", JSONArray())
        })
        val day4Candidates = VocabularyLearningEngine.analyzeTranscriptWithContext(
            context = app,
            transcript = "Let us deploy grokbot now.",
            dateKeyOverride = "2026-11-22",
            httpExecutor = { _, _, bodyJson ->
                val userText = JSONObject(bodyJson)
                    .getJSONArray("contents")
                    .getJSONObject(0)
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                capturedUserPayload = JSONObject(userText)
                day4Response
            }
        )

        assertNotNull(capturedUserPayload)
        assertEquals(
            "Daily sessions must be cleared after midnight reset",
            0,
            capturedUserPayload!!.getJSONArray("earlier_today_sessions").length()
        )
        assertEquals(
            "Waiting list term from Day 1 must survive midnight reset",
            "rockbot",
            capturedUserPayload!!.getJSONArray("heard_once_before").getString(0)
        )

        assertEquals(1, day4Candidates.size)
        val candidate = day4Candidates.first() as LearningCandidate.CustomWord
        assertEquals("grokbot", candidate.term)
        assertTrue(
            "Matched waiting term must be cleared once promoted to ask_now",
            CustomVocabularyRepository.getWaitingTerms().isEmpty()
        )
    }

    @Test
    fun `batch notification posts all items and edit saves to vocabulary shortcut and auto-correction`() = runBlocking {
        val response = wrapFlashLiteJson(JSONObject().apply {
            put("ask_now", JSONArray().apply {
                put(JSONObject().put("kind", "shortcut").put("trigger", "my email").put("value", "princetompson@gmail.com"))
                put(JSONObject().put("kind", "word").put("value", "Gemini 4 algon"))
            })
            put("still_waiting", JSONArray())
        })

        val candidates = VocabularyLearningEngine.analyzeTranscriptWithContext(
            context = app,
            transcript = "Chat me on princetompson@gmail.com about Gemini 4 algon.",
            dateKeyOverride = "2026-11-19",
            httpExecutor = { _, _, _ -> response }
        )
        assertEquals(2, candidates.size)

        CustomVocabularyRepository.enqueueCandidates(candidates)
        FloatingNotificationManager.postVocabularyLearningBatch(app, candidates)

        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val firstNotif = Shadows.shadowOf(nm).getNotification(FloatingNotificationManager.VOCAB_NOTIFICATION_ID)
        val secondNotif = Shadows.shadowOf(nm).getNotification(FloatingNotificationManager.VOCAB_NOTIFICATION_ID + 1)
        assertNotNull("Expected 1st notification to be posted", firstNotif)
        assertNotNull("Expected 2nd notification to be posted", secondNotif)
        assertEquals(listOf("Yes", "No", "Edit"), firstNotif!!.actions.map { it.title.toString() })

        // User edits email and edits "Gemini 4 algon" -> "Gemini 4 Argon"
        assertTrue(CustomVocabularyRepository.approveCandidate(candidates[0].id, "princeniithompson@gmail.com"))
        assertTrue(CustomVocabularyRepository.approveCandidate(candidates[1].id, "Gemini 4 Argon"))

        assertTrue(CustomVocabularyRepository.getPersonalShortcuts().isEmpty())
        assertTrue(CustomVocabularyRepository.getVocabulary().contains("my email: princeniithompson@gmail.com"))
        assertTrue(CustomVocabularyRepository.getVocabulary().contains("Gemini 4 Argon"))

        // Auto-correction still fixes explicit edited mishearings while NEVER doing regex shortcut expansion
        val fixed = CustomVocabularyRepository.expandShortcutsInTranscript(
            "Check my email or send princetompson@gmail.com the Gemini 4 algon specs."
        )
        assertEquals("Check my email or send princeniithompson@gmail.com the Gemini 4 Argon specs.", fixed)
    }
}
