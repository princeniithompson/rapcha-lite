package com.princeniithompson.rapcha.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class VocabularyItem(
    val term: String,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val lastUsedTimestamp: Long = System.currentTimeMillis(),
    val isKeepDismissed: Boolean = false
) {
    fun getDaysUnused(): Long {
        val diffMs = System.currentTimeMillis() - lastUsedTimestamp
        return (diffMs / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
    }
}

object CustomVocabularyRepository {
    private const val TAG = "CustomVocabRepo"
    private const val PREFS_NAME = "custom_vocabulary_prefs"
    private const val KEY_TERMS = "vocabulary_terms"
    private const val KEY_METADATA = "vocabulary_metadata_v2"
    private const val KEY_SEEDED_IDENTITY_REMOVED = "seeded_identity_removed_v1"
    private const val KEY_PERSONAL_SHORTCUTS = "personal_shortcuts_v1"
    private const val KEY_DISMISSED_CANDIDATES = "dismissed_vocab_candidates_v1"
    private const val KEY_READ_NOTIFICATIONS_ALOUD = "read_notifications_aloud_v1"
    private const val KEY_DAILY_DATE = "daily_learning_date_v1"
    private const val KEY_DAILY_SESSIONS = "daily_learning_sessions_v1"
    private const val KEY_WAITING_TERMS = "waiting_learning_terms_v1"
    private const val KEY_AUTO_CORRECTIONS = "vocab_auto_corrections_v1"
    private val SEEDED_IDENTITY = setOf("prince niithompson", "princeniithompson@gmail.com")

    private const val MAX_DAILY_SESSIONS = 15
    private const val MAX_DAILY_SESSION_CHARS = 350
    private const val MAX_WAITING_TERMS = 40
    private const val WAITING_TERM_TTL_MS = 14L * 24 * 60 * 60 * 1000
    private const val MAX_AUTO_CORRECTIONS = 60
    
    const val UNUSED_THRESHOLD_DAYS = 60
    const val MAX_GEMINI_LIVE_VOCAB_TERMS = 1000
    /** Optimal vocabulary cap sent to Gemini Live & Batch Transcribe for best accuracy and low setup bytes. */
    const val MAX_GEMINI_ACTIVE_VOCAB_TERMS = 100

    private var prefs: SharedPreferences? = null
    
    // Ordered list of active string terms (sent to Gemini Live)
    private val _vocabulary = MutableStateFlow<List<String>>(emptyList())
    val vocabulary: StateFlow<List<String>> = _vocabulary.asStateFlow()

    // Full items list with timestamps
    private val _vocabularyItems = MutableStateFlow<List<VocabularyItem>>(emptyList())
    val vocabularyItems: StateFlow<List<VocabularyItem>> = _vocabularyItems.asStateFlow()

    // Clean-up suggestions for terms unused for >= 60 days
    private val _cleanupSuggestions = MutableStateFlow<List<VocabularyItem>>(emptyList())
    val cleanupSuggestions: StateFlow<List<VocabularyItem>> = _cleanupSuggestions.asStateFlow()

    // Personal shortcuts (trigger phrase -> replacement value, expanded locally after transcription)
    private val _personalShortcuts = MutableStateFlow<Map<String, String>>(emptyMap())
    val personalShortcuts: StateFlow<Map<String, String>> = _personalShortcuts.asStateFlow()

    // Dismissed candidate keys ("word:foo" or "shortcut:my sister's phone number")
    private val _dismissedCandidates = MutableStateFlow<Set<String>>(emptySet())
    val dismissedCandidates: StateFlow<Set<String>> = _dismissedCandidates.asStateFlow()

    // Accessibility setting: Read vocabulary notifications & prompts aloud via TTS
    private val _readNotificationsAloud = MutableStateFlow(true)
    val readNotificationsAloud: StateFlow<Boolean> = _readNotificationsAloud.asStateFlow()

    // Pending learning candidates discovered after dictation sessions
    private val _pendingCandidates = MutableStateFlow<List<LearningCandidate>>(emptyList())
    val pendingCandidates: StateFlow<List<LearningCandidate>> = _pendingCandidates.asStateFlow()

    // Lightweight daily session buffer (resets at local midnight), cross-day waiting list, and Edit auto-fixes
    private var dailyDateKey: String = ""
    private val dailySessions = mutableListOf<String>()
    private val waitingTerms = linkedMapOf<String, Long>() // term -> lastSeenTimestamp
    private val autoCorrections = linkedMapOf<String, String>() // misheard -> corrected

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadTerms()
            loadShortcutsAndSettings()
        }
    }

    internal fun clearForTest() {
        prefs = null
        _vocabulary.value = emptyList()
        _vocabularyItems.value = emptyList()
        _cleanupSuggestions.value = emptyList()
        _personalShortcuts.value = emptyMap()
        _dismissedCandidates.value = emptySet()
        _readNotificationsAloud.value = true
        _pendingCandidates.value = emptyList()
        dailyDateKey = ""
        dailySessions.clear()
        waitingTerms.clear()
        autoCorrections.clear()
    }

    private fun loadShortcutsAndSettings() {
        val sp = prefs ?: return
        _readNotificationsAloud.value = sp.getBoolean(KEY_READ_NOTIFICATIONS_ALOUD, true)

        // Local regex personal shortcuts were removed to prevent mid-sentence mangling;
        // clear any legacy saved entries so old shortcuts never trigger again.
        if (sp.contains(KEY_PERSONAL_SHORTCUTS)) {
            sp.edit().remove(KEY_PERSONAL_SHORTCUTS).apply()
        }
        _personalShortcuts.value = emptyMap()

        val dismissedJson = sp.getString(KEY_DISMISSED_CANDIDATES, null)
        val dismissedSet = linkedSetOf<String>()
        if (!dismissedJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(dismissedJson)
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i, "").trim().lowercase()
                    if (item.isNotEmpty()) dismissedSet.add(item)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing dismissed candidates json", e)
            }
        }
        _dismissedCandidates.value = dismissedSet

        dailyDateKey = sp.getString(KEY_DAILY_DATE, "").orEmpty()
        dailySessions.clear()
        val dailyJson = sp.getString(KEY_DAILY_SESSIONS, null)
        if (!dailyJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(dailyJson)
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i, "").trim()
                    if (item.isNotEmpty()) dailySessions.add(item)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing daily sessions json", e)
            }
        }

        waitingTerms.clear()
        val waitingJson = sp.getString(KEY_WAITING_TERMS, null)
        if (!waitingJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(waitingJson)
                val now = System.currentTimeMillis()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val term = obj.optString("term", "").trim()
                    val ts = obj.optLong("ts", now)
                    if (term.isNotEmpty() && (now - ts) < WAITING_TERM_TTL_MS) {
                        waitingTerms[term] = ts
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing waiting terms json", e)
            }
        }

        autoCorrections.clear()
        val corrJson = sp.getString(KEY_AUTO_CORRECTIONS, null)
        if (!corrJson.isNullOrBlank()) {
            try {
                val obj = JSONObject(corrJson)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val rawKey = keys.next().trim()
                    val value = obj.optString(rawKey, "").trim()
                    if (rawKey.isNotEmpty() && value.isNotEmpty()) {
                        autoCorrections[rawKey] = value
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing auto-corrections json", e)
            }
        }
    }

    private fun saveLearningMemory() {
        val sp = prefs ?: return
        try {
            val dailyArr = JSONArray()
            dailySessions.forEach { dailyArr.put(it) }

            val waitingArr = JSONArray()
            waitingTerms.forEach { (term, ts) ->
                waitingArr.put(JSONObject().put("term", term).put("ts", ts))
            }

            val corrObj = JSONObject()
            autoCorrections.forEach { (wrong, right) ->
                corrObj.put(wrong, right)
            }

            sp.edit()
                .putString(KEY_DAILY_DATE, dailyDateKey)
                .putString(KEY_DAILY_SESSIONS, dailyArr.toString())
                .putString(KEY_WAITING_TERMS, waitingArr.toString())
                .putString(KEY_AUTO_CORRECTIONS, corrObj.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving learning memory", e)
        }
    }

    private fun saveShortcutsAndDismissed() {
        val sp = prefs ?: return
        try {
            val shortcutsObj = JSONObject()
            _personalShortcuts.value.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) {
                    shortcutsObj.put(VocabularyLearningEngine.normalizeTrigger(k), v.trim())
                }
            }
            val dismissedArr = JSONArray()
            _dismissedCandidates.value.forEach { k ->
                if (k.isNotBlank()) dismissedArr.put(k.trim().lowercase())
            }
            sp.edit()
                .putString(KEY_PERSONAL_SHORTCUTS, shortcutsObj.toString())
                .putString(KEY_DISMISSED_CANDIDATES, dismissedArr.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving shortcuts and dismissed candidates", e)
        }
    }

    private fun loadTerms() {
        val sp = prefs ?: return
        val rawJson = sp.getString(KEY_TERMS, null)
        val metaJson = sp.getString(KEY_METADATA, null)

        val termsList = mutableListOf<String>()
        if (!rawJson.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(rawJson)
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optString(i, "").trim()
                    if (item.isNotEmpty() && !termsList.contains(item)) {
                        termsList.add(item)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing custom vocabulary json", e)
            }
        }

        val alreadyRemoved = sp.getBoolean(KEY_SEEDED_IDENTITY_REMOVED, false)
        if (!alreadyRemoved) {
            termsList.removeAll { isSeededIdentity(it) }
        }

        // Parse metadata map
        val metaMap = mutableMapOf<String, VocabularyItem>()
        if (!metaJson.isNullOrBlank()) {
            try {
                val metaObj = JSONObject(metaJson)
                val keys = metaObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = metaObj.getJSONObject(key)
                    val termName = obj.optString("term", key)
                    val added = obj.optLong("addedTimestamp", System.currentTimeMillis())
                    val lastUsed = obj.optLong("lastUsedTimestamp", System.currentTimeMillis())
                    val dismissed = obj.optBoolean("isKeepDismissed", false)
                    metaMap[key.lowercase()] = VocabularyItem(
                        term = termName,
                        addedTimestamp = added,
                        lastUsedTimestamp = lastUsed,
                        isKeepDismissed = dismissed
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing vocabulary metadata", e)
            }
        }

        val itemsList = mutableListOf<VocabularyItem>()
        val now = System.currentTimeMillis()
        termsList.forEach { term ->
            val key = term.lowercase()
            val existing = metaMap[key]
            if (existing != null) {
                itemsList.add(existing)
            } else {
                itemsList.add(
                    VocabularyItem(
                        term = term,
                        addedTimestamp = now,
                        lastUsedTimestamp = now,
                        isKeepDismissed = false
                    )
                )
            }
        }

        if (!alreadyRemoved) {
            itemsList.removeAll { isSeededIdentity(it.term) }
            sp.edit().putBoolean(KEY_SEEDED_IDENTITY_REMOVED, true).apply()
        }

        _vocabulary.value = termsList
        _vocabularyItems.value = itemsList
        recomputeCleanupSuggestions(itemsList)
        saveAll(termsList, itemsList)
    }

    private fun isSeededIdentity(term: String): Boolean {
        return term.trim().lowercase() in SEEDED_IDENTITY
    }

    private fun recomputeCleanupSuggestions(items: List<VocabularyItem>) {
        val thresholdMs = UNUSED_THRESHOLD_DAYS * 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        _cleanupSuggestions.value = items.filter { item ->
            !item.isKeepDismissed && (now - item.lastUsedTimestamp >= thresholdMs)
        }
    }

    private fun saveAll(terms: List<String>, items: List<VocabularyItem>) {
        val sp = prefs ?: return
        try {
            val termsArray = JSONArray()
            terms.forEach { term ->
                if (term.isNotBlank()) termsArray.put(term.trim())
            }

            val metaObj = JSONObject()
            items.forEach { item ->
                val obj = JSONObject().apply {
                    put("term", item.term)
                    put("addedTimestamp", item.addedTimestamp)
                    put("lastUsedTimestamp", item.lastUsedTimestamp)
                    put("isKeepDismissed", item.isKeepDismissed)
                }
                metaObj.put(item.term.lowercase(), obj)
            }

            sp.edit()
                .putString(KEY_TERMS, termsArray.toString())
                .putString(KEY_METADATA, metaObj.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving custom vocabulary data", e)
        }
    }

    fun getVocabulary(): List<String> {
        val items = _vocabularyItems.value
        if (items.isEmpty()) return _vocabulary.value.take(MAX_GEMINI_LIVE_VOCAB_TERMS)
        // Most recently used terms first so active words stay in Gemini Live's high-accuracy top ~100
        return items
            .sortedByDescending { it.lastUsedTimestamp }
            .map { it.term }
            .take(MAX_GEMINI_LIVE_VOCAB_TERMS)
    }

    /**
     * Returns up to [limit] (default 100) most recently used manual dictionary terms for Gemini
     * setup and batch fallback requests, cutting handshake payload size while preserving accuracy.
     */
    fun getTopVocabularyForGemini(limit: Int = MAX_GEMINI_ACTIVE_VOCAB_TERMS): List<String> {
        return getVocabulary().take(limit.coerceAtLeast(0))
    }

    fun getPersonalShortcuts(): Map<String, String> {
        return _personalShortcuts.value
    }

    fun getDailySessionsForDate(dateKey: String): List<String> {
        if (dailyDateKey != dateKey) {
            dailyDateKey = dateKey
            dailySessions.clear()
            saveLearningMemory()
        }
        return dailySessions.toList()
    }

    fun recordDailySession(dateKey: String, transcript: String) {
        val snippet = transcript.trim().replace(Regex("\\s+"), " ").take(MAX_DAILY_SESSION_CHARS)
        if (snippet.isEmpty()) return
        if (dailyDateKey != dateKey) {
            dailyDateKey = dateKey
            dailySessions.clear()
        }
        dailySessions.add(snippet)
        while (dailySessions.size > MAX_DAILY_SESSIONS) {
            dailySessions.removeAt(0)
        }
        saveLearningMemory()
    }

    fun getWaitingTerms(nowMs: Long = System.currentTimeMillis()): List<String> {
        val iterator = waitingTerms.entries.iterator()
        var pruned = false
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value >= WAITING_TERM_TTL_MS) {
                iterator.remove()
                pruned = true
            }
        }
        if (pruned) saveLearningMemory()
        return waitingTerms.keys.toList()
    }

    fun updateWaitingTerms(
        addTerms: Collection<String> = emptyList(),
        removeTerms: Collection<String> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ) {
        var changed = false
        if (removeTerms.isNotEmpty()) {
            val removeLower = removeTerms.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            val it = waitingTerms.entries.iterator()
            while (it.hasNext()) {
                if (it.next().key.lowercase() in removeLower) {
                    it.remove()
                    changed = true
                }
            }
        }
        for (raw in addTerms) {
            val clean = raw.trim()
            if (clean.length < 2) continue
            val existingKey = waitingTerms.keys.firstOrNull { it.equals(clean, ignoreCase = true) }
            if (existingKey != null) {
                waitingTerms.remove(existingKey)
            }
            waitingTerms[clean] = nowMs
            changed = true
        }
        while (waitingTerms.size > MAX_WAITING_TERMS) {
            val oldestKey = waitingTerms.keys.firstOrNull() ?: break
            waitingTerms.remove(oldestKey)
            changed = true
        }
        if (changed) saveLearningMemory()
    }

    fun getAutoCorrections(): Map<String, String> = autoCorrections.toMap()

    fun addAutoCorrection(wrongForm: String, rightForm: String) {
        val wrong = wrongForm.trim()
        val right = rightForm.trim()
        if (wrong.isEmpty() || right.isEmpty() || wrong.equals(right, ignoreCase = true)) return
        if (!VocabularyLearningEngine.isSafeAutoCorrectionKey(wrong)) return
        val existingKey = autoCorrections.keys.firstOrNull { it.equals(wrong, ignoreCase = true) }
        if (existingKey != null) autoCorrections.remove(existingKey)
        autoCorrections[wrong] = right
        while (autoCorrections.size > MAX_AUTO_CORRECTIONS) {
            val oldest = autoCorrections.keys.firstOrNull() ?: break
            autoCorrections.remove(oldest)
        }
        saveLearningMemory()
    }

    fun getDismissedCandidates(): Set<String> {
        return _dismissedCandidates.value
    }

    fun isReadNotificationsAloud(): Boolean {
        return _readNotificationsAloud.value
    }

    fun setReadNotificationsAloud(enabled: Boolean) {
        _readNotificationsAloud.value = enabled
        prefs?.edit()?.putBoolean(KEY_READ_NOTIFICATIONS_ALOUD, enabled)?.apply()
    }

    fun addOrUpdateShortcut(triggerPhrase: String, replacementValue: String): Boolean {
        val normTrigger = VocabularyLearningEngine.normalizeTrigger(triggerPhrase)
        val normValue = VocabularyLearningEngine.normalizeShortcutValue(replacementValue)
        if (normTrigger.isBlank() || normValue.isBlank()) return false

        val updated = LinkedHashMap(_personalShortcuts.value)
        updated[normTrigger] = normValue
        _personalShortcuts.value = updated
        saveShortcutsAndDismissed()
        return true
    }

    fun deleteShortcut(triggerPhrase: String): Boolean {
        val normTrigger = VocabularyLearningEngine.normalizeTrigger(triggerPhrase)
        val updated = LinkedHashMap(_personalShortcuts.value)
        val removed = updated.remove(normTrigger) != null
        if (removed) {
            _personalShortcuts.value = updated
            saveShortcutsAndDismissed()
        }
        return removed
    }

    fun dismissCandidate(dedupeKeyOrTerm: String) {
        val key = dedupeKeyOrTerm.trim().lowercase()
        if (key.isEmpty()) return
        val updated = LinkedHashSet(_dismissedCandidates.value)
        updated.add(key)
        _dismissedCandidates.value = updated
        _pendingCandidates.value = _pendingCandidates.value.filterNot {
            it.id.equals(key, ignoreCase = true) || it.dedupeKey.equals(key, ignoreCase = true)
        }
        saveShortcutsAndDismissed()
    }

    fun isCandidateDismissed(dedupeKeyOrTerm: String): Boolean {
        val key = dedupeKeyOrTerm.trim().lowercase()
        return key in _dismissedCandidates.value
    }

    fun enqueueCandidates(candidates: List<LearningCandidate>) {
        if (candidates.isEmpty()) return
        val current = _pendingCandidates.value.toMutableList()
        for (c in candidates) {
            if (current.none { it.id.equals(c.id, ignoreCase = true) }) {
                current.add(c)
            }
        }
        _pendingCandidates.value = current
    }

    fun getCandidateById(candidateId: String?): LearningCandidate? {
        val current = _pendingCandidates.value
        if (candidateId.isNullOrBlank()) return current.firstOrNull()
        return current.firstOrNull { it.id.equals(candidateId.trim(), ignoreCase = true) }
            ?: current.firstOrNull()
    }

    fun formatLabeledVocabularyEntry(triggerPhrase: String, value: String): String {
        val normTrigger = VocabularyLearningEngine.normalizeTrigger(triggerPhrase)
        val normValue = VocabularyLearningEngine.normalizeShortcutValue(value)
        return when {
            normTrigger.isBlank() -> normValue
            normValue.isBlank() -> normTrigger
            else -> "$normTrigger: $normValue"
        }
    }

    fun approveCandidate(candidateId: String?, editedValue: String? = null): Boolean {
        val target = getCandidateById(candidateId) ?: return false
        val cleanOverride = editedValue?.trim().takeUnless { it.isNullOrEmpty() }
        val originalValue = target.editableText.trim()
        val finalValue = cleanOverride ?: originalValue
        val saved = when (target) {
            is LearningCandidate.CustomWord -> {
                addTerm(finalValue)
            }
            is LearningCandidate.Shortcut -> {
                // Save personal phone numbers/emails directly inside Custom Vocabulary as
                // a single labeled entry (e.g. "my phone number: 0550526553") instead of
                // running local regex replacement on transcripts.
                val labeledEntry = formatLabeledVocabularyEntry(target.triggerPhrase, finalValue)
                addTerm(labeledEntry)
            }
        }
        val toClear = mutableListOf(originalValue, finalValue)
        target.matchedWaitingTerm?.let { toClear.add(it) }
        updateWaitingTerms(removeTerms = toClear)
        if (cleanOverride != null && !cleanOverride.equals(originalValue, ignoreCase = true)) {
            // Remember misheard -> corrected locally and ensure old spelling is not re-suggested
            addAutoCorrection(originalValue, cleanOverride)
            dismissCandidate("word:${originalValue.lowercase()}")
        }
        _pendingCandidates.value = _pendingCandidates.value.filterNot {
            it.id.equals(target.id, ignoreCase = true)
        }
        return saved
    }

    fun rejectCandidate(candidateId: String?) {
        val target = getCandidateById(candidateId)
        if (target != null) {
            dismissCandidate(target.dedupeKey)
            val toClear = mutableListOf(target.editableText)
            target.matchedWaitingTerm?.let {
                toClear.add(it)
                dismissCandidate("word:${it.trim().lowercase()}")
            }
            updateWaitingTerms(removeTerms = toClear)
        } else if (!candidateId.isNullOrBlank()) {
            dismissCandidate(candidateId)
        }
    }

    fun expandShortcutsInTranscript(text: String): String {
        // Local personal shortcut regex replacement is intentionally removed;
        // only apply explicit user-edited spelling auto-corrections.
        return VocabularyLearningEngine.applyAutoCorrections(text, autoCorrections)
    }

    fun recordUsageFromTranscript(transcriptText: String) {
        if (transcriptText.isBlank()) return
        val currentItems = _vocabularyItems.value.toMutableList()
        val now = System.currentTimeMillis()
        var updated = false

        currentItems.indices.forEach { idx ->
            val item = currentItems[idx]
            if (containsWordOrPhrase(transcriptText, item.term)) {
                currentItems[idx] = item.copy(
                    lastUsedTimestamp = now,
                    isKeepDismissed = false
                )
                updated = true
            }
        }

        if (updated) {
            _vocabularyItems.value = currentItems
            recomputeCleanupSuggestions(currentItems)
            saveAll(_vocabulary.value, currentItems)
        }
    }

    private fun containsWordOrPhrase(text: String, term: String): Boolean {
        if (text.isBlank() || term.isBlank()) return false
        val trimmedTerm = term.trim()
        val regex = ("\\b" + Regex.escape(trimmedTerm) + "\\b").toRegex(RegexOption.IGNORE_CASE)
        return regex.containsMatchIn(text) || text.contains(trimmedTerm, ignoreCase = true)
    }

    fun addTerm(term: String): Boolean {
        val trimmed = term.trim()
        if (trimmed.isBlank()) return false
        val currentTerms = _vocabulary.value.toMutableList()
        if (currentTerms.any { it.equals(trimmed, ignoreCase = true) }) {
            return false // Duplicate
        }

        val now = System.currentTimeMillis()
        val newItem = VocabularyItem(
            term = trimmed,
            addedTimestamp = now,
            lastUsedTimestamp = now,
            isKeepDismissed = false
        )

        currentTerms.add(0, trimmed)
        val currentItems = _vocabularyItems.value.toMutableList()
        currentItems.add(0, newItem)

        _vocabulary.value = currentTerms
        _vocabularyItems.value = currentItems
        recomputeCleanupSuggestions(currentItems)
        saveAll(currentTerms, currentItems)
        return true
    }

    fun editTerm(oldTerm: String, newTerm: String): Boolean {
        val trimmedNew = newTerm.trim()
        if (trimmedNew.isBlank()) return false
        val currentTerms = _vocabulary.value.toMutableList()
        val index = currentTerms.indexOfFirst { it.equals(oldTerm, ignoreCase = true) }
        if (index == -1) return false

        currentTerms[index] = trimmedNew
        val currentItems = _vocabularyItems.value.toMutableList()
        val itemIndex = currentItems.indexOfFirst { it.term.equals(oldTerm, ignoreCase = true) }
        if (itemIndex != -1) {
            val oldItem = currentItems[itemIndex]
            currentItems[itemIndex] = oldItem.copy(term = trimmedNew)
        }

        _vocabulary.value = currentTerms
        _vocabularyItems.value = currentItems
        recomputeCleanupSuggestions(currentItems)
        saveAll(currentTerms, currentItems)
        return true
    }

    fun keepTerm(term: String): Boolean {
        val currentItems = _vocabularyItems.value.toMutableList()
        val index = currentItems.indexOfFirst { it.term.equals(term, ignoreCase = true) }
        if (index == -1) return false

        currentItems[index] = currentItems[index].copy(isKeepDismissed = true)
        _vocabularyItems.value = currentItems
        recomputeCleanupSuggestions(currentItems)
        saveAll(_vocabulary.value, currentItems)
        return true
    }

    fun deleteTerm(term: String): Boolean {
        val currentTerms = _vocabulary.value.toMutableList()
        val removedTerm = currentTerms.removeAll { it.equals(term, ignoreCase = true) }

        val currentItems = _vocabularyItems.value.toMutableList()
        currentItems.removeAll { it.term.equals(term, ignoreCase = true) }

        if (removedTerm) {
            _vocabulary.value = currentTerms
            _vocabularyItems.value = currentItems
            recomputeCleanupSuggestions(currentItems)
            saveAll(currentTerms, currentItems)
        }
        return removedTerm
    }

    fun clearAll() {
        _vocabulary.value = emptyList()
        _vocabularyItems.value = emptyList()
        _cleanupSuggestions.value = emptyList()
        _personalShortcuts.value = emptyMap()
        _dismissedCandidates.value = emptySet()
        _pendingCandidates.value = emptyList()
        dailyDateKey = ""
        dailySessions.clear()
        waitingTerms.clear()
        autoCorrections.clear()
        saveAll(emptyList(), emptyList())
        saveShortcutsAndDismissed()
        saveLearningMemory()
    }
}
