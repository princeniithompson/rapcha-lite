package com.princeniithompson.rapcha.data

import android.content.Context
import android.util.Log
import com.princeniithompson.rapcha.core.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class PersonalShortcut(
    val triggerPhrase: String,
    val replacementValue: String,
    val addedTimestamp: Long = System.currentTimeMillis()
)

sealed class LearningCandidate {
    abstract val id: String
    abstract val dedupeKey: String
    abstract val displayTitle: String
    abstract val displaySubtitle: String
    abstract val spokenPrompt: String
    abstract val editableText: String
    abstract val matchedWaitingTerm: String?

    data class CustomWord(
        val term: String,
        override val matchedWaitingTerm: String? = null
    ) : LearningCandidate() {
        override val dedupeKey: String = "word:${term.trim().lowercase(Locale.ROOT)}"
        override val id: String = dedupeKey
        internal val isEmail: Boolean = term.contains('@') && term.contains('.')
        internal val isPhone: Boolean = VocabularyLearningEngine.looksLikePhoneNumber(term)
        override val displayTitle: String = when {
            isEmail -> "Save email \"$term\"?"
            isPhone -> "Save number \"$term\"?"
            else -> "Save \"$term\" to vocabulary?"
        }
        override val displaySubtitle: String = when {
            isEmail -> "Rapcha will recognize and spell \"$term\" accurately next time."
            isPhone -> "Rapcha will remember \"$term\" for future dictations."
            else -> "Rapcha will recognize \"$term\" next time you speak."
        }
        override val spokenPrompt: String = when {
            isEmail -> "We noticed the email $term. Should we save it?"
            isPhone -> "We noticed the number ${VocabularyLearningEngine.formatValueForSpeech(term)}. Should we save it?"
            else -> "We noticed you said $term. Should we save it?"
        }
        override val editableText: String = term
    }

    data class Shortcut(
        val triggerPhrase: String,
        val replacementValue: String,
        override val matchedWaitingTerm: String? = null
    ) : LearningCandidate() {
        override val dedupeKey: String =
            "shortcut:${VocabularyLearningEngine.normalizeTrigger(triggerPhrase)}=${VocabularyLearningEngine.normalizeShortcutValue(replacementValue).lowercase(Locale.ROOT)}"
        override val id: String = dedupeKey
        override val displayTitle: String = "Save ${VocabularyLearningEngine.formatTriggerForTitle(triggerPhrase)}?"
        override val displaySubtitle: String =
            "When you say \"${VocabularyLearningEngine.normalizeTrigger(triggerPhrase)}\", Rapcha will type \"$replacementValue\"."
        override val spokenPrompt: String =
            "${VocabularyLearningEngine.formatTriggerForSpeech(triggerPhrase)} is ${VocabularyLearningEngine.formatValueForSpeech(replacementValue)}. Shall I save it?"
        override val editableText: String = replacementValue
    }
}

data class LearningAnalysisResult(
    val candidates: List<LearningCandidate>,
    val stillWaiting: List<String>,
    val clearedWaitingTerms: List<String>
)

/**
 * Post-dictation smart vocabulary and personal-shortcut learner powered by
 * `gemini-3.5-flash-lite` (System Instruction + Structured JSON Output), paired with
 * tiny on-device daily/waiting memory and local post-transcription shortcut expansion.
 *
 * Designed for low-end devices (Infinix Hot 11 / 4 GB RAM / Android 7+):
 * - Runs asynchronously after text injection finishes (never blocks voice typing or injection).
 * - Reuses the process-wide OkHttp client and user API key from [ApiConfig] (zero new libraries).
 * - Fails silently if offline or rate-limited so dictation is never interrupted.
 */
object VocabularyLearningEngine {
    private const val TAG = "VocabLearningEngine"
    const val LEARNING_MODEL = "gemini-3.5-flash-lite"
    const val MAX_SUGGESTIONS_PER_SESSION = 2
    private const val NETWORK_TIMEOUT_SECONDS = 15L

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val ORDINAL_OR_UNIT_REGEX = Regex(
        pattern = """(?i)^\d+(?:st|nd|rd|th|am|pm|k|m|g|gb|mb|kb|px|dp|hz|fps)$"""
    )

