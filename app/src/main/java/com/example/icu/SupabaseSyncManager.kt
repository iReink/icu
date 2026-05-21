package com.example.icu

class SupabaseSyncManager(
    private val trackStore: GpxTrackStore,
    private val metadataStore: SyncMetadataStore,
    private val apiClient: SupabaseApiClient
) {
    fun sync(): SyncResult {
        var session = apiClient.activeSession()
        var uploaded = 0
        var downloaded = 0
        var deleted = 0

        metadataStore.deletedRemoteIds().forEach { remoteId ->
            withFreshSession({ refreshed -> session = refreshed }) { activeSession ->
                apiClient.markTrackDeleted(activeSession, remoteId)
            }
            metadataStore.clearDeleted(remoteId)
            deleted += 1
        }

        val remoteTracks = withFreshSession({ refreshed -> session = refreshed }) { activeSession ->
            apiClient.fetchTracks(activeSession)
        }
        val localTracks = trackStore.loadTracks()

        localTracks
            .filterNot { metadataStore.isSynced(it) }
            .forEach { track ->
                val remoteId = metadataStore.ensureRemoteId(track.file)
                val storagePath = metadataStore.storagePath(session.userId, track.file)
                withFreshSession({ refreshed -> session = refreshed }) { activeSession ->
                    apiClient.uploadGpx(activeSession, storagePath, track.file.readBytes())
                    apiClient.upsertTrack(activeSession, track.toRemoteTrack(remoteId, activeSession.userId, storagePath))
                }
                metadataStore.markSynced(track, remoteId)
                uploaded += 1
            }

        remoteTracks
            .filterNot { metadataStore.hasRemoteId(it.id) }
            .filterNot { metadataStore.deletedRemoteIds().contains(it.id) }
            .forEach { remoteTrack ->
                val gpxBytes = withFreshSession({ refreshed -> session = refreshed }) { activeSession ->
                    apiClient.downloadGpx(activeSession, remoteTrack.storagePath)
                }
                val importedTrack = trackStore.saveImportedGpx(
                    fileName = "track-cloud-${remoteTrack.id}.gpx",
                    gpxBytes = gpxBytes
                )
                if (importedTrack != null) {
                    metadataStore.markSynced(importedTrack, remoteTrack.id)
                    downloaded += 1
                }
            }

        return SyncResult(uploaded, downloaded, deleted)
    }

    fun markDeleted(track: RecordedTrack) {
        metadataStore.markDeleted(track)
    }

    fun isSynced(track: RecordedTrack): Boolean {
        return metadataStore.isSynced(track)
    }

    private fun RecordedTrack.toRemoteTrack(
        remoteId: String,
        userId: String,
        storagePath: String
    ): RemoteTrack {
        return RemoteTrack(
            id = remoteId,
            userId = userId,
            name = name,
            activityType = type,
            distanceMeters = distanceMeters,
            durationMillis = durationMillis,
            startedAtMillis = startedAtMillis,
            endedAtMillis = (startedAtMillis + durationMillis).takeIf { durationMillis > 0L },
            storagePath = storagePath,
            visible = visible,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    private fun <T> withFreshSession(
        onRefresh: (SupabaseSession) -> Unit,
        block: (SupabaseSession) -> T
    ): T {
        val session = apiClient.activeSession()
        return try {
            block(session)
        } catch (error: SupabaseException) {
            if (error.statusCode != 401) throw error
            val refreshed = apiClient.activeSession(forceRefresh = true)
            onRefresh(refreshed)
            block(refreshed)
        }
    }
}

data class SyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val deleted: Int
)
