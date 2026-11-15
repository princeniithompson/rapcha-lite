package com.princeniithompson.rapcha.service

import android.app.Application
import android.content.Context
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.service.floating.FloatingDictationSessionManager
import com.princeniithompson.rapcha.ui.VoiceTypingViewModel
import com.princeniithompson.rapcha.websocket.TranscriptAcceptance
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleSyncAndVocabTest {

    @Test
    fun `constructing the view model does not crash`() {
        val app = RuntimeEnvironment.getApplication() as Application
        val viewModel = VoiceTypingViewModel(app)
        assertTrue(viewModel.customApiKey.value.isEmpty() || viewModel.customApiKey.value.isNotEmpty())
    }

    @Test
    fun `fresh vocabulary is empty and the seeded identity is removed once`() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("custom_vocabulary_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        CustomVocabularyRepository.clearForTest()
        CustomVocabularyRepository.init(app)
        assertTrue(CustomVocabularyRepository.getVocabulary().isEmpty())
        assertTrue(prefs.getBoolean("seeded_identity_removed_v1", false))

        CustomVocabularyRepository.clearForTest()
        prefs.edit()
            .putString(
                "vocabulary_terms",
                JSONArray(listOf("prince niithompson", "Keep This", "princeniithompson@gmail.com")).toString()
            )
            .putBoolean("seeded_identity_removed_v1", false)
            .commit()
        CustomVocabularyRepository.init(app)
        assertEquals(listOf("Keep This"), CustomVocabularyRepository.getVocabulary())

        CustomVocabularyRepository.clearForTest()
        prefs.edit()
            .putString(
                "vocabulary_terms",
                JSONArray(listOf("Keep This", "prince niithompson")).toString()
            )
            .putBoolean("seeded_identity_removed_v1", true)
            .commit()
        CustomVocabularyRepository.init(app)
        assertTrue(CustomVocabularyRepository.getVocabulary().contains("prince niithompson"))
        assertTrue(CustomVocabularyRepository.getVocabulary().contains("Keep This"))
    }

    @Test
    fun `near silent audio and quiet vocabulary-only text are dropped`() {
        val vocab = listOf("prince niithompson", "princeniithompson@gmail.com")
        val invented = "Please send the invoice to prince niithompson at example"
        assertEquals("", TranscriptAcceptance.accept(invented, 129.0, 239.0, vocab))
        assertEquals("", TranscriptAcceptance.accept("prince niithompson", 300.0, 500.0, vocab))
        assertEquals("send the invoice", TranscriptAcceptance.accept("send the invoice", 300.0, 500.0, vocab))
        assertEquals(invented, TranscriptAcceptance.accept(invented, 1800.0, 4200.0, vocab))
        // v25: a soft one- or two-word phrase said near other people is kept (v24 dropped it).
        assertEquals("send it", TranscriptAcceptance.accept("send it", 200.0, 380.0, vocab))
        assertEquals("yes", TranscriptAcceptance.accept("yes", 165.0, 330.0, vocab))
        assertFalse(TranscriptAcceptance.isNearSilent(200.0, 380.0))
        assertTrue(TranscriptAcceptance.isNearSilent(130.0, 290.0))
        val clause = TranscriptAcceptance.vocabularyClause(vocab)
        assertTrue(clause.contains("Never insert a term that was not spoken"))
        assertFalse(clause.contains("Prioritize and accurately transcribe"))
        assertEquals("", TranscriptAcceptance.vocabularyClause(emptyList()))
        val dropped = TranscriptAcceptance.silenceDroppedMessage("prince niithompson", "", 129.0, 239.0)
        assertEquals("words=2 rms_mean=129 rms_max=239", dropped)
        assertFalse(dropped!!.contains("prince"))
        assertNull(TranscriptAcceptance.silenceDroppedMessage("send the invoice", "send the invoice", 1800.0, 4200.0))
        assertNull(TranscriptAcceptance.silenceDroppedMessage("", "", 10.0, 10.0))
    }

    @Test
    fun `a tap with no keyboard and no field hides the bubble`() {
        assertEquals(
            FloatingBubbleService.RingTap.HIDE,
            FloatingBubbleService.decideRingTap(recording = false, keyboardVisible = false, fieldFocused = false)
        )
        assertEquals(
            FloatingBubbleService.RingTap.CONTINUE,
            FloatingBubbleService.decideRingTap(recording = true, keyboardVisible = false, fieldFocused = false)
        )
        assertEquals(
            FloatingBubbleService.RingTap.CONTINUE,
            FloatingBubbleService.decideRingTap(recording = false, keyboardVisible = true, fieldFocused = false)
        )
        assertEquals(
            FloatingBubbleService.RingTap.HIDE,
            FloatingBubbleService.decideRingTap(recording = false, keyboardVisible = false, fieldFocused = true)
        )
        assertTrue(FloatingBubbleService.startsRecordingOnTap(recording = false, keyboardVisible = true, fieldFocused = false))
        assertFalse(FloatingBubbleService.startsRecordingOnTap(recording = false, keyboardVisible = false, fieldFocused = true))
        assertFalse(FloatingBubbleService.startsRecordingOnTap(recording = false, keyboardVisible = false, fieldFocused = false))
        assertFalse(FloatingBubbleService.startsRecordingOnTap(recording = true, keyboardVisible = true, fieldFocused = true))
        assertTrue(FloatingBubbleService.fieldFocusRevealsBubble(keyboardVisible = true))
        assertFalse(FloatingBubbleService.fieldFocusRevealsBubble(keyboardVisible = false))
        assertTrue(FloatingBubbleService.keyboardRecheckShouldHide(imeVisible = false, sessionActive = false))
        assertFalse(FloatingBubbleService.keyboardRecheckShouldHide(imeVisible = false, sessionActive = true))
        assertFalse(FloatingBubbleService.keyboardRecheckShouldHide(imeVisible = true, sessionActive = false))
    }

    @Test
    fun `session duration follows the wall clock`() {
        val start = 1_000L
        assertEquals(21, FloatingDictationSessionManager.elapsedSeconds(start, start + 21_400L))
        assertEquals(0, FloatingDictationSessionManager.elapsedSeconds(0L, start + 21_000L))
    }
}
