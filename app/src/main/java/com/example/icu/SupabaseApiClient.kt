package com.example.icu

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.Instant
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class SupabaseApiClient(
    private val sessionStore: SupabaseSessionStore
) {
    fun emailExists(email: String): Boolean {
        val body = JSONObject()
            .put("candidate_email", email)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val response = request(
            method = "POST",
            url = "${SupabaseConfig.PROJECT_URL}/rest/v1/rpc/email_exists_for_auth",
            headers = jsonHeaders(),
            body = body
        )
        return response.text.trim().toBooleanStrictOrNull() ?: false
    }

    fun signIn(email: String, password: String): SupabaseSession {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val response = request(
            method = "POST",
            url = "${SupabaseConfig.PROJECT_URL}/auth/v1/token?grant_type=password",
            headers = jsonHeaders(),
            body = body
        )
        val session = parseSession(JSONObject(response.text), email)
        sessionStore.save(session)
        return session
    }

    fun signUp(email: String, password: String): SupabaseSession? {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val response = request(
            method = "POST",
            url = "${SupabaseConfig.PROJECT_URL}/auth/v1/signup?redirect_to=${SupabaseConfig.AUTH_REDIRECT_URI.urlEncoded()}",
            headers = jsonHeaders(),
            body = body
        )
        val json = JSONObject(response.text)
        if (!json.has("access_token")) {
            return null
        }

        val session = parseSession(json, email)
        sessionStore.save(session)
        return session
    }

    fun activeSession(): SupabaseSession {
        val session = sessionStore.current() ?: throw SupabaseException(401, "Not signed in")
        if (session.expiresAtMillis - System.currentTimeMillis() > REFRESH_MARGIN_MILLIS) {
            return session
        }

        val refreshToken = session.refreshToken ?: return session
        val body = JSONObject()
            .put("refresh_token", refreshToken)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val response = request(
            method = "POST",
            url = "${SupabaseConfig.PROJECT_URL}/auth/v1/token?grant_type=refresh_token",
            headers = jsonHeaders(),
            body = body
        )
        val refreshed = parseSession(JSONObject(response.text), session.email)
        sessionStore.save(refreshed)
        return refreshed
    }

    fun fetchTracks(session: SupabaseSession): List<RemoteTrack> {
        val response = request(
            method = "GET",
            url = "${SupabaseConfig.PROJECT_URL}/rest/v1/tracks?select=*&deleted_at=is.null&order=updated_at.desc",
            headers = authHeaders(session)
        )
        val array = JSONArray(response.text)
        return (0 until array.length()).map { index ->
            parseRemoteTrack(array.getJSONObject(index))
        }
    }

    fun uploadGpx(session: SupabaseSession, storagePath: String, gpxBytes: ByteArray) {
        request(
            method = "POST",
            url = "${SupabaseConfig.PROJECT_URL}/storage/v1/object/${SupabaseConfig.TRACK_BUCKET}/$storagePath",
            headers = authHeaders(session) + mapOf(
                "Content-Type" to "application/gzip",
                "x-upsert" to "true"
            ),
            body = gzip(gpxBytes)
        )
    }

    fun downloadGpx(session: SupabaseSession, storagePath: String): ByteArray {
        val response = request(
            method = "GET",
            url = "${SupabaseConfig.PROJECT_URL}/storage/v1/object/${SupabaseConfig.TRACK_BUCKET}/$storagePath",
            headers = authHeaders(session)
        )
        return gunzip(response.bytes)
    }

    fun upsertTrack(session: SupabaseSession, track: RemoteTrack) {
        val body = JSONArray()
            .put(track.toJson())
            .toString()
            .toByteArray(Charsets.UTF_8)

        request(
            method = "POST",
            url = "${SupabaseConfig.PROJECT_URL}/rest/v1/tracks?on_conflict=id",
            headers = authHeaders(session) + mapOf(
                "Content-Type" to "application/json",
                "Prefer" to "resolution=merge-duplicates"
            ),
            body = body
        )
    }

    fun markTrackDeleted(session: SupabaseSession, remoteId: String) {
        val now = Instant.now().toString()
        val body = JSONObject()
            .put("deleted_at", now)
            .put("updated_at", now)
            .toString()
            .toByteArray(Charsets.UTF_8)

        request(
            method = "PATCH",
            url = "${SupabaseConfig.PROJECT_URL}/rest/v1/tracks?id=eq.$remoteId",
            headers = authHeaders(session) + mapOf(
                "Content-Type" to "application/json",
                "Prefer" to "return=minimal"
            ),
            body = body
        )
    }

    fun createFriendInvite(session: SupabaseSession): String {
        val response = request(
            method = "POST",
            url = "${SupabaseConfig.PROJECT_URL}/rest/v1/rpc/create_friend_invite",
            headers = authHeaders(session) + mapOf("Content-Type" to "application/json"),
            body = "{}".toByteArray(Charsets.UTF_8)
        )
        return response.text.trim().trim('"')
    }

    fun acceptFriendInvite(session: SupabaseSession, token: String) {
        val body = JSONObject()
            .put("invite_token", token)
            .toString()
            .toByteArray(Charsets.UTF_8)
        request(
            method = "POST",
            url = "${SupabaseConfig.PROJECT_URL}/rest/v1/rpc/accept_friend_invite",
            headers = authHeaders(session) + mapOf("Content-Type" to "application/json"),
            body = body
        )
    }

    fun fetchFriends(session: SupabaseSession): List<FriendProfile> {
        val response = request(
            method = "POST",
            url = "${SupabaseConfig.PROJECT_URL}/rest/v1/rpc/friend_list",
            headers = authHeaders(session) + mapOf("Content-Type" to "application/json"),
            body = "{}".toByteArray(Charsets.UTF_8)
        )
        val array = JSONArray(response.text)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            FriendProfile(
                friendshipId = item.getString("friendship_id"),
                userId = item.getString("friend_id"),
                email = item.optString("friend_email", ""),
                iShare = item.optBoolean("i_share", true),
                friendShares = item.optBoolean("friend_shares", true)
            )
        }
    }

    fun setFriendShare(session: SupabaseSession, friendshipId: String, isSharing: Boolean) {
        val body = JSONObject()
            .put("friendship", friendshipId)
            .put("share_enabled", isSharing)
            .toString()
            .toByteArray(Charsets.UTF_8)
        request(
            method = "POST",
            url = "${SupabaseConfig.PROJECT_URL}/rest/v1/rpc/set_friend_share",
            headers = authHeaders(session) + mapOf("Content-Type" to "application/json"),
            body = body
        )
    }

    fun deleteFriend(session: SupabaseSession, friendshipId: String) {
        request(
            method = "DELETE",
            url = "${SupabaseConfig.PROJECT_URL}/rest/v1/friendships?id=eq.$friendshipId",
            headers = authHeaders(session) + mapOf("Prefer" to "return=minimal")
        )
    }

    fun uploadLocationPoints(session: SupabaseSession, points: List<LocationSharePoint>) {
        if (points.isEmpty()) return
        val body = JSONArray()
        points.forEach { point ->
            val json = JSONObject()
                .put("user_id", session.userId)
                .put("latitude", point.latitude)
                .put("longitude", point.longitude)
                .put("recorded_at", Instant.ofEpochMilli(point.recordedAtMillis).toString())
            point.altitude?.let { json.put("altitude", it) }
            point.accuracyMeters?.let { json.put("accuracy_meters", it.toDouble()) }
            body.put(json)
        }
        request(
            method = "POST",
            url = "${SupabaseConfig.PROJECT_URL}/rest/v1/location_points",
            headers = authHeaders(session) + mapOf(
                "Content-Type" to "application/json",
                "Prefer" to "return=minimal"
            ),
            body = body.toString().toByteArray(Charsets.UTF_8)
        )
    }

    fun fetchFriendLocations(session: SupabaseSession, friendId: String): List<LocationSharePoint> {
        val since = Instant.now().minusSeconds(24 * 60 * 60).toString()
        val response = request(
            method = "GET",
            url = "${SupabaseConfig.PROJECT_URL}/rest/v1/location_points?select=*&user_id=eq.$friendId&recorded_at=gte.$since&order=recorded_at.asc",
            headers = authHeaders(session)
        )
        val array = JSONArray(response.text)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            LocationSharePoint(
                latitude = item.getDouble("latitude"),
                longitude = item.getDouble("longitude"),
                altitude = item.optDoubleOrNull("altitude"),
                accuracyMeters = item.optDoubleOrNull("accuracy_meters")?.toFloat(),
                recordedAtMillis = Instant.parse(item.getString("recorded_at")).toEpochMilli()
            )
        }
    }

    private fun parseSession(json: JSONObject, fallbackEmail: String?): SupabaseSession {
        val user = json.getJSONObject("user")
        val expiresInSeconds = json.optLong("expires_in", DEFAULT_EXPIRES_IN_SECONDS)
        return SupabaseSession(
            userId = user.getString("id"),
            email = user.optString("email").takeIf { it.isNotBlank() } ?: fallbackEmail,
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresAtMillis = System.currentTimeMillis() + expiresInSeconds * 1000L
        )
    }

    private fun parseRemoteTrack(json: JSONObject): RemoteTrack {
        val endedAt = json.optString("ended_at").takeIf { it.isNotBlank() && it != "null" }
        return RemoteTrack(
            id = json.getString("id"),
            userId = json.getString("user_id"),
            name = json.getString("name"),
            activityType = TrackType.fromGpxType(json.getString("activity_type")),
            distanceMeters = json.optDouble("distance_meters", 0.0).toFloat(),
            durationMillis = json.optLong("duration_millis", 0L),
            startedAtMillis = Instant.parse(json.getString("started_at")).toEpochMilli(),
            endedAtMillis = endedAt?.let { Instant.parse(it).toEpochMilli() },
            storagePath = json.getString("storage_path"),
            visible = json.optBoolean("visible", true),
            updatedAtMillis = Instant.parse(json.getString("updated_at")).toEpochMilli()
        )
    }

    private fun RemoteTrack.toJson(): JSONObject {
        val json = JSONObject()
            .put("id", id)
            .put("user_id", userId)
            .put("name", name)
            .put("activity_type", activityType.gpxType)
            .put("distance_meters", distanceMeters.toDouble())
            .put("duration_millis", durationMillis)
            .put("started_at", Instant.ofEpochMilli(startedAtMillis).toString())
            .put("storage_bucket", SupabaseConfig.TRACK_BUCKET)
            .put("storage_path", storagePath)
            .put("visible", visible)
            .put("updated_at", Instant.now().toString())

        if (endedAtMillis != null) {
            json.put("ended_at", Instant.ofEpochMilli(endedAtMillis).toString())
        } else {
            json.put("ended_at", JSONObject.NULL)
        }
        return json
    }

    private fun jsonHeaders(): Map<String, String> {
        return mapOf(
            "apikey" to SupabaseConfig.PUBLISHABLE_KEY,
            "Content-Type" to "application/json"
        )
    }

    private fun authHeaders(session: SupabaseSession): Map<String, String> {
        return mapOf(
            "apikey" to SupabaseConfig.PUBLISHABLE_KEY,
            "Authorization" to "Bearer ${session.accessToken}"
        )
    }

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray? = null
    ): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body) }
            }
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        if (responseCode !in 200..299) {
            throw SupabaseException(responseCode, bytes.toString(Charsets.UTF_8))
        }
        return HttpResult(bytes, bytes.toString(Charsets.UTF_8))
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        return GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 30_000
        private const val REFRESH_MARGIN_MILLIS = 60_000L
        private const val DEFAULT_EXPIRES_IN_SECONDS = 3600L
    }
}

