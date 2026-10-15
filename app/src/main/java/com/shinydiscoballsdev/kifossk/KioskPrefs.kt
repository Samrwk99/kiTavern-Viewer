package com.shinydiscoballsdev.kifossk

import android.content.Context
import android.content.SharedPreferences

/** Centralised, application-context-backed preferences. */
object KioskPrefs {
    private const val PREFS_NAME = "sillytavern_prefs"
    private const val DEFAULT_URL = "http://localhost:8000"

    @Volatile
    private var prefsInstance: SharedPreferences? = null

    fun getInstance(context: Context): SharedPreferences {
        return prefsInstance ?: synchronized(this) {
            prefsInstance ?: context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .also { prefsInstance = it }
        }
    }

    fun getUrl(context: Context): String {
        val saved = getInstance(context).getString("web_url", null)
        return UrlValidator.sanitizeOrDefault(saved, DEFAULT_URL)
    }

    fun setUrl(context: Context, url: String) {
        getInstance(context).edit().putString("web_url", url.trim()).apply()
    }

    fun isFirstRun(context: Context): Boolean =
        getInstance(context).getBoolean("first_run", true)

    fun setFirstRun(context: Context, value: Boolean) {
        getInstance(context).edit().putBoolean("first_run", value).apply()
    }

    fun getOrientation(context: Context): String =
        getInstance(context).getString("orientation", "auto")
            ?.takeIf { it in setOf("landscape", "portrait", "auto") } ?: "auto"

    fun setOrientation(context: Context, orientation: String) {
        val safe = orientation.takeIf { it in setOf("landscape", "portrait", "auto") } ?: "auto"
        getInstance(context).edit().putString("orientation", safe).apply()
    }

    fun getScreenOn(context: Context): Boolean =
        getInstance(context).getBoolean("screen_on", false)

    fun setScreenOn(context: Context, value: Boolean) {
        getInstance(context).edit().putBoolean("screen_on", value).apply()
    }

    fun getTheme(context: Context): String =
        getInstance(context).getString("app_theme", "dark")
            ?.takeIf { it == "dark" || it == "light" } ?: "dark"

    fun setTheme(context: Context, theme: String) {
        val safe = theme.takeIf { it == "dark" || it == "light" } ?: "dark"
        getInstance(context).edit().putString("app_theme", safe).apply()
    }

    /** Preserve the existing behaviour unless the user explicitly disables it. */
    fun getAutoFocusChatInput(context: Context): Boolean =
        getInstance(context).getBoolean("auto_focus_chat_input", true)

    /** Store a settings snapshot with one apply() instead of several disk writes. */
    fun saveSettings(
        context: Context,
        url: String,
        screenOn: Boolean,
        keepAlive: Boolean,
        showNotification: Boolean,
        autoFocusChatInput: Boolean,
        orientation: String,
        theme: String
    ) {
        val prefs = getInstance(context)
        val safeOrientation = orientation.takeIf { it in setOf("landscape", "portrait", "auto") } ?: "auto"
        val safeTheme = theme.takeIf { it == "dark" || it == "light" } ?: "dark"
        val editor = prefs.edit()
        var changed = false

        fun putString(key: String, value: String) {
            if (prefs.getString(key, null) != value) {
                editor.putString(key, value)
                changed = true
            }
        }
        fun putBoolean(key: String, value: Boolean, default: Boolean) {
            if (prefs.getBoolean(key, default) != value) {
                editor.putBoolean(key, value)
                changed = true
            }
        }
        putString("web_url", url.trim())
        putBoolean("screen_on", screenOn, false)
        putBoolean("keep_alive", keepAlive, true)
        putBoolean("show_notification", showNotification, true)
        putBoolean("auto_focus_chat_input", autoFocusChatInput, true)
        putString("orientation", safeOrientation)
        putString("app_theme", safeTheme)
        if (changed) editor.apply()
    }

    /** Controls whether the ongoing notification includes descriptive text; it cannot remove an FGS notification. */
    fun getKeepAlive(context: Context): Boolean =
        getInstance(context).getBoolean("keep_alive", true)

    fun setKeepAlive(context: Context, value: Boolean) {
        getInstance(context).edit().putBoolean("keep_alive", value).apply()
    }

    fun getShowNotification(context: Context): Boolean =
        getInstance(context).getBoolean("show_notification", true)

    fun setShowNotification(context: Context, value: Boolean) {
        getInstance(context).edit().putBoolean("show_notification", value).apply()
    }
}
