package com.princeniithompson.rapcha.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private val Context.rapchaKeyVaultDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "rapcha_key_vault"
)

/**
 * Dual-format Gemini API key validator supporting classic `AIzaSy...` and newer `AQ....` keys.
 */
object ApiKeyFormat {
    val GEMINI_KEY_REGEX: Regex = Regex("^(AIzaSy[A-Za-z0-9_-]{28,}|AQ\\.[A-Za-z0-9_.-]{28,})$")
    private val EMBEDDED_KEY_REGEX: Regex = Regex("(AIzaSy[A-Za-z0-9_-]{28,}|AQ\\.[A-Za-z0-9_.-]{28,})")
    private val EMAIL_REGEX: Regex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    fun isValidGeminiKey(candidate: String?): Boolean {
        val trimmed = candidate?.trim().orEmpty()
        return trimmed.isNotEmpty() && GEMINI_KEY_REGEX.matches(trimmed)
    }

    fun extractFirstGeminiKey(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = EMBEDDED_KEY_REGEX.find(text) ?: return null
        val value = match.value.trim()
        return value.takeIf { isValidGeminiKey(it) }
    }

    fun extractFirstEmail(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = EMAIL_REGEX.find(text) ?: return null
        val value = match.value.trim()
        // Ignore obvious non-account resource suffixes if any
        return if (value.contains("@") && !value.endsWith(".png", ignoreCase = true) && !value.endsWith(".js", ignoreCase = true)) {
            value
        } else {
            null
        }
    }
}

/**
 * Encrypts and persists the user's provisioned Gemini API key silently on-device using
 * Android Keystore (AES-256-GCM / NoPadding) + Jetpack DataStore without any password gate.
 */
object ApiKeyVault {

    internal interface CipherAdapter {
        fun encrypt(plainText: String): String
        fun decrypt(cipherBlobBase64: String): String
    }

    private object AndroidKeyStoreCipherAdapter : CipherAdapter {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "rapcha_gemini_key_aes_gcm_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12

        @Synchronized
        private fun getOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }

        override fun encrypt(plainText: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(1 + iv.size + cipherBytes.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)
            return Base64.encodeToString(combined, Base64.NO_WRAP)
        }

        override fun decrypt(cipherBlobBase64: String): String {
            val combined = Base64.decode(cipherBlobBase64, Base64.NO_WRAP)
            if (combined.size < 2) return ""
            val ivLength = combined[0].toInt() and 0xFF
            if (ivLength <= 0 || combined.size <= 1 + ivLength) return ""
            val iv = combined.copyOfRange(1, 1 + ivLength)
            val cipherBytes = combined.copyOfRange(1 + ivLength, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            return String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        }
    }

    const val SETTINGS_PREFS_NAME = "voxstream_settings"
    const val KEY_CUSTOM_API_KEY = "custom_api_key"
    private const val KEY_VERIFIED_KEY_HASH = "verified_gemini_key_hash"
    private const val KEY_VERIFY_URL = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1"

    enum class KeyVerifyKind {
        VALID,
        INVALID,
        NETWORK_ERROR
    }

    data class KeyVerifyResult(
        val kind: KeyVerifyKind,
        val reason: String = ""
    )

    private val KEY_ENCRYPTED_API_KEY = stringPreferencesKey("encrypted_gemini_api_key")
    private val KEY_GOOGLE_ACCOUNT_EMAIL = stringPreferencesKey("encrypted_google_email")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var cipherAdapter: CipherAdapter = AndroidKeyStoreCipherAdapter

    @Volatile
    var currentUserApiKey: String = ""
        private set

    @Volatile
    var currentApiKeyVerified: Boolean = false
        private set

    private val _userApiKeyFlow = MutableStateFlow("")
    val userApiKeyFlow: StateFlow<String> = _userApiKeyFlow.asStateFlow()

    private val _googleEmailFlow = MutableStateFlow("")
    val googleEmailFlow: StateFlow<String> = _googleEmailFlow.asStateFlow()

    private val _isApiKeyVerified = MutableStateFlow(false)
    val isApiKeyVerified: StateFlow<Boolean> = _isApiKeyVerified.asStateFlow()

    private val _apiKeyErrorReason = MutableStateFlow("")
    val apiKeyErrorReason: StateFlow<String> = _apiKeyErrorReason.asStateFlow()

    fun hasUserApiKey(): Boolean = !ApiConfig.isPlaceholder(currentUserApiKey)

