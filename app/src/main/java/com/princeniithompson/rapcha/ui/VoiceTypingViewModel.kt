package com.princeniithompson.rapcha.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * v25 lean: settings + onboarding state only. Dictation runs in the floating bubble;
 * there is no in-app recorder, history, diagnostics or polish state here.
 */
class VoiceTypingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "voxstream_settings"
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _customApiKey = MutableStateFlow("")
    val customApiKey: StateFlow<String> = _customApiKey.asStateFlow()

    val isBubbleEnabled: StateFlow<Boolean> = com.princeniithompson.rapcha.service.FloatingBubbleManager.isBubbleEnabled
    val isAccessibilityConnected: StateFlow<Boolean> = com.princeniithompson.rapcha.service.FloatingBubbleManager.isAccessibilityConnected
    val hasOverlayPermission: StateFlow<Boolean> = com.princeniithompson.rapcha.service.FloatingBubbleManager.hasOverlayPermission

    fun refreshPermissions() {
        com.princeniithompson.rapcha.service.FloatingBubbleManager.checkPermissions(getApplication())
    }

    val signedInGoogleEmail: StateFlow<String> = com.princeniithompson.rapcha.core.ApiKeyVault.googleEmailFlow
    val provisionedApiKey: StateFlow<String> = com.princeniithompson.rapcha.core.ApiKeyVault.userApiKeyFlow

    private val prefsKeyListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
        if (key == com.princeniithompson.rapcha.core.ApiKeyVault.KEY_CUSTOM_API_KEY) {
            val latest = sharedPrefs.getString(key, "")?.trim().orEmpty()
            if (_customApiKey.value != latest) {
                _customApiKey.value = latest
            }
        }
    }

    init {
        com.princeniithompson.rapcha.core.ApiKeyVault.init(application)
        val savedKey = prefs.getString(KEY_CUSTOM_API_KEY, "")?.trim().orEmpty()
        val vaultKey = com.princeniithompson.rapcha.core.ApiKeyVault.currentUserApiKey.trim()
        val initialKey = when {
            savedKey.isNotBlank() && !com.princeniithompson.rapcha.core.ApiConfig.isPlaceholder(savedKey) -> savedKey
            vaultKey.isNotBlank() && !com.princeniithompson.rapcha.core.ApiConfig.isPlaceholder(vaultKey) -> vaultKey
            else -> ""
        }
        _customApiKey.value = initialKey
        prefs.registerOnSharedPreferenceChangeListener(prefsKeyListener)
        viewModelScope.launch {
            com.princeniithompson.rapcha.core.ApiKeyVault.userApiKeyFlow.collect { key ->
                val clean = key.trim()
                if (clean != _customApiKey.value) {
                    _customApiKey.value = clean
                }
            }
        }
        com.princeniithompson.rapcha.service.FloatingBubbleManager.init(application)
    }

    private val _isSmartMode = MutableStateFlow(com.princeniithompson.rapcha.core.SmartModeSetting.isEnabled(application))
    /** Smart Mode (Smart polish). Default ON. Read by the bubble service at the start of each dictation. */
    val isSmartMode: StateFlow<Boolean> = _isSmartMode.asStateFlow()

    fun setSmartMode(enabled: Boolean) {
        _isSmartMode.value = enabled
        com.princeniithompson.rapcha.core.SmartModeSetting.setEnabled(getApplication(), enabled)
    }

    val isOnboardingCompleted: StateFlow<Boolean> = com.princeniithompson.rapcha.data.OnboardingRepository.isOnboardingCompleted

    fun completeOnboarding() {
        com.princeniithompson.rapcha.data.OnboardingRepository.completeOnboarding()
    }

    fun resetOnboarding() {
        com.princeniithompson.rapcha.data.OnboardingRepository.resetOnboarding()
    }

    fun canDrawOverlays(): Boolean = com.princeniithompson.rapcha.service.FloatingBubbleManager.canDrawOverlays(getApplication())

    fun setBubbleEnabled(enabled: Boolean) {
        com.princeniithompson.rapcha.service.FloatingBubbleManager.setBubbleEnabled(getApplication(), enabled)
    }

    val effectiveApiKey: String
        get() = com.princeniithompson.rapcha.core.ApiConfig.getEffectiveKey(_customApiKey.value)

    fun isApiKeyConfigured(): Boolean {
        return !com.princeniithompson.rapcha.core.ApiConfig.isPlaceholder(effectiveApiKey)
    }

    fun setCustomApiKey(key: String) {
        val trimmed = key.trim()
        if (com.princeniithompson.rapcha.core.ApiConfig.isPlaceholder(trimmed)) {
            _customApiKey.value = ""
            prefs.edit().remove(KEY_CUSTOM_API_KEY).apply()
            com.princeniithompson.rapcha.core.ApiKeyVault.clearInMemoryKeyNow()
            viewModelScope.launch {
                com.princeniithompson.rapcha.core.ApiKeyVault.clearUserKeyOnly(getApplication())
            }
        } else {
            _customApiKey.value = trimmed
            prefs.edit().putString(KEY_CUSTOM_API_KEY, trimmed).apply()
            viewModelScope.launch {
                com.princeniithompson.rapcha.core.ApiKeyVault.syncManualKey(getApplication(), trimmed)
            }
        }
    }

    fun tryAutoInjectFromClipboard(): Boolean {
        if (isApiKeyConfigured()) return false
        return try {
            val app = getApplication<Application>()
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
            val clip = cm.primaryClip ?: return false
            var extracted: String? = null
            for (i in 0 until clip.itemCount) {
                val txt = clip.getItemAt(i)?.coerceToText(app)?.toString()?.trim().orEmpty()
                extracted = com.princeniithompson.rapcha.core.ApiKeyFormat.extractFirstGeminiKey(txt)
                if (extracted != null) break
            }
            val found = extracted ?: return false
            setCustomApiKey(found)
            try {
                val masked = "\u2022".repeat(found.length.coerceIn(24, 40))
                val maskedClip = ClipData.newPlainText("API Key", masked).apply {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        description.extras = android.os.PersistableBundle().apply {
                            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                    }
                }
                cm.setPrimaryClip(maskedClip)
            } catch (_: Throwable) {
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun hasProvisionedPersonalKey(): Boolean {
        return com.princeniithompson.rapcha.core.ApiKeyVault.hasUserApiKey() ||
            !com.princeniithompson.rapcha.core.ApiConfig.isPlaceholder(_customApiKey.value)
    }

    fun clearProvisionedCredentialsForAccountSwitch() {
        _customApiKey.value = ""
        prefs.edit().remove(KEY_CUSTOM_API_KEY).apply()
        com.princeniithompson.rapcha.core.ApiKeyVault.clearInMemoryKeyNow()
        viewModelScope.launch {
            com.princeniithompson.rapcha.core.ApiKeyVault.clearUserCredentials(getApplication())
        }
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsKeyListener)
        super.onCleared()
    }
}
