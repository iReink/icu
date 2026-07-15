package com.example.icu

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class StoredPublicLocationShare(
    val ownerId: String,
    val shareId: String,
    val token: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long?
)

class PublicLocationShareStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(ownerId: String, share: PublicLocationShare, token: String) {
        prefs.edit()
            .putString(KEY_OWNER_ID, ownerId)
            .putString(KEY_SHARE_ID, share.id)
            .putString(KEY_TOKEN, token)
            .putLong(KEY_CREATED_AT, share.createdAtMillis)
            .putLong(KEY_EXPIRES_AT, share.expiresAtMillis ?: NO_EXPIRY)
            .apply()
    }

    fun load(ownerId: String): StoredPublicLocationShare? {
        if (prefs.getString(KEY_OWNER_ID, null) != ownerId) return null
        val shareId = prefs.getString(KEY_SHARE_ID, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return StoredPublicLocationShare(
            ownerId = ownerId,
            shareId = shareId,
            token = token,
            createdAtMillis = prefs.getLong(KEY_CREATED_AT, 0L),
            expiresAtMillis = prefs.getLong(KEY_EXPIRES_AT, NO_EXPIRY).takeIf { it != NO_EXPIRY }
        )
    }

    fun clear(ownerId: String) {
        if (prefs.getString(KEY_OWNER_ID, null) == ownerId) {
            prefs.edit().clear().apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "public_location_share"
        private const val KEY_OWNER_ID = "owner_id"
        private const val KEY_SHARE_ID = "share_id"
        private const val KEY_TOKEN = "token"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val NO_EXPIRY = -1L

        fun newToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun tokenHash(token: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
