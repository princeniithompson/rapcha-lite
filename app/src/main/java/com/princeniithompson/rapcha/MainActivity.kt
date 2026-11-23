package com.princeniithompson.rapcha

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.princeniithompson.rapcha.data.ThemeRepository
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.service.floating.FloatingNotificationManager
import com.princeniithompson.rapcha.ui.VoiceTypingScreen
import com.princeniithompson.rapcha.ui.VoiceTypingViewModel
import com.princeniithompson.rapcha.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceTypingViewModel by viewModels()
    private var dictionaryDraft by mutableStateOf<String?>(null)
    private var openDestination by mutableStateOf<String?>(null)
    private var vocabCandidateId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RapchaApplication.ensureBackgroundInit(this)
        ThemeRepository.init(this)
        com.princeniithompson.rapcha.data.ApiUsageRepository.init(this)
        com.princeniithompson.rapcha.data.UserProfileRepository.init(this)
        com.princeniithompson.rapcha.data.CustomVocabularyRepository.init(this)
        com.princeniithompson.rapcha.data.ToneRepository.init(this)
        com.princeniithompson.rapcha.data.OnboardingRepository.init(this)
        FloatingBubbleManager.init(this)
        consumeIntentExtras(intent)
        enableEdgeToEdge()
        setContent {
            // v25 lean: no haptics anywhere in the app.
            val rootView = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect { rootView.isHapticFeedbackEnabled = false }
            val isDarkTheme by ThemeRepository.isDarkTheme.collectAsState()
            LaunchedEffect(isDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    }
                )
            }
            MyApplicationTheme(darkTheme = isDarkTheme, dynamicColor = true, animate = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VoiceTypingScreen(
                        viewModel = viewModel,
                        dictionaryDraft = dictionaryDraft,
                        onDictionaryDraftConsumed = {
                            dictionaryDraft = null
                            intent.removeExtra(EXTRA_DICTIONARY_DRAFT)
                        },
                        openDestination = openDestination,
                        onOpenDestinationConsumed = {
                            openDestination = null
                            intent.removeExtra(FloatingNotificationManager.EXTRA_OPEN_DESTINATION)
                        },
                        vocabCandidateId = vocabCandidateId,
                        onVocabCandidateIdConsumed = {
                            vocabCandidateId = null
                            intent.removeExtra(FloatingNotificationManager.EXTRA_VOCAB_CANDIDATE_ID)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntentExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        FloatingBubbleManager.checkPermissions(this)
        FloatingBubbleManager.ensureBubbleRunningAndPromoted(this, source = "user_resume")
        viewModel.refreshPermissions()
        viewModel.tryAutoInjectFromClipboard()
    }

    private fun consumeIntentExtras(intent: Intent?) {
        val draft = intent?.getStringExtra(EXTRA_DICTIONARY_DRAFT)?.trim().orEmpty()
        if (draft.isNotEmpty()) {
            dictionaryDraft = draft
        }
        val candidate = intent?.getStringExtra(FloatingNotificationManager.EXTRA_VOCAB_CANDIDATE_ID)?.trim().orEmpty()
        if (candidate.isNotEmpty()) {
            vocabCandidateId = candidate
        }
        val destination = intent?.getStringExtra(FloatingNotificationManager.EXTRA_OPEN_DESTINATION)?.trim().orEmpty()
        if (destination.isNotEmpty()) {
            openDestination = destination
        }
    }

    companion object {
        const val EXTRA_DICTIONARY_DRAFT = "com.princeniithompson.rapcha.extra.DICTIONARY_DRAFT"
    }
}
