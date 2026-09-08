package com.jarvis.android

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The handful of things the native shell needs to remember. Everything the chat
 * itself remembers (transcript, access token, per-page gateway override) lives
 * in the page's own localStorage, which the WebView persists for us.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * Base URL of the gateway that serves the chat page. Empty until the user
     * sets one, unless a default was baked in at build time with
     * `-PjarvisGatewayUrl=...`.
     */
    var gatewayUrl: String
        get() = prefs.getString(KEY_GATEWAY_URL, null) ?: BuildConfig.DEFAULT_GATEWAY_URL
        set(value) = prefs.edit { putString(KEY_GATEWAY_URL, normalize(value)) }

    /** Whether the foreground service should hold the session open when backgrounded. */
    var keepSessionAlive: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ALIVE, true)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_ALIVE, value) }

    val isConfigured: Boolean get() = gatewayUrl.isNotBlank()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        private const val NAME = "jarvis_shell"
        const val KEY_GATEWAY_URL = "gateway_url"
        const val KEY_KEEP_ALIVE = "keep_session_alive"

        /** Trims trailing slashes so origin comparisons elsewhere stay simple. */
        fun normalize(raw: String): String = raw.trim().trimEnd('/')

        fun isValid(raw: String): Boolean {
            val value = normalize(raw)
            if (value.isEmpty()) return false
            return try {
                val uri = android.net.Uri.parse(value)
                uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }
    }
}
