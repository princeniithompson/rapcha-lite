package com.princeniithompson.rapcha.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.data.LearningCandidate
import com.princeniithompson.rapcha.service.floating.FloatingNotificationManager

/**
 * Handles standard WhatsApp/Telegram-style "Yes" and "No" notification actions for
 * post-dictation vocabulary and personal shortcut suggestions.
 */
class VocabularyNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val candidateId = intent.getStringExtra(FloatingNotificationManager.EXTRA_VOCAB_CANDIDATE_ID)
        val notificationId = intent.getIntExtra(
            FloatingNotificationManager.EXTRA_VOCAB_NOTIFICATION_ID,
            FloatingNotificationManager.VOCAB_NOTIFICATION_ID
        )

        CustomVocabularyRepository.init(context)
        VocabularySpeechHelper.stop()
        FloatingNotificationManager.cancelVocabularyNotification(context, notificationId)

        when (action) {
            ACTION_VOCAB_YES -> {
                val candidate = CustomVocabularyRepository.getCandidateById(candidateId)
                val saved = CustomVocabularyRepository.approveCandidate(candidateId)
                if (saved && candidate != null) {
                    val label = when (candidate) {
                        is LearningCandidate.CustomWord -> "Saved \"${candidate.term}\" to vocabulary"
                        is LearningCandidate.Shortcut -> "Saved \"${CustomVocabularyRepository.formatLabeledVocabularyEntry(candidate.triggerPhrase, candidate.replacementValue)}\" to vocabulary"
                    }
                    Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_VOCAB_NO -> {
                CustomVocabularyRepository.rejectCandidate(candidateId)
            }
        }
    }

    companion object {
        const val ACTION_VOCAB_YES = "com.princeniithompson.rapcha.action.VOCAB_YES"
        const val ACTION_VOCAB_NO = "com.princeniithompson.rapcha.action.VOCAB_NO"
    }
}
