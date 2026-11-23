package com.princeniithompson.rapcha.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ModuleItem(
    val id: String,
    val name: String,
    val query: String,
    val isBuiltIn: Boolean,
    val isConfigurable: Boolean = false,
    val enabled: Boolean = true,
    val iconEmoji: String = "",
    val iconKey: String = "",
    val iconColorKey: String = ""
)

data class ToneItem(
    val id: String,
    val name: String,
    val instruction: String,
    val isDefault: Boolean,
    val enabled: Boolean = true,
    val iconEmoji: String = "",
    val iconKey: String = "",
    val iconColorKey: String = ""
) {
    val displayLabel: String
        get() = if (iconEmoji.isNotBlank()) "$iconEmoji $name" else name
}

data class TargetLanguageItem(
    val id: String,
    val name: String,
    val instruction: String = "",
    val isDefault: Boolean = false,
    val enabled: Boolean = true
)

/**
 * Local SharedPreferences repository for floating action Modules, Tones, and Target Languages.
 * Preserves existing tone keys and adds persisted display-sequence ordering, custom modules/languages,
 * and optional emoji/vector icon badges.
 */
object ToneRepository {
    private const val PREFS_NAME = "rapcha_tones_prefs"
    private const val KEY_DISABLED_DEFAULTS = "disabled_default_tone_ids"
    private const val KEY_CUSTOM_TONES_JSON = "custom_tones_json"
    private const val KEY_TONE_ORDER_JSON = "tone_order_ids_json"
    private const val KEY_DISABLED_MODULES = "disabled_module_ids"
    private const val KEY_CUSTOM_MODULES_JSON = "custom_modules_json"
    private const val KEY_MODULE_ORDER_JSON = "module_order_ids_json"
    private const val KEY_DISABLED_LANGUAGES = "disabled_language_ids"
    private const val KEY_CUSTOM_LANGUAGES_JSON = "custom_languages_json"
    private const val KEY_LANGUAGE_ORDER_JSON = "language_order_ids_json"
    private const val KEY_LAST_REPLY_LENGTH = "last_reply_length"
    private const val KEY_LAST_TRANSLATE_LANG = "last_translate_lang"

    const val MAX_NAME_LENGTH = 19
    const val MAX_TONE_NAME_LENGTH = MAX_NAME_LENGTH
    const val MAX_QUERY_LENGTH = 500
    const val DEFAULT_REPLY_LENGTH = "Short"
    const val DEFAULT_TRANSLATE_LANGUAGE = "English"

    const val MODULE_GRAMMAR = "builtin_grammar"
    const val MODULE_TRANSLATE = "builtin_translate"
    const val MODULE_REPLY = "builtin_reply"
    const val MODULE_TONE = "builtin_tone"

    val DEFAULT_MODULES: List<ModuleItem> = listOf(
        ModuleItem(
            id = MODULE_GRAMMAR,
            name = "Grammar",
            query = "GRAMMAR",
            isBuiltIn = true,
            isConfigurable = false
        ),
        ModuleItem(
            id = MODULE_TRANSLATE,
            name = "Translate",
            query = "TRANSLATE",
            isBuiltIn = true,
            isConfigurable = true
        ),
        ModuleItem(
            id = MODULE_REPLY,
            name = "Reply",
            query = "REPLY",
            isBuiltIn = true,
            isConfigurable = false
        ),
        ModuleItem(
            id = MODULE_TONE,
            name = "Tone",
            query = "TONE",
            isBuiltIn = true,
            isConfigurable = true
        )
    )

