package com.example.icu

import android.content.Context
import java.io.File
import java.util.UUID

class SyncMetadataStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun remoteId(file: File): String? {
        return prefs.getString(remoteIdKey(file.name), null)
    }

    fun ensureRemoteId(file: File): String {
        val current = remoteId(file)
        if (current != null) return current

        val remoteId = UUID.randomUUID().toString()
        prefs.edit()
            .putString(remoteIdKey(file.name), remoteId)
            .putString(fileNameKey(remoteId), file.name)
            .apply()
        return remoteId
    }

    fun storagePath(userId: String, file: File): String {
        return "$userId/${ensureRemoteId(file)}.gpx.gz"
    }

    fun isSynced(track: RecordedTrack): Boolean {
        val syncedAtModified = prefs.getLong(syncedModifiedKey(track.file.name), -1L)
        return remoteId(track.file) != null && syncedAtModified >= track.file.lastModified()
    }

    fun markSynced(track: RecordedTrack, remoteId: String) {
        prefs.edit()
            .putString(remoteIdKey(track.file.name), remoteId)
            .putString(fileNameKey(remoteId), track.file.name)
            .putLong(syncedModifiedKey(track.file.name), track.file.lastModified())
            .remove(deletedRemoteKey(remoteId))
            .apply()
    }

    fun hasRemoteId(remoteId: String): Boolean {
        return prefs.getString(fileNameKey(remoteId), null) != null
    }

    fun markDeleted(track: RecordedTrack) {
        val remoteId = remoteId(track.file)
        val edit = prefs.edit()
            .remove(remoteIdKey(track.file.name))
            .remove(syncedModifiedKey(track.file.name))

        if (remoteId != null) {
            edit.remove(fileNameKey(remoteId))
                .putBoolean(deletedRemoteKey(remoteId), true)
        }
        edit.apply()
    }

    fun deletedRemoteIds(): List<String> {
        return prefs.all.keys
            .filter { it.startsWith(DELETED_PREFIX) && prefs.getBoolean(it, false) }
            .map { it.removePrefix(DELETED_PREFIX) }
    }

    fun clearDeleted(remoteId: String) {
        prefs.edit().remove(deletedRemoteKey(remoteId)).apply()
    }

    private fun remoteIdKey(fileName: String) = "file.$fileName.remote_id"
    private fun syncedModifiedKey(fileName: String) = "file.$fileName.synced_modified"
    private fun fileNameKey(remoteId: String) = "remote.$remoteId.file_name"
    private fun deletedRemoteKey(remoteId: String) = "$DELETED_PREFIX$remoteId"

    companion object {
        private const val PREFS_NAME = "track_sync"
        private const val DELETED_PREFIX = "deleted."
    }
}