private fun JSONObject.optDoubleOrNull(name: String): Double? {
    return if (has(name) && !isNull(name)) optDouble(name) else null
}

private fun String.urlEncoded(): String {
    return URLEncoder.encode(this, Charsets.UTF_8.name())
}

data class RemoteTrack(
    val id: String,
    val userId: String,
    val name: String,
    val activityType: TrackType,
    val distanceMeters: Float,
    val durationMillis: Long,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val storagePath: String,
    val visible: Boolean,
    val updatedAtMillis: Long
)

data class HttpResult(
    val bytes: ByteArray,
    val text: String
)

data class FriendProfile(
    val friendshipId: String,
    val userId: String,
    val email: String,
    val iShare: Boolean,
    val friendShares: Boolean
)

data class LocationSharePoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracyMeters: Float?,
    val recordedAtMillis: Long
)

class SupabaseException(
    val statusCode: Int,
    message: String
) : Exception(message) {
    private val errorJson = runCatching { JSONObject(message) }.getOrNull()

    fun isInvalidCredentials(): Boolean {
        val lower = readableMessage().lowercase(Locale.US)
        return statusCode == 400 && (
            lower.contains("invalid login credentials") ||
                lower.contains("invalid_credentials")
            )
    }

    fun isRateLimited(): Boolean {
        val lower = readableMessage().lowercase(Locale.US)
        return statusCode == 429 ||
            lower.contains("rate limit") ||
            lower.contains("too many")
    }

    fun readableMessage(): String {
        return errorJson?.let { json ->
            json.optString("msg")
                .takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("error_description").takeIf { it.isNotBlank() }
                ?: json.optString("code").takeIf { it.isNotBlank() }
        } ?: message.orEmpty()
    }
}
