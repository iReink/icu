package com.example.icu

class SupabaseSyncManager(
    private val trackStore: GpxTrackStore,
    private val metadataStore: SyncMetadataStore,
    private val apiClient: SupabaseApiClient
) {
    fun sync(): SyncResult {
        val session = apiClient.activeSession()
        var uploaded = 0
        var downloaded = 0
        var deleted = 0

        metadataStore.deletedRemoteIds().forEach { remoteId ->
            apiClient.markTrackDeleted(session, remoteId)
            metadataStore.clearDeleted(remoteId)
            deleted += 1
        }

        val remoteTracks = apiClient.fetchTracks(session)
        val localTracks = trackStore.loadTracks()

        localTracks
            .filterNot { metadataStore.isSynced(it) }
            .forEach { track ->
                val remoteId = metadataStore.ensureRemoteId(track.file)
                val storagePath = metadataStore.storagePath(session.userId, track.file)
                apiClient.uploadGpx(session, storagePath, track.file.readBytes())
                apiClient.upsertTrack(session, track.toRemoteTrack(remoteId, session.userId, storagePath))
                metadataStore.markSynced(track, remoteId)
                uploaded += 1
            }

        remoteTracks
            .filterNot { metadataStore.hasRemoteId(it.id) }
            .filterNot { metadataStore.deletedRemoteIds().contains(it.id) }
            .forEach { remoteTrack ->
                val gpxBytes = apiClient.downloadGpx(session, remoteTrack.storagePath)
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
}

data class SyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val deleted: Int
)
