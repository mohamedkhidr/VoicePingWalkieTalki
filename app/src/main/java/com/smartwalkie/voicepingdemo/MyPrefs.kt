package com.smartwalkie.voicepingdemo

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Thin wrapper over a single [SharedPreferences] file. Initialized lazily so
 * that this object can be referenced in any class-load order without
 * depending on [VoicePingClientApp.appContext] being set yet.
 */
object MyPrefs {
    private const val PREFS_NAME = "voiceping_sdk.sp"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_COMPANY = "company"
    private const val KEY_SERVER_URL = "server_url"

    private const val DEFAULT_SERVER_URL = "wss://router-lite.voiceping.info"

    private val prefs: SharedPreferences by lazy {
        VoicePingClientApp.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, "")
        set(value) = prefs.edit { putString(KEY_USER_ID, value) }

    var company: String?
        get() = prefs.getString(KEY_COMPANY, "")
        set(value) = prefs.edit { putString(KEY_COMPANY, value) }

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL)
        set(value) = prefs.edit { putString(KEY_SERVER_URL, value) }

    fun clear() = prefs.edit { clear() }
}