    val DEFAULT_TONES: List<ToneItem> = listOf(
        ToneItem(
            id = "default_professional",
            name = "Professional",
            instruction = "Rewrite the text in a clear, polished, professional tone suitable for work or business.",
            isDefault = true,
            iconEmoji = "\uD83D\uDCBC" // 💼
        ),
        ToneItem(
            id = "default_romantic",
            name = "Romantic",
            instruction = "Rewrite the text in a warm, affectionate, romantic tone.",
            isDefault = true,
            iconEmoji = "\uD83C\uDF39" // 🌹
        ),
        ToneItem(
            id = "default_friendly",
            name = "Friendly",
            instruction = "Rewrite the text in a warm, casual, approachable, and friendly tone.",
            isDefault = true,
            iconEmoji = "\uD83D\uDE42" // 🙂
        ),
        ToneItem(
            id = "default_funny",
            name = "Funny",
            instruction = "Rewrite the text in a witty, lighthearted, and funny tone.",
            isDefault = true,
            iconEmoji = "\uD83D\uDE02" // 😂
        ),
        ToneItem(
            id = "default_social",
            name = "Social",
            instruction = "Rewrite the text in an engaging, natural social-media style.",
            isDefault = true,
            iconEmoji = "\uD83D\uDE03" // 😃
        ),
        ToneItem(
            id = "default_sarcastic",
            name = "Sarcastic",
            instruction = "Rewrite the text in a clever, dry, playfully sarcastic tone.",
            isDefault = true,
            iconEmoji = "\uD83D\uDE44" // 🙄
        ),
        ToneItem(
            id = "default_direct",
            name = "Direct",
            instruction = "Rewrite the text so it is concise, clear, and straight to the point.",
            isDefault = true,
            iconEmoji = "\uD83C\uDFAF" // 🎯
        )
    )

    val DEFAULT_LANGUAGES: List<TargetLanguageItem> = listOf(
        TargetLanguageItem(
            id = "lang_english",
            name = "\uD83C\uDDEC\uD83C\uDDE7 English",
            isDefault = true
        ),
        TargetLanguageItem(
            id = "lang_spanish",
            name = "\uD83C\uDDEA\uD83C\uDDF8 Spanish",
            isDefault = true
        ),
        TargetLanguageItem(
            id = "lang_french",
            name = "\uD83C\uDDEB\uD83C\uDDF7 French",
            isDefault = true
        ),
        TargetLanguageItem(
            id = "lang_german",
            name = "\uD83C\uDDE9\uD83C\uDDEA German",
            isDefault = true
        ),
        TargetLanguageItem(
            id = "lang_italian",
            name = "\uD83C\uDDEE\uD83C\uDDF9 Italian",
            isDefault = true
        )
    )

    private var prefs: SharedPreferences? = null
    private val lock = Any()

    private val _modules = MutableStateFlow<List<ModuleItem>>(DEFAULT_MODULES)
    val modules: StateFlow<List<ModuleItem>> = _modules.asStateFlow()

    private val _tones = MutableStateFlow<List<ToneItem>>(DEFAULT_TONES)
    val tones: StateFlow<List<ToneItem>> = _tones.asStateFlow()

    private val _languages = MutableStateFlow<List<TargetLanguageItem>>(DEFAULT_LANGUAGES)
    val languages: StateFlow<List<TargetLanguageItem>> = _languages.asStateFlow()

    private val _lastReplyLength = MutableStateFlow(DEFAULT_REPLY_LENGTH)
    val lastReplyLength: StateFlow<String> = _lastReplyLength.asStateFlow()