    fun hasVerifiedWorkingKey(): Boolean =
        hasUserApiKey() && currentApiKeyVerified && _apiKeyErrorReason.value.isBlank()

    private fun hashKey(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(trimmed.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(digest, Base64.NO_WRAP)
        } catch (_: Throwable) {
            trimmed.hashCode().toString()
        }
    }

    private fun refreshVerifiedStateFromPrefs(context: Context, key: String) {
        val trimmed = key.trim()
        if (ApiConfig.isPlaceholder(trimmed)) {
            currentApiKeyVerified = false
            _isApiKeyVerified.value = false
            return
        }
        val prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
        val storedHash = prefs.getString(KEY_VERIFIED_KEY_HASH, "").orEmpty()
        val verified = storedHash.isNotEmpty() && storedHash == hashKey(trimmed)
        currentApiKeyVerified = verified
        _isApiKeyVerified.value = verified
    }

    fun init(context: Context) {
        val appCtx = context.applicationContext ?: context
        appContext = appCtx
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            // Fast synchronous restore from SharedPreferences so callers on startup have the key immediately
            try {
                val prefs = appCtx.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
                val prefsKey = prefs.getString(KEY_CUSTOM_API_KEY, null)?.trim().orEmpty()
                if (!ApiConfig.isPlaceholder(prefsKey) && currentUserApiKey.isBlank()) {
                    currentUserApiKey = prefsKey
                    _userApiKeyFlow.value = prefsKey
                }
                refreshVerifiedStateFromPrefs(appCtx, currentUserApiKey)
            } catch (_: Throwable) {
            }
        }
        scope.launch {
            loadFromDataStore(appCtx)
        }
    }

    suspend fun loadFromDataStore(context: Context) {
        val appCtx = context.applicationContext ?: context
        appContext = appCtx
        val prefs = try {
            appCtx.rapchaKeyVaultDataStore.data
                .catch { e ->
                    if (e is IOException) emit(emptyPreferences()) else throw e
                }
                .first()
        } catch (_: Throwable) {
            emptyPreferences()
        }

        val encKey = prefs[KEY_ENCRYPTED_API_KEY].orEmpty()
        val decryptedKey = if (encKey.isNotEmpty()) {
            try {
                cipherAdapter.decrypt(encKey).trim()
            } catch (_: Throwable) {
                ""
            }
        } else {
            ""
        }

        val sharedPrefs = appCtx.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
        val sharedPrefKey = sharedPrefs.getString(KEY_CUSTOM_API_KEY, null)?.trim().orEmpty()

        val validVaultKey = if (ApiKeyFormat.isValidGeminiKey(decryptedKey)) decryptedKey else ""
        val resolvedKey = when {
            validVaultKey.isNotEmpty() -> {
                if (sharedPrefKey != validVaultKey) {
                    sharedPrefs.edit().putString(KEY_CUSTOM_API_KEY, validVaultKey).apply()
                }
                validVaultKey
            }
            !ApiConfig.isPlaceholder(sharedPrefKey) -> sharedPrefKey
            else -> ""
        }

        currentUserApiKey = resolvedKey
        _userApiKeyFlow.value = resolvedKey
        refreshVerifiedStateFromPrefs(appCtx, resolvedKey)

        val encEmail = prefs[KEY_GOOGLE_ACCOUNT_EMAIL].orEmpty()
        val decryptedEmail = if (encEmail.isNotEmpty()) {
            try {
                cipherAdapter.decrypt(encEmail).trim()
            } catch (_: Throwable) {
                ""
            }
        } else {
            ""
        }
        _googleEmailFlow.value = decryptedEmail
    }

    suspend fun saveUserApiKey(
        context: Context,
        rawKey: String,
        email: String? = null
    ): Boolean {
        val trimmed = rawKey.trim()
        if (!ApiKeyFormat.isValidGeminiKey(trimmed)) return false

        val appCtx = context.applicationContext ?: context
        appContext = appCtx

        val previousKey = currentUserApiKey.trim()
        // Always update in-memory + voxstream_settings custom_api_key immediately so UI & services sync
        currentUserApiKey = trimmed
        _userApiKeyFlow.value = trimmed
        _apiKeyErrorReason.value = ""
        val prefsEditor = appCtx.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_API_KEY, trimmed)
        if (previousKey != trimmed) {
            prefsEditor.remove(KEY_VERIFIED_KEY_HASH)
            currentApiKeyVerified = false
            _isApiKeyVerified.value = false
        }
        prefsEditor.apply()
        if (previousKey == trimmed) {
            refreshVerifiedStateFromPrefs(appCtx, trimmed)
        }

        val encryptedKey = try {
            cipherAdapter.encrypt(trimmed)
        } catch (_: Throwable) {
            ""
        }

        val cleanEmail = email?.trim().orEmpty()
        val encryptedEmail = if (cleanEmail.isNotEmpty()) {
            try {
                cipherAdapter.encrypt(cleanEmail)
            } catch (_: Throwable) {
                ""
            }
        } else {
            ""
        }

        if (encryptedKey.isNotEmpty() || encryptedEmail.isNotEmpty()) {
            try {
                appCtx.rapchaKeyVaultDataStore.edit { prefs ->
                    if (encryptedKey.isNotEmpty()) {
                        prefs[KEY_ENCRYPTED_API_KEY] = encryptedKey
                    }
                    if (encryptedEmail.isNotEmpty()) {
                        prefs[KEY_GOOGLE_ACCOUNT_EMAIL] = encryptedEmail
                    }
                }
            } catch (_: Throwable) {
            }
        }

        if (cleanEmail.isNotEmpty()) {
            _googleEmailFlow.value = cleanEmail
        }
        return true
    }

    suspend fun syncManualKey(context: Context, rawKey: String) {
        val trimmed = rawKey.trim()
        val appCtx = context.applicationContext ?: context
        if (ApiConfig.isPlaceholder(trimmed)) {
            clearUserKeyOnly(appCtx)
            return
        }
        if (ApiKeyFormat.isValidGeminiKey(trimmed)) {
            saveUserApiKey(appCtx, trimmed, email = null)
        } else {
            appContext = appCtx
            val previousKey = currentUserApiKey.trim()
            currentUserApiKey = trimmed
            _userApiKeyFlow.value = trimmed
            _apiKeyErrorReason.value = ""
            val editor = appCtx.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CUSTOM_API_KEY, trimmed)
            if (previousKey != trimmed) {
                editor.remove(KEY_VERIFIED_KEY_HASH)
                currentApiKeyVerified = false
                _isApiKeyVerified.value = false
            }
            editor.apply()
        }
    }

    fun markApiKeyVerified(context: Context, apiKey: String = currentUserApiKey) {
        val trimmed = apiKey.trim()
        if (ApiConfig.isPlaceholder(trimmed)) return
        val appCtx = context.applicationContext ?: context
        appContext = appCtx
        val keyHash = hashKey(trimmed)
        appCtx.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VERIFIED_KEY_HASH, keyHash)
            .apply()
        currentApiKeyVerified = true
        _isApiKeyVerified.value = true
        _apiKeyErrorReason.value = ""
    }

    fun markApiKeyInvalid(context: Context, reason: String) {
        val appCtx = context.applicationContext ?: context
        appContext = appCtx
        appCtx.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_VERIFIED_KEY_HASH)
            .apply()
        currentApiKeyVerified = false
        _isApiKeyVerified.value = false
        _apiKeyErrorReason.value = reason.trim().ifBlank { "Gemini API key was rejected." }
    }

    suspend fun verifyApiKeyWithGemini(context: Context, apiKey: String): KeyVerifyResult {
        val trimmed = apiKey.trim()
        if (ApiConfig.isPlaceholder(trimmed)) {
            markApiKeyInvalid(context, "Gemini API key is missing.")
            return KeyVerifyResult(KeyVerifyKind.INVALID, "Gemini API key is missing.")
        }
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val client = ApiConfig.createOkHttpClient()
                val helloPayload = """{"contents":[{"parts":[{"text":"hello"}]}],"generationConfig":{"maxOutputTokens":1}}"""
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val helloUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
                val helloRequest = okhttp3.Request.Builder()
                    .url(helloUrl)
                    .addHeader("x-goog-api-key", trimmed)
                    .post(helloPayload.toRequestBody(mediaType))
                    .build()

                client.newCall(helloRequest).execute().use { helloResp ->
                    val code = helloResp.code
                    when {
                        helloResp.isSuccessful -> {
                            markApiKeyVerified(context, trimmed)
                            return@withContext KeyVerifyResult(KeyVerifyKind.VALID)
                        }
                        code == 429 -> {
                            // Key is valid and authenticated; rate-limited on generateContent
                            markApiKeyVerified(context, trimmed)
                            return@withContext KeyVerifyResult(KeyVerifyKind.VALID)
                        }
                        code == 400 || code == 401 || code == 403 -> {
                            val body = try { helloResp.body?.string().orEmpty() } catch (_: Throwable) { "" }
                            if (code == 400 && !body.contains("API_KEY", ignoreCase = true) && !body.contains("key", ignoreCase = true)) {
                                // Model parameter change rather than bad key; fall through to models check
                            } else {
                                val msg = "API key rejected (HTTP $code)"
                                markApiKeyInvalid(context, msg)
                                return@withContext KeyVerifyResult(KeyVerifyKind.INVALID, msg)
                            }
                        }
                        code != 404 -> {
                            return@withContext KeyVerifyResult(KeyVerifyKind.NETWORK_ERROR, "HTTP $code")
                        }
                    }
                }

                // Fallback if the specific model endpoint returned 404 in a future API revision
                val request = okhttp3.Request.Builder()
                    .url(KEY_VERIFY_URL)
                    .addHeader("x-goog-api-key", trimmed)
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    when {
                        response.isSuccessful -> {
                            markApiKeyVerified(context, trimmed)
                            KeyVerifyResult(KeyVerifyKind.VALID)
                        }
                        code == 400 || code == 401 || code == 403 -> {
                            val msg = "API key rejected (HTTP $code)"
                            markApiKeyInvalid(context, msg)
                            KeyVerifyResult(KeyVerifyKind.INVALID, msg)
                        }
                        else -> {
                            KeyVerifyResult(KeyVerifyKind.NETWORK_ERROR, "HTTP $code")
                        }
                    }
                }
            } catch (e: Throwable) {
                KeyVerifyResult(KeyVerifyKind.NETWORK_ERROR, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun clearInMemoryKeyNow() {
        currentUserApiKey = ""
        _userApiKeyFlow.value = ""
        currentApiKeyVerified = false
        _isApiKeyVerified.value = false
        _apiKeyErrorReason.value = ""
    }

    suspend fun clearUserKeyOnly(context: Context) {
        val appCtx = context.applicationContext ?: context
        currentUserApiKey = ""
        _userApiKeyFlow.value = ""
        currentApiKeyVerified = false
        _isApiKeyVerified.value = false
        _apiKeyErrorReason.value = ""
        appCtx.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CUSTOM_API_KEY)
            .remove(KEY_VERIFIED_KEY_HASH)
            .apply()
        try {
            appCtx.rapchaKeyVaultDataStore.edit { prefs ->
                prefs.remove(KEY_ENCRYPTED_API_KEY)
            }
        } catch (_: Throwable) {
        }
    }

    suspend fun saveGoogleEmail(context: Context, email: String) {
        val cleanEmail = email.trim()
        if (cleanEmail.isEmpty()) return
        val appCtx = context.applicationContext ?: context
        appContext = appCtx
        val encrypted = try {
            cipherAdapter.encrypt(cleanEmail)
        } catch (_: Throwable) {
            ""
        }
        if (encrypted.isNotEmpty()) {
            try {
                appCtx.rapchaKeyVaultDataStore.edit { prefs ->
                    prefs[KEY_GOOGLE_ACCOUNT_EMAIL] = encrypted
                }
            } catch (_: Throwable) {
            }
        }
        _googleEmailFlow.value = cleanEmail
    }

    suspend fun clearUserCredentials(context: Context) {
        val appCtx = context.applicationContext ?: context
        currentUserApiKey = ""
        _userApiKeyFlow.value = ""
        _googleEmailFlow.value = ""
        currentApiKeyVerified = false
        _isApiKeyVerified.value = false
        _apiKeyErrorReason.value = ""
        appCtx.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CUSTOM_API_KEY)
            .remove(KEY_VERIFIED_KEY_HASH)
            .apply()
        try {
            appCtx.rapchaKeyVaultDataStore.edit { prefs ->
                prefs.remove(KEY_ENCRYPTED_API_KEY)
                prefs.remove(KEY_GOOGLE_ACCOUNT_EMAIL)
            }
        } catch (_: Throwable) {
        }
    }

    internal fun setCipherAdapterForTesting(adapter: CipherAdapter?) {
        cipherAdapter = adapter ?: AndroidKeyStoreCipherAdapter
    }

    internal fun resetForTesting() {
        initialized = false
        currentUserApiKey = ""
        _userApiKeyFlow.value = ""
        _googleEmailFlow.value = ""
        currentApiKeyVerified = false
        _isApiKeyVerified.value = false
        _apiKeyErrorReason.value = ""
        cipherAdapter = AndroidKeyStoreCipherAdapter
    }
}
