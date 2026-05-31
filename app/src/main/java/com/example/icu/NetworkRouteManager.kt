package com.example.icu

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

enum class NetworkRoute {
    DIRECT,
    PROXY
}

object NetworkRouteManager {
    const val PROXY_BASE_URL = "https://icu-proxy.185-92-181-109.sslip.io"
    const val PROXY_HOST = "icu-proxy.185-92-181-109.sslip.io"

    private const val PREFS_NAME = "network_route"
    private const val KEY_CURRENT_ROUTE = "current_route"
    private const val KEY_LAST_DIRECT_PROBE_AT = "last_direct_probe_at"
    private const val DIRECT_RECHECK_INTERVAL_MS = 5 * 60 * 1000L
    private const val HEALTH_TIMEOUT_MS = 5_000

    private lateinit var appContext: Context
    private val proxySuccessCount = AtomicLong(0L)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun currentRoute(): NetworkRoute {
        if (!::appContext.isInitialized) return NetworkRoute.DIRECT
        val raw = prefs().getString(KEY_CURRENT_ROUTE, NetworkRoute.DIRECT.name)
        return runCatching { NetworkRoute.valueOf(raw ?: NetworkRoute.DIRECT.name) }
            .getOrDefault(NetworkRoute.DIRECT)
    }

    fun shouldUseProxy(): Boolean = currentRoute() == NetworkRoute.PROXY

    fun markProxyRequired(): Boolean {
        val changed = currentRoute() != NetworkRoute.PROXY
        if (::appContext.isInitialized) {
            prefs().edit()
                .putString(KEY_CURRENT_ROUTE, NetworkRoute.PROXY.name)
                .apply()
        }
        return changed
    }

    fun markDirectAvailable() {
        if (::appContext.isInitialized) {
            prefs().edit()
                .putString(KEY_CURRENT_ROUTE, NetworkRoute.DIRECT.name)
                .apply()
        }
    }

    fun proxySuccessCount(): Long = proxySuccessCount.get()

    fun markSuccessfulRequest(route: NetworkRoute) {
        if (route == NetworkRoute.PROXY) {
            proxySuccessCount.incrementAndGet()
        }
    }

    fun resolveSupabaseUrl(originalUrl: String, route: NetworkRoute): String {
        if (route == NetworkRoute.DIRECT) return originalUrl
        val suffix = originalUrl.removePrefix(SupabaseConfig.PROJECT_URL)
        return "$PROXY_BASE_URL/supabase$suffix"
    }

    fun functionUrl(path: String): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val base = if (shouldUseProxy()) PROXY_BASE_URL else SupabaseConfig.PROJECT_URL
        return "$base$normalizedPath"
    }

    fun shouldProbeDirect(): Boolean {
        if (currentRoute() != NetworkRoute.PROXY || !::appContext.isInitialized) return false
        val now = System.currentTimeMillis()
        val lastProbeAt = prefs().getLong(KEY_LAST_DIRECT_PROBE_AT, 0L)
        return now - lastProbeAt >= DIRECT_RECHECK_INTERVAL_MS
    }

    fun probeDirectSupabase(): Boolean {
        if (!::appContext.isInitialized) return false
        prefs().edit().putLong(KEY_LAST_DIRECT_PROBE_AT, System.currentTimeMillis()).apply()
        return try {
            val connection = (URL("${SupabaseConfig.PROJECT_URL}/auth/v1/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HEALTH_TIMEOUT_MS
                readTimeout = HEALTH_TIMEOUT_MS
            }
            connection.responseCode
            connection.disconnect()
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
