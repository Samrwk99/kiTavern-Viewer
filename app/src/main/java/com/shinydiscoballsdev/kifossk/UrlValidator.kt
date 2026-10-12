package com.shinydiscoballsdev.kifossk

import android.net.Uri

/**
 * Accepts only absolute HTTP(S) URLs with a host. This keeps WebView navigation
 * and the background pinger from trying to treat arbitrary schemes as websites.
 */
object UrlValidator {
    private val allowedSchemes = setOf("http", "https")

    fun isValid(url: String?): Boolean {
        if (url.isNullOrBlank()) return false

        return try {
            val parsed = Uri.parse(url.trim())
            parsed.scheme?.lowercase() in allowedSchemes && !parsed.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun sanitizeOrDefault(url: String?, default: String): String {
        return if (isValid(url)) url!!.trim() else default
    }
}
