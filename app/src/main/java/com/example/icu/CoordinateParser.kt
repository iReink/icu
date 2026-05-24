package com.example.icu

import android.net.Uri
import org.osmdroid.util.GeoPoint
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.abs

object CoordinateParser {
    private val decimalNumber = """[-+]?\d{1,3}(?:[.,]\d+)?"""
    private val decimalPairRegex = Regex("""($decimalNumber)\s*[,;\s]\s*($decimalNumber)""")
    private val geoRegex = Regex("""geo:\s*($decimalNumber)\s*,\s*($decimalNumber)""", RegexOption.IGNORE_CASE)
    private val dmsRegex = Regex(
        """(\d{1,3})[°\s]+(\d{1,2})['′\s]+(\d{1,2}(?:[.,]\d+)?)?["″\s]*([NS])\D+(\d{1,3})[°\s]+(\d{1,2})['′\s]+(\d{1,2}(?:[.,]\d+)?)?["″\s]*([EW])""",
        RegexOption.IGNORE_CASE
    )
    private val dmRegex = Regex(
        """(\d{1,3})[°\s]+(\d{1,2}(?:[.,]\d+)?)['′\s]*([NS])\D+(\d{1,3})[°\s]+(\d{1,2}(?:[.,]\d+)?)['′\s]*([EW])""",
        RegexOption.IGNORE_CASE
    )

    fun parseFirst(text: String): GeoPoint? {
        val decoded = decode(text.trim())
        parseGeoUri(decoded)?.let { return it }
        parseQueryParams(decoded)?.let { return it }
        parseDms(decoded)?.let { return it }
        parseDegreesMinutes(decoded)?.let { return it }
        return parseDecimal(decoded)
    }

    private fun parseGeoUri(text: String): GeoPoint? {
        val match = geoRegex.find(text) ?: return null
        return normalized(match.groupValues[1].num(), match.groupValues[2].num())
    }

    private fun parseQueryParams(text: String): GeoPoint? {
        val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
        val mlat = uri.getQueryParameter("mlat")?.num()
        val mlon = uri.getQueryParameter("mlon")?.num()
        if (mlat != null && mlon != null) return normalized(mlat, mlon)
        val ll = uri.getQueryParameter("ll")
        if (!ll.isNullOrBlank()) parseDecimal(ll)?.let { return it }
        val q = uri.getQueryParameter("q")
        if (!q.isNullOrBlank()) parseDecimal(q)?.let { return it }
        return null
    }

    private fun parseDecimal(text: String): GeoPoint? {
        decimalPairRegex.findAll(text).forEach { match ->
            normalized(match.groupValues[1].num(), match.groupValues[2].num())?.let { return it }
        }
        return null
    }

    private fun parseDms(text: String): GeoPoint? {
        val match = dmsRegex.find(text) ?: return null
        val lat = dmsToDecimal(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
        val lon = dmsToDecimal(match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8])
        return normalized(lat, lon)
    }

    private fun parseDegreesMinutes(text: String): GeoPoint? {
        val match = dmRegex.find(text) ?: return null
        val lat = dmToDecimal(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        val lon = dmToDecimal(match.groupValues[4], match.groupValues[5], match.groupValues[6])
        return normalized(lat, lon)
    }

    private fun normalized(first: Double?, second: Double?): GeoPoint? {
        if (first == null || second == null) return null
        val latitude: Double
        val longitude: Double
        if (abs(first) > 90 && abs(second) <= 90) {
            latitude = second
            longitude = first
        } else {
            latitude = first
            longitude = second
        }
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return GeoPoint(latitude, longitude)
    }

    private fun dmsToDecimal(degrees: String, minutes: String, seconds: String, hemisphere: String): Double {
        val value = degrees.num().orZero() + minutes.num().orZero() / 60.0 + seconds.num().orZero() / 3600.0
        return if (hemisphere.uppercase(Locale.US) == "S" || hemisphere.uppercase(Locale.US) == "W") -value else value
    }

    private fun dmToDecimal(degrees: String, minutes: String, hemisphere: String): Double {
        val value = degrees.num().orZero() + minutes.num().orZero() / 60.0
        return if (hemisphere.uppercase(Locale.US) == "S" || hemisphere.uppercase(Locale.US) == "W") -value else value
    }

    private fun String.num(): Double? = replace(',', '.').toDoubleOrNull()
    private fun Double?.orZero(): Double = this ?: 0.0

    private fun decode(value: String): String {
        return runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
    }
}
