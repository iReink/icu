package com.example.icu

import android.content.Context

data class SupabaseSession(
    val userId: String,
    val email: String?,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long
)

class SupabaseSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun current(): SupabaseSession? {
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        return SupabaseSession(
            userId = userId,
            email = prefs.getString(KEY_EMAIL, null),
            accessToken = accessToken,
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null),
            expiresAtMillis = prefs.getLong(KEY_EXPIRES_AT, 0L)
        )
    }

    fun save(session: SupabaseSession) {
        prefs.edit()
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAtMillis)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "supabase_session"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
    }
}
