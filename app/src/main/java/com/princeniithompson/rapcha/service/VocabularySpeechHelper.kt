package com.princeniithompson.rapcha.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import java.util.Locale

/**
 * Lightweight wrapper around Android's built-in [TextToSpeech] for reading vocabulary
 * notifications and review prompts out loud to users who prefer voice or cannot read/write.
 * Automatically releases the TTS engine after speaking or when [stop] is called to save RAM
 * and battery on low-end devices.
 */
object VocabularySpeechHelper {
    private const val TAG = "VocabSpeechHelper"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingText: String? = null

    fun speak(context: Context, text: String, force: Boolean = false) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        CustomVocabularyRepository.init(context)
        if (!force && !CustomVocabularyRepository.isReadNotificationsAloud()) return

        mainHandler.post {
            try {
                pendingText = clean
                val existing = tts
                if (existing != null && isReady) {
                    speakNow(existing, clean)
                    return@post
                }
                if (existing != null) {
                    return@post
                }
                val appCtx = context.applicationContext
                var createdEngine: TextToSpeech? = null
                createdEngine = TextToSpeech(appCtx) { status ->
                    mainHandler.post {
                        val engine = createdEngine ?: return@post
                        if (status == TextToSpeech.SUCCESS) {
                            isReady = true
                            runCatching {
                                engine.language = Locale.getDefault()
                                engine.setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build()
                                )
                            }
                            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) = Unit
                                override fun onDone(utteranceId: String?) {
                                    scheduleIdleShutdown()
                                }
                                @Deprecated("Deprecated in Java")
                                override fun onError(utteranceId: String?) {
                                    scheduleIdleShutdown()
                                }
                            })
                            val toSpeak = pendingText
                            if (!toSpeak.isNullOrBlank()) {
                                speakNow(engine, toSpeak)
                            }
                        } else {
                            Log.w(TAG, "TextToSpeech init failed with status=$status")
                            stop()
                        }
                    }
                }
                tts = createdEngine
            } catch (t: Throwable) {
                Log.w(TAG, "TextToSpeech speak failed", t)
                stop()
            }
        }
    }

    private fun speakNow(engine: TextToSpeech, text: String) {
        mainHandler.removeCallbacks(idleShutdownRunnable)
        runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rapcha_vocab_prompt")
        }
    }

    private val idleShutdownRunnable = Runnable {
        stop()
    }

    private fun scheduleIdleShutdown() {
        mainHandler.removeCallbacks(idleShutdownRunnable)
        mainHandler.postDelayed(idleShutdownRunnable, 6_000L)
    }

    fun stop() {
        mainHandler.post {
            mainHandler.removeCallbacks(idleShutdownRunnable)
            pendingText = null
            isReady = false
            val engine = tts
            tts = null
            runCatching {
                engine?.stop()
                engine?.shutdown()
            }
        }
    }
}
