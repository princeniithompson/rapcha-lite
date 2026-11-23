package com.princeniithompson.rapcha.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.data.LearningCandidate
import com.princeniithompson.rapcha.service.VocabularySpeechHelper
import com.princeniithompson.rapcha.service.floating.FloatingNotificationManager
import java.util.Locale

/**
 * Voice-first review screen opened when the user taps a vocabulary/shortcut notification or its
 * "Edit" button.
 *
 * - Automatically reads the prompt out loud using Android's built-in Text-to-Speech.
 * - Provides oversized, high-contrast Yes, No, and Edit buttons for users who cannot read or write.
 * - Supports both voice re-speaking/spelling and text editing, and shuts down TTS cleanly on exit.
 */
@Composable
fun VocabularyReviewScreen(
    initialCandidateId: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pendingList by CustomVocabularyRepository.pendingCandidates.collectAsState()

    val activeCandidate: LearningCandidate? = remember(pendingList, initialCandidateId) {
        CustomVocabularyRepository.getCandidateById(initialCandidateId)
    }

    var isEditing by remember(activeCandidate?.id) { mutableStateOf(false) }
    var editedText by remember(activeCandidate?.id) {
        mutableStateOf(activeCandidate?.editableText.orEmpty())
    }

    BackHandler {
        VocabularySpeechHelper.stop()
        onClose()
    }

    DisposableEffect(Unit) {
        FloatingNotificationManager.cancelVocabularyNotification(context)
        onDispose {
            VocabularySpeechHelper.stop()
        }
    }

    LaunchedEffect(activeCandidate?.id) {
        val candidate = activeCandidate
        if (candidate == null) {
            VocabularySpeechHelper.stop()
            onClose()
        } else {
            FloatingNotificationManager.cancelVocabularyNotification(context)
            VocabularySpeechHelper.speak(context, candidate.spokenPrompt, force = true)
        }
    }

    val voiceEditLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) {
                // If user spelled letters with spaces (e.g. "3 0 F R S T"), compact single-char tokens
                val tokens = spoken.split(Regex("""\s+"""))
                val normalized = if (tokens.size >= 3 && tokens.all { it.length == 1 }) {
                    tokens.joinToString("").uppercase(Locale.ROOT)
                } else {
                    spoken
                }
                editedText = normalized
                isEditing = true
                VocabularySpeechHelper.speak(
                    context = context,
                    text = "Updated to $normalized. Tap Yes to save.",
                    force = true
                )
            }
        }
    }

    val candidate = activeCandidate ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .testTag("vocabulary_review_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Smart Vocabulary",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Big "Hear It Out Loud" button for non-readers
            FilledTonalButton(
                onClick = {
                    val promptToRead = if (isEditing && editedText.isNotBlank()) {
                        "Current value is $editedText. Tap Yes to save, or No to ignore."
                    } else {
                        candidate.spokenPrompt
                    }
                    VocabularySpeechHelper.speak(context, promptToRead, force = true)
                },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .testTag("vocab_review_speak_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Hear prompt out loud",
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Hear It Out Loud",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = candidate.spokenPrompt,
                        fontSize = 22.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("vocab_review_prompt_text")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = candidate.displaySubtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = editedText.ifBlank { candidate.editableText },
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(horizontal = 24.dp, vertical = 14.dp)
                                .testTag("vocab_review_current_value")
                        )
                    }

                    AnimatedVisibility(visible = isEditing) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(
                                onClick = {
                                    VocabularySpeechHelper.stop()
                                    try {
                                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                            putExtra(
                                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                            )
                                            putExtra(
                                                RecognizerIntent.EXTRA_PROMPT,
                                                "Say or spell the word or number"
                                            )
                                        }
                                        voiceEditLauncher.launch(intent)
                                    } catch (_: Throwable) {
                                        Toast.makeText(
                                            context,
                                            "Voice input is not available on this device",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .testTag("vocab_review_voice_edit_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Mic,
                                    contentDescription = "Speak or spell by voice",
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Speak or Spell by Voice",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = editedText,
                                onValueChange = { editedText = it },
                                label = { Text("Or edit by typing") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("vocab_review_text_field")
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Big, simple Yes / No / Edit buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    val valueToSave = editedText.trim().ifBlank { candidate.editableText }
                    CustomVocabularyRepository.approveCandidate(candidate.id, valueToSave)
                    FloatingNotificationManager.cancelVocabularyNotification(context)
                    Toast.makeText(context, "Saved \"$valueToSave\"", Toast.LENGTH_SHORT).show()
                    if (CustomVocabularyRepository.pendingCandidates.value.isEmpty()) {
                        VocabularySpeechHelper.stop()
                        onClose()
                    }
                },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF16A34A),
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 62.dp)
                    .testTag("vocab_review_yes_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Yes, save it",
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Yes, Save It",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        CustomVocabularyRepository.rejectCandidate(candidate.id)
                        FloatingNotificationManager.cancelVocabularyNotification(context)
                        if (CustomVocabularyRepository.pendingCandidates.value.isEmpty()) {
                            VocabularySpeechHelper.stop()
                            onClose()
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.5.dp, Color(0xFFDC2626)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFDC2626)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 60.dp)
                        .testTag("vocab_review_no_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "No, ignore",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "No",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                FilledTonalButton(
                    onClick = {
                        isEditing = true
                        VocabularySpeechHelper.speak(
                            context = context,
                            text = "Tap the microphone button to speak or spell it, then tap Yes to save.",
                            force = true
                        )
                    },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 60.dp)
                        .testTag("vocab_review_edit_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit",
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Edit",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
