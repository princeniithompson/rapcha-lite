package com.princeniithompson.rapcha.data

import android.content.Context
import android.content.SharedPreferences
import com.princeniithompson.rapcha.core.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class UserProfileState(
    val displayName: String = "",
    val email: String = "",
    val avatarUrl: String = "",
    val avatarFilePath: String = "",
    val isSyncing: Boolean = false,
    val lastSyncMessage: String = ""
)

/**
 * Persists the signed-in Google profile's display name and caches the user's real Google avatar
 * locally in app storage (`filesDir/rapcha_google_avatar.img`) so the Hamburger Drawer and Account
 * screen render it instantly offline without extra image-loader dependencies.
 */
object UserProfileRepository {
    private const val PREFS_NAME = "rapcha_user_profile_prefs"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_EMAIL = "email"
    private const val KEY_AVATAR_URL = "avatar_url"
    private const val AVATAR_FILE_NAME = "rapcha_google_avatar.img"

    private val lock = Any()
    private var prefs: SharedPreferences? = null

    private val _profile = MutableStateFlow(UserProfileState())
    val profile: StateFlow<UserProfileState> = _profile.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            val appCtx = context.applicationContext
            if (prefs == null) {
                prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            val sp = prefs ?: return
            val avatarFile = File(appCtx.filesDir, AVATAR_FILE_NAME)
            val existingPath = if (avatarFile.exists() && avatarFile.length() > 128L) {
                avatarFile.absolutePath
            } else {
                ""
            }
            _profile.value = UserProfileState(
                displayName = sp.getString(KEY_DISPLAY_NAME, "").orEmpty(),
                email = sp.getString(KEY_EMAIL, "").orEmpty(),
                avatarUrl = sp.getString(KEY_AVATAR_URL, "").orEmpty(),
                avatarFilePath = existingPath,
                isSyncing = _profile.value.isSyncing,
                lastSyncMessage = _profile.value.lastSyncMessage
            )
        }
    }

    fun setSyncing(syncing: Boolean, message: String = "") {
        _profile.value = _profile.value.copy(
            isSyncing = syncing,
            lastSyncMessage = if (message.isNotBlank()) message else _profile.value.lastSyncMessage
        )
    }

    /**
     * Saves profile fields extracted from Google AI Studio's account badge and downloads/caches
     * the circular Google avatar image to internal storage if changed or not yet cached.
     */
    suspend fun saveProfileFromWeb(
        context: Context,
        displayName: String?,
        email: String?,
        avatarUrl: String?
    ) {
        val appCtx = context.applicationContext
        val cleanName = displayName?.trim().orEmpty()
        val cleanEmail = email?.trim().orEmpty()
        val cleanUrl = sanitizeAvatarUrl(avatarUrl)

        val current = _profile.value
        val resolvedName = cleanName.ifBlank { current.displayName }
        val resolvedEmail = cleanEmail.ifBlank { current.email }
        val resolvedUrl = cleanUrl.ifBlank { current.avatarUrl }

        synchronized(lock) {
            if (prefs == null) {
                prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            prefs?.edit()
                ?.putString(KEY_DISPLAY_NAME, resolvedName)
                ?.putString(KEY_EMAIL, resolvedEmail)
                ?.putString(KEY_AVATAR_URL, resolvedUrl)
                ?.apply()
        }

        val avatarFile = File(appCtx.filesDir, AVATAR_FILE_NAME)
        val shouldDownload = resolvedUrl.isNotBlank() &&
            (resolvedUrl != current.avatarUrl || !avatarFile.exists() || avatarFile.length() <= 128L)

        var cachedPath = if (avatarFile.exists() && avatarFile.length() > 128L) {
            avatarFile.absolutePath
        } else {
            ""
        }

        if (shouldDownload) {
            val downloaded = downloadAvatarToFile(resolvedUrl, avatarFile)
            if (downloaded) {
                cachedPath = avatarFile.absolutePath
            }
        }

        _profile.value = _profile.value.copy(
            displayName = resolvedName,
            email = resolvedEmail,
            avatarUrl = resolvedUrl,
            avatarFilePath = cachedPath
        )
    }

    fun clearProfile(context: Context) {
        val appCtx = context.applicationContext
        synchronized(lock) {
            prefs?.edit()?.clear()?.apply()
        }
        try {
            File(appCtx.filesDir, AVATAR_FILE_NAME).delete()
        } catch (_: Throwable) {
        }
        _profile.value = UserProfileState()
    }

    internal fun sanitizeAvatarUrl(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (!trimmed.startsWith("https://")) return ""
        // Request a crisp 192px square avatar when Google serves a tiny =s32 or =s64 thumbnail
        return trimmed.replace(Regex("=s\\d+-c"), "=s192-c")
    }

    private suspend fun downloadAvatarToFile(url: String, destFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = ApiConfig.createOkHttpClient()
                    .newBuilder()
                    .callTimeout(10L, TimeUnit.SECONDS)
                    .readTimeout(10L, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext false
                    val bytes = resp.body?.bytes() ?: return@withContext false
                    if (bytes.size < 128 || bytes.size > 4_000_000) return@withContext false
                    val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(destFile)) {
                        destFile.writeBytes(bytes)
                        tmp.delete()
                    }
                    true
                }
            } catch (_: Throwable) {
                false
            }
        }
    }
}