    private val _lastTranslateLanguage = MutableStateFlow(DEFAULT_TRANSLATE_LANGUAGE)
    val lastTranslateLanguage: StateFlow<String> = _lastTranslateLanguage.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = sp
            reloadLocked(sp)
        }
    }

    internal fun resetForTests() {
        synchronized(lock) {
            prefs = null
            _modules.value = DEFAULT_MODULES
            _tones.value = DEFAULT_TONES
            _languages.value = DEFAULT_LANGUAGES
            _lastReplyLength.value = DEFAULT_REPLY_LENGTH
            _lastTranslateLanguage.value = DEFAULT_TRANSLATE_LANGUAGE
        }
    }

    private fun reloadLocked(sp: SharedPreferences) {
        val disabledModules = sp.getStringSet(KEY_DISABLED_MODULES, emptySet()).orEmpty()
        val builtInModules = DEFAULT_MODULES.map { it.copy(enabled = !disabledModules.contains(it.id)) }
        val customModules = parseCustomModules(sp.getString(KEY_CUSTOM_MODULES_JSON, null))
        val moduleOrder = parseIdList(sp.getString(KEY_MODULE_ORDER_JSON, null))
        _modules.value = applySavedOrder(builtInModules + customModules, moduleOrder) { it.id }

        val disabledDefaults = sp.getStringSet(KEY_DISABLED_DEFAULTS, emptySet()).orEmpty()
        val defaults = DEFAULT_TONES.map { tone ->
            tone.copy(enabled = !disabledDefaults.contains(tone.id))
        }
        val customs = parseCustomTones(sp.getString(KEY_CUSTOM_TONES_JSON, null))
        val toneOrder = parseIdList(sp.getString(KEY_TONE_ORDER_JSON, null))
        _tones.value = applySavedOrder(defaults + customs, toneOrder) { it.id }

        val disabledLanguages = sp.getStringSet(KEY_DISABLED_LANGUAGES, emptySet()).orEmpty()
        val defaultLangs = DEFAULT_LANGUAGES.map { lang ->
            lang.copy(enabled = !disabledLanguages.contains(lang.id))
        }
        val customLangs = parseCustomLanguages(sp.getString(KEY_CUSTOM_LANGUAGES_JSON, null))
        val langOrder = parseIdList(sp.getString(KEY_LANGUAGE_ORDER_JSON, null))
        _languages.value = applySavedOrder(defaultLangs + customLangs, langOrder) { it.id }

        _lastReplyLength.value = sp.getString(KEY_LAST_REPLY_LENGTH, DEFAULT_REPLY_LENGTH)
            ?.takeIf { it == "Short" || it == "Medium" || it == "Long" || it == "No Reply" }
            ?: DEFAULT_REPLY_LENGTH
        _lastTranslateLanguage.value = sp.getString(KEY_LAST_TRANSLATE_LANG, DEFAULT_TRANSLATE_LANGUAGE)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TRANSLATE_LANGUAGE
    }

    fun getEnabledModules(): List<ModuleItem> = _modules.value.filter { it.enabled }

    fun getEnabledTones(): List<ToneItem> = _tones.value.filter { it.enabled }

    fun getEnabledLanguages(): List<TargetLanguageItem> = _languages.value.filter { it.enabled }

    fun sanitizeName(raw: String): String {
        return raw.replace("\n", " ").replace("\r", " ").trim().take(MAX_NAME_LENGTH)
    }

    fun sanitizeQuery(raw: String): String {
        return raw.trim().take(MAX_QUERY_LENGTH)
    }

    private fun sanitizeBadge(raw: String): String = raw.trim().take(12)

    // -------------------------------------------------------------------------
    // Modules CRUD + Order
    // -------------------------------------------------------------------------

    fun isModuleNameDuplicate(name: String, excludeId: String? = null): Boolean {
        val clean = sanitizeName(name)
        if (clean.isEmpty()) return false
        return _modules.value.any { it.id != excludeId && it.name.equals(clean, ignoreCase = true) }
    }

    fun setModuleEnabled(moduleId: String, enabled: Boolean) {
        synchronized(lock) {
            val updated = _modules.value.map { m ->
                if (m.id == moduleId) m.copy(enabled = enabled) else m
            }
            _modules.value = updated
            persistModulesLocked(updated)
        }
    }

    fun addCustomModule(
        name: String,
        query: String,
        iconEmoji: String = "",
        iconKey: String = "",
        iconColorKey: String = ""
    ): Boolean {
        val cleanName = sanitizeName(name)
        val cleanQuery = sanitizeQuery(query)
        if (cleanName.isBlank() || cleanQuery.isBlank()) return false
        synchronized(lock) {
            if (_modules.value.any { it.name.equals(cleanName, ignoreCase = true) }) {
                return false
            }
            val item = ModuleItem(
                id = "custom_mod_${UUID.randomUUID().toString().take(8)}",
                name = cleanName,
                query = cleanQuery,
                isBuiltIn = false,
                isConfigurable = false,
                enabled = true,
                iconEmoji = sanitizeBadge(iconEmoji),
                iconKey = iconKey.trim().take(32),
                iconColorKey = iconColorKey.trim().take(24)
            )
            val updated = _modules.value + item
            _modules.value = updated
            persistModulesLocked(updated)
            return true
        }
    }

    fun editCustomModule(
        moduleId: String,
        name: String,
        query: String,
        iconEmoji: String = "",
        iconKey: String = "",
        iconColorKey: String = ""
    ): Boolean {
        val cleanName = sanitizeName(name)
        val cleanQuery = sanitizeQuery(query)
        if (cleanName.isBlank() || cleanQuery.isBlank()) return false
        synchronized(lock) {
            val target = _modules.value.firstOrNull { it.id == moduleId && !it.isBuiltIn } ?: return false
            if (_modules.value.any { it.id != target.id && it.name.equals(cleanName, ignoreCase = true) }) {
                return false
            }
            val updated = _modules.value.map { m ->
                if (m.id == moduleId) {
                    m.copy(
                        name = cleanName,
                        query = cleanQuery,
                        iconEmoji = sanitizeBadge(iconEmoji),
                        iconKey = iconKey.trim().take(32),
                        iconColorKey = iconColorKey.trim().take(24)
                    )
                } else {
                    m
                }
            }
            _modules.value = updated
            persistModulesLocked(updated)
            return true
        }
    }

    fun deleteCustomModule(moduleId: String): Boolean {
        synchronized(lock) {
            val before = _modules.value
            val updated = before.filterNot { it.id == moduleId && !it.isBuiltIn }
            if (updated.size == before.size) return false
            _modules.value = updated
            persistModulesLocked(updated)
            return true
        }
    }

    fun reorderModules(orderedIds: List<String>) {
        synchronized(lock) {
            val updated = applySavedOrder(_modules.value, orderedIds) { it.id }
            _modules.value = updated
            persistModulesLocked(updated)
        }
    }

    // -------------------------------------------------------------------------
    // Tones CRUD + Order
    // -------------------------------------------------------------------------

    fun isToneNameDuplicate(name: String, excludeId: String? = null): Boolean {
        val clean = sanitizeName(name)
        if (clean.isEmpty()) return false
        return _tones.value.any { it.id != excludeId && it.name.equals(clean, ignoreCase = true) }
    }

    fun setToneEnabled(toneId: String, enabled: Boolean) {
        synchronized(lock) {
            val updated = _tones.value.map { tone ->
                if (tone.id == toneId) tone.copy(enabled = enabled) else tone
            }
            _tones.value = updated
            persistTonesLocked(updated)
        }
    }

    fun addCustomTone(
        name: String,
        instruction: String,
        iconEmoji: String = "",
        iconKey: String = "",
        iconColorKey: String = ""
    ): Boolean {
        val cleanName = sanitizeName(name)
        val cleanInstruction = sanitizeQuery(instruction)
        if (cleanName.isBlank() || cleanInstruction.isBlank()) return false
        synchronized(lock) {
            if (_tones.value.any { it.name.equals(cleanName, ignoreCase = true) }) {
                return false
            }
            val item = ToneItem(
                id = "custom_${UUID.randomUUID().toString().take(8)}",
                name = cleanName,
                instruction = cleanInstruction,
                isDefault = false,
                enabled = true,
                iconEmoji = sanitizeBadge(iconEmoji),
                iconKey = iconKey.trim().take(32),
                iconColorKey = iconColorKey.trim().take(24)
            )
            val updated = _tones.value + item
            _tones.value = updated
            persistTonesLocked(updated)
            return true
        }
    }

    fun editCustomTone(
        toneId: String,
        name: String,
        instruction: String,
        iconEmoji: String = "",
        iconKey: String = "",
        iconColorKey: String = ""
    ): Boolean {
        val cleanName = sanitizeName(name)
        val cleanInstruction = sanitizeQuery(instruction)
        if (cleanName.isBlank() || cleanInstruction.isBlank()) return false
        synchronized(lock) {
            val target = _tones.value.firstOrNull { it.id == toneId && !it.isDefault } ?: return false
            if (_tones.value.any { it.id != target.id && it.name.equals(cleanName, ignoreCase = true) }) {
                return false
            }
            val updated = _tones.value.map { tone ->
                if (tone.id == toneId) {
                    tone.copy(
                        name = cleanName,
                        instruction = cleanInstruction,
                        iconEmoji = sanitizeBadge(iconEmoji),
                        iconKey = iconKey.trim().take(32),
                        iconColorKey = iconColorKey.trim().take(24)
                    )
                } else {
                    tone
                }
            }
            _tones.value = updated
            persistTonesLocked(updated)
            return true
        }
    }

    fun deleteCustomTone(toneId: String): Boolean {
        synchronized(lock) {
            val before = _tones.value
            val updated = before.filterNot { it.id == toneId && !it.isDefault }
            if (updated.size == before.size) return false
            _tones.value = updated
            persistTonesLocked(updated)
            return true
        }
    }

    fun reorderTones(orderedIds: List<String>) {
        synchronized(lock) {
            val updated = applySavedOrder(_tones.value, orderedIds) { it.id }
            _tones.value = updated
            persistTonesLocked(updated)
        }
    }

    // -------------------------------------------------------------------------
    // Target Languages CRUD + Order
    // -------------------------------------------------------------------------

    fun isLanguageNameDuplicate(name: String, excludeId: String? = null): Boolean {
        val clean = sanitizeName(name)
        if (clean.isEmpty()) return false
        return _languages.value.any { it.id != excludeId && it.name.equals(clean, ignoreCase = true) }
    }

    fun setLanguageEnabled(languageId: String, enabled: Boolean) {
        synchronized(lock) {
            val updated = _languages.value.map { lang ->
                if (lang.id == languageId) lang.copy(enabled = enabled) else lang
            }
            _languages.value = updated
            persistLanguagesLocked(updated)
        }
    }

    fun addCustomLanguage(name: String, instruction: String = ""): Boolean {
        val cleanName = sanitizeName(name)
        val cleanInstruction = sanitizeQuery(instruction)
        if (cleanName.isBlank()) return false
        synchronized(lock) {
            if (_languages.value.any { it.name.equals(cleanName, ignoreCase = true) }) {
                return false
            }
            val item = TargetLanguageItem(
                id = "custom_lang_${UUID.randomUUID().toString().take(8)}",
                name = cleanName,
                instruction = cleanInstruction,
                isDefault = false,
                enabled = true
            )
            val updated = _languages.value + item
            _languages.value = updated
            persistLanguagesLocked(updated)
            return true
        }
    }

    fun editCustomLanguage(languageId: String, name: String, instruction: String = ""): Boolean {
        val cleanName = sanitizeName(name)
        val cleanInstruction = sanitizeQuery(instruction)
        if (cleanName.isBlank()) return false
        synchronized(lock) {
            val target = _languages.value.firstOrNull { it.id == languageId && !it.isDefault } ?: return false
            if (_languages.value.any { it.id != target.id && it.name.equals(cleanName, ignoreCase = true) }) {
                return false
            }
            val updated = _languages.value.map { lang ->
                if (lang.id == languageId) lang.copy(name = cleanName, instruction = cleanInstruction) else lang
            }
            _languages.value = updated
            persistLanguagesLocked(updated)
            return true
        }
    }

    fun deleteCustomLanguage(languageId: String): Boolean {
        synchronized(lock) {
            val before = _languages.value
            val updated = before.filterNot { it.id == languageId && !it.isDefault }
            if (updated.size == before.size) return false
            _languages.value = updated
            persistLanguagesLocked(updated)
            return true
        }
    }

    fun reorderLanguages(orderedIds: List<String>) {
        synchronized(lock) {
            val updated = applySavedOrder(_languages.value, orderedIds) { it.id }
            _languages.value = updated
            persistLanguagesLocked(updated)
        }
    }

    // -------------------------------------------------------------------------
    // Preferences
    // -------------------------------------------------------------------------

    fun setLastReplyLength(length: String) {
        if (length != "Short" && length != "Medium" && length != "Long" && length != "No Reply") return
        synchronized(lock) {
            _lastReplyLength.value = length
            prefs?.edit()?.putString(KEY_LAST_REPLY_LENGTH, length)?.apply()
        }
    }

    fun setLastTranslateLanguage(languageName: String) {
        val clean = languageName.trim()
        if (clean.isEmpty()) return
        synchronized(lock) {
            _lastTranslateLanguage.value = clean
            prefs?.edit()?.putString(KEY_LAST_TRANSLATE_LANG, clean)?.apply()
        }
    }

    // -------------------------------------------------------------------------
    // Persistence helpers
    // -------------------------------------------------------------------------

    private fun persistModulesLocked(allModules: List<ModuleItem>) {
        val sp = prefs ?: return
        val disabledBuiltIns = allModules.filter { it.isBuiltIn && !it.enabled }.map { it.id }.toSet()
        val customArray = JSONArray()
        allModules.filter { !it.isBuiltIn }.forEach { mod ->
            customArray.put(
                JSONObject().apply {
                    put("id", mod.id)
                    put("name", mod.name)
                    put("query", mod.query)
                    put("enabled", mod.enabled)
                    if (mod.iconEmoji.isNotEmpty()) put("iconEmoji", mod.iconEmoji)
                    if (mod.iconKey.isNotEmpty()) put("iconKey", mod.iconKey)
                    if (mod.iconColorKey.isNotEmpty()) put("iconColorKey", mod.iconColorKey)
                }
            )
        }
        sp.edit()
            .putStringSet(KEY_DISABLED_MODULES, disabledBuiltIns)
            .putString(KEY_CUSTOM_MODULES_JSON, customArray.toString())
            .putString(KEY_MODULE_ORDER_JSON, encodeIdList(allModules.map { it.id }))
            .apply()
    }

    private fun persistTonesLocked(allTones: List<ToneItem>) {
        val sp = prefs ?: return
        val disabledDefaults = allTones
            .filter { it.isDefault && !it.enabled }
            .map { it.id }
            .toSet()
        val customArray = JSONArray()
        allTones.filter { !it.isDefault }.forEach { tone ->
            customArray.put(
                JSONObject().apply {
                    put("id", tone.id)
                    put("name", tone.name)
                    put("instruction", tone.instruction)
                    put("enabled", tone.enabled)
                    if (tone.iconEmoji.isNotEmpty()) put("iconEmoji", tone.iconEmoji)
                    if (tone.iconKey.isNotEmpty()) put("iconKey", tone.iconKey)
                    if (tone.iconColorKey.isNotEmpty()) put("iconColorKey", tone.iconColorKey)
                }
            )
        }
        sp.edit()
            .putStringSet(KEY_DISABLED_DEFAULTS, disabledDefaults)
            .putString(KEY_CUSTOM_TONES_JSON, customArray.toString())
            .putString(KEY_TONE_ORDER_JSON, encodeIdList(allTones.map { it.id }))
            .apply()
    }

    private fun persistLanguagesLocked(allLanguages: List<TargetLanguageItem>) {
        val sp = prefs ?: return
        val disabledDefaults = allLanguages
            .filter { it.isDefault && !it.enabled }
            .map { it.id }
            .toSet()
        val customArray = JSONArray()
        allLanguages.filter { !it.isDefault }.forEach { lang ->
            customArray.put(
                JSONObject().apply {
                    put("id", lang.id)
                    put("name", lang.name)
                    put("instruction", lang.instruction)
                    put("enabled", lang.enabled)
                }
            )
        }
        sp.edit()
            .putStringSet(KEY_DISABLED_LANGUAGES, disabledDefaults)
            .putString(KEY_CUSTOM_LANGUAGES_JSON, customArray.toString())
            .putString(KEY_LANGUAGE_ORDER_JSON, encodeIdList(allLanguages.map { it.id }))
            .apply()
    }

    private fun parseCustomModules(rawJson: String?): List<ModuleItem> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            val result = ArrayList<ModuleItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val name = sanitizeName(obj.optString("name"))
                val query = sanitizeQuery(obj.optString("query"))
                val enabled = obj.optBoolean("enabled", true)
                val iconEmoji = sanitizeBadge(obj.optString("iconEmoji"))
                val iconKey = obj.optString("iconKey").trim()
                val iconColorKey = obj.optString("iconColorKey").trim()
                if (id.isNotEmpty() && name.isNotEmpty() && query.isNotEmpty()) {
                    result.add(
                        ModuleItem(
                            id = id,
                            name = name,
                            query = query,
                            isBuiltIn = false,
                            isConfigurable = false,
                            enabled = enabled,
                            iconEmoji = iconEmoji,
                            iconKey = iconKey,
                            iconColorKey = iconColorKey
                        )
                    )
                }
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parseCustomTones(rawJson: String?): List<ToneItem> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            val result = ArrayList<ToneItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val name = sanitizeName(obj.optString("name"))
                val instruction = sanitizeQuery(obj.optString("instruction"))
                val enabled = obj.optBoolean("enabled", true)
                val iconEmoji = sanitizeBadge(obj.optString("iconEmoji"))
                val iconKey = obj.optString("iconKey").trim()
                val iconColorKey = obj.optString("iconColorKey").trim()
                if (id.isNotEmpty() && name.isNotEmpty() && instruction.isNotEmpty()) {
                    result.add(
                        ToneItem(
                            id = id,
                            name = name,
                            instruction = instruction,
                            isDefault = false,
                            enabled = enabled,
                            iconEmoji = iconEmoji,
                            iconKey = iconKey,
                            iconColorKey = iconColorKey
                        )
                    )
                }
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parseCustomLanguages(rawJson: String?): List<TargetLanguageItem> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            val result = ArrayList<TargetLanguageItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val name = sanitizeName(obj.optString("name"))
                val instruction = sanitizeQuery(obj.optString("instruction"))
                val enabled = obj.optBoolean("enabled", true)
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    result.add(
                        TargetLanguageItem(
                            id = id,
                            name = name,
                            instruction = instruction,
                            isDefault = false,
                            enabled = enabled
                        )
                    )
                }
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun encodeIdList(ids: List<String>): String {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun parseIdList(rawJson: String?): List<String> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            val result = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val id = arr.optString(i).trim()
                if (id.isNotEmpty()) result.add(id)
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun <T> applySavedOrder(
        items: List<T>,
        savedOrderIds: List<String>,
        idSelector: (T) -> String
    ): List<T> {
        if (savedOrderIds.isEmpty()) return items
        val byId = items.associateBy(idSelector)
        val ordered = ArrayList<T>(items.size)
        val seen = HashSet<String>(items.size)
        savedOrderIds.forEach { id ->
            val item = byId[id]
            if (item != null && seen.add(id)) {
                ordered.add(item)
            }
        }
        items.forEach { item ->
            if (seen.add(idSelector(item))) {
                ordered.add(item)
            }
        }
        return ordered
    }
}
