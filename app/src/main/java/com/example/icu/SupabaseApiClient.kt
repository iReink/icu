package com.example.icu

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class SupabaseApiClient(
    private val sessionStore: SupabaseSessionStore
) {
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
            url = "${SupabaseConfig.PROJECT_URL}/auth/v1/signup",
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

class SupabaseException(
    val statusCode: Int,
    message: String
) : Exception(message) {
    fun isInvalidCredentials(): Boolean {
        val lower = message.orEmpty().lowercase(Locale.US)
        return statusCode == 400 && (
            lower.contains("invalid login credentials") ||
                lower.contains("invalid_credentials")
            )
    }
}
