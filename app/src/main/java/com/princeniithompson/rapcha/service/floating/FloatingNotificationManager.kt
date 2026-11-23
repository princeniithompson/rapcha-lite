package com.princeniithompson.rapcha.service.floating

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.princeniithompson.rapcha.MainActivity
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.data.LearningCandidate
import com.princeniithompson.rapcha.data.VocabularyLearningEngine
import com.princeniithompson.rapcha.service.FloatingBubbleService
import com.princeniithompson.rapcha.service.VocabularyNotificationReceiver
import com.princeniithompson.rapcha.service.VocabularySpeechHelper

object FloatingNotificationManager {
    private const val TAG = "FloatingNotifMgr"
    const val CHANNEL_ID = "voxstream_floating_channel"
    const val STATUS_CHANNEL_ID = "rapcha_bubble_status_channel"
    const val VOCAB_CHANNEL_ID = "rapcha_vocab_learning_channel"
    const val NOTIFICATION_ID = 2001
    const val RESTORE_NOTIFICATION_ID = 2002
    const val ATTENTION_NOTIFICATION_ID = 2003
    const val VOCAB_NOTIFICATION_ID = 2004

    const val EXTRA_OPEN_DESTINATION = "com.princeniithompson.rapcha.extra.OPEN_DESTINATION"
    const val EXTRA_VOCAB_CANDIDATE_ID = "com.princeniithompson.rapcha.extra.VOCAB_CANDIDATE_ID"
    const val EXTRA_VOCAB_NOTIFICATION_ID = "com.princeniithompson.rapcha.extra.VOCAB_NOTIFICATION_ID"
    const val DEST_KEEP_AWAKE = "keep_awake"
    const val DEST_ACCESSIBILITY_GUIDE = "accessibility_guide"
    const val DEST_VOCAB_REVIEW = "vocab_review"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rapcha Voice Typing Bubble",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the floating voice typing bubble active across apps"
            }
            val statusChannel = NotificationChannel(
                STATUS_CHANNEL_ID,
                "Rapcha Bubble Recovery & Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when the floating bubble needs restoring or accessibility attention"
            }
            val vocabChannel = NotificationChannel(
                VOCAB_CHANNEL_ID,
                "Rapcha Smart Vocabulary",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Asks to save new custom words or personal shortcuts after dictation"
            }
            notificationManager.createNotificationChannel(channel)
            notificationManager.createNotificationChannel(statusChannel)
            notificationManager.createNotificationChannel(vocabChannel)
        }
    }

    fun buildNotification(context: Context, contentText: String): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val restoreIntent = Intent(context, FloatingBubbleService::class.java).apply {
            action = FloatingBubbleService.ACTION_RESTORE
        }
        val restorePendingIntent = servicePendingIntent(context, 101, restoreIntent)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Rapcha Voice Typing")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(0, "Restore bubble", restorePendingIntent)
            .build()
    }

    fun postRestoreNotification(context: Context) {
        if (!canPostNotifications(context)) return
        createChannel(context)
        try {
            val restoreIntent = Intent(context, FloatingBubbleService::class.java).apply {
                action = FloatingBubbleService.ACTION_RESTORE
            }
            val restorePending = servicePendingIntent(context, RESTORE_NOTIFICATION_ID, restoreIntent)
            val notification = NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
                .setContentTitle("Rapcha bubble is hidden")
                .setContentText("Tap to bring it back.")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(restorePending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(RESTORE_NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to post restore notification", t)
        }
    }

    fun cancelRestoreNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(RESTORE_NOTIFICATION_ID)
        } catch (_: Throwable) {}
    }

    fun postAttentionNotification(
        context: Context,
        body: String,
        destination: String = DEST_ACCESSIBILITY_GUIDE,
        urgent: Boolean = false
    ) {
        if (!canPostNotifications(context)) return
        createChannel(context)
        try {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_DESTINATION, destination)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                ATTENTION_NOTIFICATION_ID,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
                .setContentTitle("Rapcha needs attention")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(
                    if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
                )
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(ATTENTION_NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to post attention notification", t)
        }
    }

    fun cancelAttentionNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(ATTENTION_NOTIFICATION_ID)
        } catch (_: Throwable) {}
    }

    /**
     * Posts all learning notifications discovered in a single dictation batch and reads the
     * entire batch aloud in one smooth spoken sentence when Read Notifications Aloud is ON.
     */
    fun postVocabularyLearningBatch(
        context: Context,
        candidates: List<LearningCandidate>
    ) {
        if (candidates.isEmpty()) return
        CustomVocabularyRepository.init(context)
        if (CustomVocabularyRepository.isReadNotificationsAloud()) {
            val combinedPrompt = VocabularyLearningEngine.buildBatchSpokenPrompt(candidates)
            if (combinedPrompt.isNotBlank()) {
                VocabularySpeechHelper.speak(context, combinedPrompt)
            }
        }
        candidates.forEachIndexed { idx, candidate ->
            postVocabularyLearningNotification(
                context = context,
                candidate = candidate,
                indexOffset = idx,
                speakAloud = false
            )
        }
    }

    /**
     * Posts a standard WhatsApp/Telegram-style notification asking the user whether to save a
     * newly detected custom word or personal shortcut, with "Yes", "No", and "Edit" actions.
     */
    fun postVocabularyLearningNotification(
        context: Context,
        candidate: LearningCandidate,
        indexOffset: Int = 0,
        speakAloud: Boolean = (indexOffset == 0)
    ) {
        CustomVocabularyRepository.init(context)
        if (speakAloud && CustomVocabularyRepository.isReadNotificationsAloud()) {
            VocabularySpeechHelper.speak(context, candidate.spokenPrompt)
        }
        if (!canPostNotifications(context)) return
        createChannel(context)
        try {
            val notifId = VOCAB_NOTIFICATION_ID + (indexOffset.coerceIn(0, 1))
            val reqBase = notifId * 10

            val yesIntent = Intent(context, VocabularyNotificationReceiver::class.java).apply {
                action = VocabularyNotificationReceiver.ACTION_VOCAB_YES
                putExtra(EXTRA_VOCAB_CANDIDATE_ID, candidate.id)
                putExtra(EXTRA_VOCAB_NOTIFICATION_ID, notifId)
            }
            val yesPending = PendingIntent.getBroadcast(
                context,
                reqBase + 1,
                yesIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val noIntent = Intent(context, VocabularyNotificationReceiver::class.java).apply {
                action = VocabularyNotificationReceiver.ACTION_VOCAB_NO
                putExtra(EXTRA_VOCAB_CANDIDATE_ID, candidate.id)
                putExtra(EXTRA_VOCAB_NOTIFICATION_ID, notifId)
            }
            val noPending = PendingIntent.getBroadcast(
                context,
                reqBase + 2,
                noIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val editIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_DESTINATION, DEST_VOCAB_REVIEW)
                putExtra(EXTRA_VOCAB_CANDIDATE_ID, candidate.id)
                putExtra(EXTRA_VOCAB_NOTIFICATION_ID, notifId)
            }
            val editPending = PendingIntent.getActivity(
                context,
                reqBase + 3,
                editIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(context, VOCAB_CHANNEL_ID)
                .setContentTitle(candidate.displayTitle)
                .setContentText(candidate.spokenPrompt)
                .setStyle(NotificationCompat.BigTextStyle().bigText(candidate.spokenPrompt))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(editPending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .addAction(0, "Yes", yesPending)
                .addAction(0, "No", noPending)
                .addAction(0, "Edit", editPending)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(notifId, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to post vocabulary learning notification", t)
        }
    }

    fun cancelVocabularyNotification(context: Context, notificationId: Int = VOCAB_NOTIFICATION_ID) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(notificationId)
            nm.cancel(VOCAB_NOTIFICATION_ID)
            nm.cancel(VOCAB_NOTIFICATION_ID + 1)
        } catch (_: Throwable) {}
    }

    private fun servicePendingIntent(context: Context, requestCode: Int, intent: Intent): PendingIntent {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        } else {
            PendingIntent.getService(context, requestCode, intent, flags)
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
