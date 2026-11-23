package com.princeniithompson.rapcha.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's app theme preference (Light by default, Dark optional).
 * Loaded synchronously in [com.princeniithompson.rapcha.MainActivity.onCreate] before `setContent`
 * so the app opens in the chosen theme with zero light-to-dark flash.
 */
object ThemeRepository {
    private const val PREFS_NAME = "rapcha_theme_prefs"
    private const val KEY_DARK_THEME = "dark_theme_enabled"

    private val _isDarkTheme = MutableStateFlow(false)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isDarkTheme.value = prefs.getBoolean(KEY_DARK_THEME, false)
        loaded = true
    }

    fun setDarkTheme(context: Context, dark: Boolean) {
        loaded = true
        _isDarkTheme.value = dark
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_THEME, dark)
            .apply()
    }
}