    // Tiny filler utterances that never need a network call
    private val TRIVIAL_FILLER_UTTERANCES = setOf(
        "ok", "okay", "yes", "no", "yeah", "yep", "nope", "nah",
        "thanks", "thank you", "hello", "hi", "hey", "bye", "goodbye",
        "good", "fine", "sure", "cool", "done", "alright", "all right", "please", "sorry"
    )

    // System instruction sent to gemini-3.5-flash-lite on every post-dictation check
    const val SYSTEM_INSTRUCTION =
        "You are the smart post-dictation vocabulary assistant for Rapcha, a voice-typing app.\n" +
            "Every transcript you receive was produced by gemini-3.5-transcribe-live with Smart Polish ON, " +
            "so it may contain realistic speech-to-text mistakes or phonetic mishearings (for example: " +
            "\"rockbot\" or \"brok\" instead of \"grokbot\", \"31st\" instead of \"30FRST\", " +
            "\"Gemini 4 algon\" instead of \"Gemini 4 Argon\", or slightly misspelled emails like \"princetompson@gmail.com\").\n\n" +
            "You will receive four inputs:\n" +
            "1. already_done: words, shortcuts, and previously rejected items. NEVER suggest anything that matches or means the same as an item in already_done.\n" +
            "2. heard_once_before: unusual words the user said once on previous days.\n" +
            "3. earlier_today_sessions: the user's previous voice-typing sessions from today.\n" +
            "4. current_session: the voice-typing session that just finished.\n\n" +
            "Follow these rules strictly:\n" +
            "1. Ignore normal language & noise: NEVER suggest everyday English words (even if capitalized, like \"Lite\", \"Flash\", \"Today\", \"Monday\"), " +
            "well-known global names or brands (\"Superman\", \"Batman\", \"WhatsApp\", \"Android\"), ordinary dates/times (\"May 31st\", \"5pm\"), " +
            "prices/currencies, order/tracking/OTP/PIN numbers, or hypothetical example sentences (\"when the user says...\", \"for example...\").\n" +
            "2. Keep multi-word terms whole: Keep project names, AI models, and versioned names together as one phrase (e.g., \"Gemini 4 Argon\", \"Gemini 3.5 transcribe\", \"Feebo 5\").\n" +
            "3. Spot speech-to-text mistakes: Use sentence context to spot what the user likely meant if gemini-3.5-transcribe-live misheard a custom word, handle, or email. " +
            "If a misheard version (like \"rockbot\") appeared in an earlier session and \"grokbot\" appears now, treat them as the SAME term, output the cleanest intended spelling in value, " +
            "and put the old waiting-list spelling in matched_waiting_term.\n" +
            "4. Instant items (ask_now on the 1st session): Put personal phone numbers, email addresses, @handles, and explicit nicknames or \"save it as ...\" labels from current_session " +
            "directly into ask_now (use kind=\"shortcut\" with a clean lowercase trigger like \"my phone number\", \"my email\", \"my mom's phone number\", or \"my nickname\" when ownership is clear; " +
            "otherwise use kind=\"word\").\n" +
            "5. Separate-session rule for general custom words: Repeating a word multiple times inside current_session alone counts as 1 session only—do NOT put it in ask_now yet. " +
            "Only put a custom word, local name/slang, or tech/project term into ask_now if it appears in current_session AND also appeared (or phonetically matched) in earlier_today_sessions OR heard_once_before.\n" +
            "6. Waiting list (still_waiting): Put brand-new unusual custom words, local names, or suspected misheard custom terms that appeared for the first time in current_session " +
            "(and are not in already_done or ask_now) into still_waiting.\n" +
            "7. Strict output: Return ONLY valid JSON matching the schema. Return at most 2 items in ask_now (prioritizing personal info first). If nothing qualifies, return empty arrays."

    fun normalizeTrigger(trigger: String): String {
        return trigger
            .replace('’', '\'')
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
    }

    fun normalizeShortcutValue(rawValue: String): String {
        val trimmed = rawValue.trim().trimEnd('.', ',', ';', '!', '?')
        return when {
            trimmed.contains('@') -> trimmed.lowercase(Locale.ROOT)
            trimmed.any { it.isLetter() } -> {
                val parts = trimmed.split(Regex("""\s+"""))
                if (parts.size >= 3 && parts.drop(1).all { it.length == 1 }) {
                    parts.joinToString("")
                } else {
                    trimmed
                }
            }
            else -> trimmed.replace(Regex("""[\s\-()]"""), "")
        }
    }

    fun looksLikePhoneNumber(value: String): Boolean {
        val clean = value.trim()
        if (clean.isEmpty()) return false
        if (!clean.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }) {
            return false
        }
        val digits = clean.count { it.isDigit() }
        return digits in 7..15
    }

    fun formatTriggerForTitle(trigger: String): String {
        val norm = normalizeTrigger(trigger)
        return when {
            norm.startsWith("my ") -> "your " + norm.removePrefix("my ")
            norm.startsWith("our ") -> "your " + norm.removePrefix("our ")
            else -> norm
        }
    }

    fun formatTriggerForSpeech(trigger: String): String {
        val norm = normalizeTrigger(trigger)
        return when {
            norm.startsWith("my ") -> "Your " + norm.removePrefix("my ")
            norm.startsWith("our ") -> "Your " + norm.removePrefix("our ")
            else -> norm.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }

    /**
     * Formats a value so Android TTS speaks it clearly.
     * Phone numbers are spaced into short digit groups ("0 5 5, 7 5 2, 6 5 5 3") so TTS
     * never reads them as "five hundred fifty-seven million...".
     */
    fun formatValueForSpeech(value: String): String {
        val clean = value.trim()
        if (!looksLikePhoneNumber(clean)) return clean
        val hasPlus = clean.startsWith("+")
        val digits = clean.filter { it.isDigit() }
        if (digits.length < 7) return clean
        val groups = mutableListOf<String>()
        var idx = 0
        while (idx < digits.length) {
            val remaining = digits.length - idx
            val take = if (remaining == 4) 4 else minOf(3, remaining)
            groups.add(digits.substring(idx, idx + take).toCharArray().joinToString(" "))
            idx += take
        }
        val joined = groups.joinToString(", ")
        return if (hasPlus) "plus $joined" else joined
    }

    /**
     * Combines 1 or more learning candidates into a single natural spoken sentence so TTS
     * reads every item found in a batch instead of dropping the 2nd notification.
     */
    fun buildBatchSpokenPrompt(candidates: List<LearningCandidate>): String {
        if (candidates.isEmpty()) return ""
        if (candidates.size == 1) return candidates.first().spokenPrompt

        val ordinals = listOf("First", "Second", "Third")
        val parts = candidates.take(3).mapIndexed { index, candidate ->
            val label = ordinals.getOrElse(index) { "Next" }
            val description = when (candidate) {
                is LearningCandidate.Shortcut ->
                    "${formatTriggerForSpeech(candidate.triggerPhrase).replaceFirstChar { it.lowercase(Locale.ROOT) }} is ${formatValueForSpeech(candidate.replacementValue)}"
                is LearningCandidate.CustomWord -> when {
                    candidate.isEmail -> "the email ${candidate.term}"
                    candidate.isPhone -> "the number ${formatValueForSpeech(candidate.term)}"
                    else -> "you said ${candidate.term}"
                }
            }
            "$label, $description."
        }
        val countWord = when (candidates.size) {
            2 -> "two"
            3 -> "three"
            else -> candidates.size.toString()
        }
        return "We noticed $countWord things. ${parts.joinToString(" ")} Check your notifications to save, edit, or skip them."
    }

    fun isSafeAutoCorrectionKey(wrongForm: String): Boolean {
        val clean = wrongForm.trim()
        if (clean.length < 4) return false
        if (ORDINAL_OR_UNIT_REGEX.matches(clean)) return false
        if (clean.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }) return false
        return true
    }

    /**
     * Expands saved personal shortcuts locally on the device right before text injection.
     * Supports natural fallback aliases ("my number" -> "my phone number", "my email address" -> "my email")
     * while avoiding double expansion when defining the shortcut.
     */
    fun expandPersonalShortcuts(text: String, shortcuts: Map<String, String>): String {
        if (text.isBlank() || shortcuts.isEmpty()) return text
        return runCatching {
            val expandedMap = LinkedHashMap<String, String>()
            shortcuts.forEach { (k, v) ->
                val norm = normalizeTrigger(k)
                val cleanVal = v.trim()
                if (norm.isNotEmpty() && cleanVal.isNotEmpty()) {
                    expandedMap[norm] = cleanVal
                }
            }
            // Safe natural trigger aliases when no separate shortcut exists
            expandedMap["my phone number"]?.let { phone ->
                expandedMap.putIfAbsent("my number", phone)
            }
            expandedMap["my email"]?.let { email ->
                expandedMap.putIfAbsent("my email address", email)
            }

            var result = text
            val sorted = expandedMap.entries.sortedByDescending { it.key.length }
            for ((normTrigger, replacement) in sorted) {
                val escapedPattern = normTrigger
                    .split(" ")
                    .joinToString("""\s+""") { token ->
                        token.split("'").joinToString("""['’]""") { part -> Regex.escape(part) }
                    }
                val escapedValue = Regex.escape(replacement)
                val regex = Regex("""(?i)\b$escapedPattern\b(?!\s*(?:is|equals|:|,)?\s*$escapedValue)""")
                result = regex.replace(result) { replacement }
            }
            result
        }.getOrDefault(text)
    }

    /**
     * Applies user-confirmed Edit auto-corrections (e.g., "Gemini 4 algon" -> "Gemini 4 Argon"
     * or "princetompson@gmail.com" -> "princeniithompson@gmail.com") locally before injection.
     */
    fun applyAutoCorrections(text: String, corrections: Map<String, String>): String {
        if (text.isBlank() || corrections.isEmpty()) return text
        return runCatching {
            var result = text
            val sorted = corrections.entries
                .filter { isSafeAutoCorrectionKey(it.key) && it.value.isNotBlank() }
                .sortedByDescending { it.key.length }
            for ((wrong, right) in sorted) {
                val escaped = Regex.escape(wrong.trim())
                val regex = if (wrong.contains('@')) {
                    Regex("""(?i)(?<![A-Za-z0-9._%+\-])$escaped(?![A-Za-z0-9.\-])""")
                } else {
                    Regex("""(?i)\b$escaped\b""")
                }
                result = regex.replace(result) { right.trim() }
            }
            result
        }.getOrDefault(text)
    }

    /**
     * Skips trivial 1-word everyday filler dictations ("Okay", "Yes", "Thanks") to save
     * battery and daily API quota without skipping single custom words ("grokbot", "30FRST").
     */
    fun shouldSkipNetworkAnalysis(transcript: String): Boolean {
        val clean = transcript.trim().trimEnd('.', '!', '?', ',', ';')
        if (clean.length < 2) return true
        val lower = clean.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")
        return lower in TRIVIAL_FILLER_UTTERANCES
    }

    fun currentLocalDateKey(nowMs: Long = System.currentTimeMillis()): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
    }

    /**
     * Builds the exact REST JSON request body for `gemini-3.5-flash-lite:generateContent`
     * with System Instructions and Structured JSON Output (`responseMimeType` + `responseSchema`).
     */
    fun buildRequestPayload(
        currentSession: String,
        earlierTodaySessions: List<String>,
        heardOnceBefore: List<String>,
        alreadyDone: List<String>
    ): JSONObject {
        val inputObj = JSONObject().apply {
            put("already_done", JSONArray(alreadyDone.take(150)))
            put("heard_once_before", JSONArray(heardOnceBefore.take(40)))
            put("earlier_today_sessions", JSONArray(earlierTodaySessions.takeLast(15)))
            put("current_session", currentSession.trim().take(1500))
        }

        val itemSchema = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("kind", JSONObject().apply {
                    put("type", "STRING")
                    put("enum", JSONArray().put("shortcut").put("word"))
                    put("description", "Use shortcut for owned personal info (phone, email, nickname); use word for custom vocabulary.")
                })
                put("trigger", JSONObject().apply {
                    put("type", "STRING")
                    put("description", "Lowercase trigger phrase when kind is shortcut (e.g. 'my phone number', 'my email', 'my nickname'). Empty string for word.")
                })
                put("value", JSONObject().apply {
                    put("type", "STRING")
                    put("description", "Clean intended term, handle, email, or phone number to save.")
                })
                put("matched_waiting_term", JSONObject().apply {
                    put("type", "STRING")
                    put("description", "Exact entry from heard_once_before that this matched (even if previously misspelled), or empty string.")
                })
            })
            put("required", JSONArray().put("kind").put("value"))
        }

        val responseSchema = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("ask_now", JSONObject().apply {
                    put("type", "ARRAY")
                    put("items", itemSchema)
                    put("description", "At most 2 items to prompt the user about right now.")
                })
                put("still_waiting", JSONObject().apply {
                    put("type", "ARRAY")
                    put("items", JSONObject().put("type", "STRING"))
                    put("description", "Brand-new unusual 1-session words to remember quietly until a 2nd separate session.")
                })
            })
            put("required", JSONArray().put("ask_now").put("still_waiting"))
        }

        return JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", inputObj.toString())))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 512)
                put("responseMimeType", "application/json")
                put("responseSchema", responseSchema)
            })
        }
    }

    /**
     * Parses the raw REST response from `gemini-3.5-flash-lite` and applies local trust-boundary
     * deduplication against saved words, saved shortcuts, and dismissed keys.
     */
    fun parseFlashLiteResponse(
        responseBody: String,
        existingVocabulary: Collection<String> = emptyList(),
        existingShortcuts: Map<String, String> = emptyMap(),
        dismissedKeys: Set<String> = emptySet()
    ): LearningAnalysisResult {
        if (responseBody.isBlank()) {
            return LearningAnalysisResult(emptyList(), emptyList(), emptyList())
        }
        return runCatching {
            val root = JSONObject(responseBody)
            val contentText = extractCandidateText(root)
                ?: return LearningAnalysisResult(emptyList(), emptyList(), emptyList())
            val cleanedJson = contentText
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val parsed = JSONObject(cleanedJson)

            val savedWordsLower = existingVocabulary.map { it.trim().lowercase(Locale.ROOT) }.toSet()
            val savedLabeledValuesLower = existingVocabulary
                .mapNotNull { entry ->
                    val idx = entry.indexOf(':')
                    if (idx > 0 && idx < entry.lastIndex) {
                        normalizeShortcutValue(entry.substring(idx + 1)).lowercase(Locale.ROOT)
                            .takeIf { it.isNotEmpty() }
                    } else {
                        null
                    }
                }
                .toSet()
            val savedShortcutsLower = existingShortcuts.entries.associate {
                normalizeTrigger(it.key) to normalizeShortcutValue(it.value)
            }
            val savedShortcutValuesLower = (savedShortcutsLower.values.map { it.lowercase(Locale.ROOT) } +
                savedLabeledValuesLower).toSet()
            val dismissedLower = dismissedKeys.map { it.trim().lowercase(Locale.ROOT) }.toSet()

            val candidates = mutableListOf<LearningCandidate>()
            val clearedWaiting = mutableListOf<String>()
            val seenKeys = mutableSetOf<String>()
            val seenValuesLower = mutableSetOf<String>()

            val askNowArr = parsed.optJSONArray("ask_now") ?: JSONArray()
            for (i in 0 until askNowArr.length()) {
                if (candidates.size >= MAX_SUGGESTIONS_PER_SESSION) break
                val item = askNowArr.optJSONObject(i) ?: continue
                val kind = item.optString("kind", "word").trim().lowercase(Locale.ROOT)
                val rawValue = item.optString("value", "").trim()
                val matchedWaiting = item.optString("matched_waiting_term", "").trim().takeIf { it.isNotEmpty() }
                if (rawValue.length < 2 || rawValue.length > 80) continue

                if (matchedWaiting != null) {
                    clearedWaiting.add(matchedWaiting)
                }

                if (kind == "shortcut") {
                    val trigger = normalizeTrigger(item.optString("trigger", ""))
                    val value = normalizeShortcutValue(rawValue)
                    val lowerVal = value.lowercase(Locale.ROOT)
                    val labeledLower = "$trigger: $lowerVal"
                    if (trigger.isEmpty() || value.isEmpty() || lowerVal in seenValuesLower) continue
                    val alreadySaved = savedShortcutsLower[trigger]?.equals(value, ignoreCase = true) == true ||
                        lowerVal in savedShortcutValuesLower ||
                        labeledLower in savedWordsLower
                    val alreadyDismissed = isDismissedShortcut(trigger, value, dismissedLower)
                    seenValuesLower.add(lowerVal)
                    clearedWaiting.add(value)
                    if (!alreadySaved && !alreadyDismissed) {
                        val candidate = LearningCandidate.Shortcut(
                            triggerPhrase = trigger,
                            replacementValue = value,
                            matchedWaitingTerm = matchedWaiting
                        )
                        if (seenKeys.add(candidate.dedupeKey)) {
                            candidates.add(candidate)
                        }
                    }
                } else {
                    val value = if (rawValue.contains('@') || looksLikePhoneNumber(rawValue)) {
                        normalizeShortcutValue(rawValue)
                    } else {
                        rawValue
                    }
                    val lowerVal = value.lowercase(Locale.ROOT)
                    if (lowerVal in seenValuesLower) continue
                    val wordKey = "word:$lowerVal"
                    val alreadySaved = lowerVal in savedWordsLower || lowerVal in savedShortcutValuesLower
                    val alreadyDismissed = lowerVal in dismissedLower || wordKey in dismissedLower
                    seenValuesLower.add(lowerVal)
                    clearedWaiting.add(value)
                    if (!alreadySaved && !alreadyDismissed) {
                        val candidate = LearningCandidate.CustomWord(
                            term = value,
                            matchedWaitingTerm = matchedWaiting
                        )
                        if (seenKeys.add(candidate.dedupeKey)) {
                            candidates.add(candidate)
                        }
                    }
                }
            }

            val stillWaiting = mutableListOf<String>()
            val waitingArr = parsed.optJSONArray("still_waiting") ?: JSONArray()
            for (i in 0 until waitingArr.length()) {
                val term = waitingArr.optString(i, "").trim()
                val lower = term.lowercase(Locale.ROOT)
                if (term.length < 2 || term.length > 60) continue
                if (lower in seenValuesLower ||
                    lower in savedWordsLower ||
                    lower in savedShortcutValuesLower ||
                    lower in dismissedLower ||
                    "word:$lower" in dismissedLower
                ) {
                    continue
                }
                stillWaiting.add(term)
            }

            LearningAnalysisResult(
                candidates = candidates,
                stillWaiting = stillWaiting.distinctBy { it.lowercase(Locale.ROOT) },
                clearedWaitingTerms = clearedWaiting.distinctBy { it.lowercase(Locale.ROOT) }
            )
        }.onFailure { err ->
            Log.w(TAG, "Failed to parse Flash-Lite response", err)
        }.getOrElse {
            LearningAnalysisResult(emptyList(), emptyList(), emptyList())
        }
    }

    /**
     * Device-level post-dictation analysis using `gemini-3.5-flash-lite` + daily session memory
     * + cross-day waiting list. Safe and silent on offline/429/timeout.
     */
    suspend fun analyzeTranscriptWithContext(
        context: Context,
        transcript: String,
        dateKeyOverride: String? = null,
        httpExecutor: (suspend (url: String, apiKey: String, bodyJson: String) -> String?)? = null
    ): List<LearningCandidate> {
        val cleanTranscript = transcript.trim()
        if (shouldSkipNetworkAnalysis(cleanTranscript)) return emptyList()

        CustomVocabularyRepository.init(context)
        val dateKey = dateKeyOverride ?: currentLocalDateKey()
        val earlierToday = CustomVocabularyRepository.getDailySessionsForDate(dateKey)
        val waitingTerms = CustomVocabularyRepository.getWaitingTerms()
        val existingVocab = CustomVocabularyRepository.getVocabulary()
        val existingShortcuts = CustomVocabularyRepository.getPersonalShortcuts()
        val dismissedKeys = CustomVocabularyRepository.getDismissedCandidates()

        val apiKey = if (httpExecutor != null) {
            ApiConfig.resolveApiKey(context).ifEmpty { "TEST_API_KEY" }
        } else {
            ApiConfig.resolveApiKey(context)
        }
        if (apiKey.isBlank()) {
            // Still record today's session locally if user has no key yet? No key means live didn't run either.
            return emptyList()
        }

        val alreadyDone = buildAlreadyDoneList(existingVocab, existingShortcuts, dismissedKeys)
        val requestBodyJson = buildRequestPayload(
            currentSession = cleanTranscript,
            earlierTodaySessions = earlierToday,
            heardOnceBefore = waitingTerms,
            alreadyDone = alreadyDone
        ).toString()

        val url = ApiConfig.geminiGenerateContentUrl(LEARNING_MODEL)
        val responseBody = if (httpExecutor != null) {
            httpExecutor(url, apiKey, requestBodyJson)
        } else {
            executeFlashLiteRequest(url, apiKey, requestBodyJson)
        }

        // Always store this session in today's daily window so subsequent sessions today see it
        CustomVocabularyRepository.recordDailySession(dateKey, cleanTranscript)

        if (responseBody.isNullOrBlank()) return emptyList()

        val analysis = parseFlashLiteResponse(
            responseBody = responseBody,
            existingVocabulary = existingVocab,
            existingShortcuts = existingShortcuts,
            dismissedKeys = dismissedKeys
        )

        CustomVocabularyRepository.updateWaitingTerms(
            addTerms = analysis.stillWaiting,
            removeTerms = analysis.clearedWaitingTerms
        )

        return analysis.candidates
    }

    private suspend fun executeFlashLiteRequest(
        url: String,
        apiKey: String,
        bodyJson: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val client = ApiConfig.createOkHttpClient().newBuilder()
                .callTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                ApiUsageRepository.recordModulesCall(response.code)
                if (!response.isSuccessful) {
                    Log.w(TAG, "Flash-Lite learning call failed with HTTP ${response.code}")
                    return@withContext null
                }
                response.body?.string()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Flash-Lite learning call skipped (${t.javaClass.simpleName})")
            null
        }
    }

    private fun extractCandidateText(root: JSONObject): String? {
        val candidatesArr = root.optJSONArray("candidates") ?: return null
        val first = candidatesArr.optJSONObject(0) ?: return null
        val content = first.optJSONObject("content") ?: return null
        val parts = content.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val text = parts.optJSONObject(i)?.optString("text", "").orEmpty()
            if (text.isNotEmpty()) sb.append(text)
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun buildAlreadyDoneList(
        vocabulary: Collection<String>,
        shortcuts: Map<String, String>,
        dismissed: Set<String>
    ): List<String> {
        val combined = LinkedHashSet<String>()
        vocabulary.forEach { term ->
            val clean = term.trim()
            if (clean.isNotEmpty()) combined.add(clean)
        }
        shortcuts.forEach { (trigger, value) ->
            val t = normalizeTrigger(trigger)
            val v = value.trim()
            if (t.isNotEmpty() && v.isNotEmpty()) {
                combined.add("$t = $v")
            }
        }
        dismissed.forEach { key ->
            val readable = key
                .removePrefix("word:")
                .removePrefix("shortcut:")
                .trim()
            if (readable.isNotEmpty()) combined.add("rejected: $readable")
        }
        return combined.toList()
    }

    private fun isDismissedShortcut(trigger: String, value: String, dismissedLower: Set<String>): Boolean {
        val normTrigger = normalizeTrigger(trigger)
        val lowerValue = normalizeShortcutValue(value).lowercase(Locale.ROOT)
        val pairKey = "shortcut:$normTrigger=$lowerValue"
        val legacyKey = "shortcut:$normTrigger"
        val wordKey = "word:$lowerValue"
        return pairKey in dismissedLower ||
            legacyKey in dismissedLower ||
            normTrigger in dismissedLower ||
            wordKey in dismissedLower ||
            lowerValue in dismissedLower
    }
}
