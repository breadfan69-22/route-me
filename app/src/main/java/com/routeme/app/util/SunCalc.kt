package com.routeme.app.util

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Simple sunrise/sunset calculator using NOAA's approximation algorithm.
 * Accurate to within a few minutes for typical lawn-care latitudes (US).
 */
object SunCalc {

    /**
     * Returns true if the current local time is between sunrise and sunset for the given coordinates.
     */
    fun isDaytime(lat: Double, lng: Double): Boolean {
        val zone = ZoneId.systemDefault()
        val now = LocalTime.now(zone)
        val today = LocalDate.now(zone)
        val (sunrise, sunset) = sunriseSunset(lat, lng, today) ?: return true // default to day if calculation fails
        return now.isAfter(sunrise) && now.isBefore(sunset)
    }

    /**
     * Calculate sunrise and sunset times for a given date and location.
     * Returns (sunrise, sunset) as LocalTime, or null if sun never rises/sets (polar regions).
     */
    fun sunriseSunset(lat: Double, lng: Double, date: LocalDate): Pair<LocalTime, LocalTime>? {
        val dayOfYear = date.dayOfYear
        val zenith = 90.833 // official sunrise/sunset: center of sun is 90.833° below horizon

        // Convert longitude to hour value and calculate approximate time
        val lngHour = lng / 15.0

        // Calculate time of mid-day (approximate)
        val tRise = dayOfYear + ((6 - lngHour) / 24)
        val tSet = dayOfYear + ((18 - lngHour) / 24)

        // Sun's mean anomaly
        val mRise = (0.9856 * tRise) - 3.289
        val mSet = (0.9856 * tSet) - 3.289

        // Sun's true longitude
        fun trueLong(m: Double): Double {
            var l = m + (1.916 * sin(Math.toRadians(m))) + (0.020 * sin(Math.toRadians(2 * m))) + 282.634
            if (l > 360) l -= 360
            if (l < 0) l += 360
            return l
        }

        val lRise = trueLong(mRise)
        val lSet = trueLong(mSet)

        // Sun's right ascension
        fun rightAscension(l: Double): Double {
            var ra = Math.toDegrees(kotlin.math.atan(0.91764 * tan(Math.toRadians(l))))
            if (ra > 360) ra -= 360
            if (ra < 0) ra += 360
            // RA must be in same quadrant as L
            val lQuad = (floor(l / 90)) * 90
            val raQuad = (floor(ra / 90)) * 90
            ra += (lQuad - raQuad)
            return ra / 15 // convert to hours
        }

        val raRise = rightAscension(lRise)
        val raSet = rightAscension(lSet)

        // Sun's declination
        fun declination(l: Double): Double {
            val sinDec = 0.39782 * sin(Math.toRadians(l))
            return Math.toDegrees(kotlin.math.asin(sinDec))
        }

        val decRise = declination(lRise)
        val decSet = declination(lSet)

        // Local hour angle
        fun hourAngle(dec: Double, forRise: Boolean): Double? {
            val cosH = (cos(Math.toRadians(zenith)) - (sin(Math.toRadians(lat)) * sin(Math.toRadians(dec)))) /
                    (cos(Math.toRadians(lat)) * cos(Math.toRadians(dec)))
            if (cosH > 1 || cosH < -1) return null // sun never rises/sets
            val h = Math.toDegrees(acos(cosH))
            return if (forRise) (360 - h) / 15 else h / 15
        }

        val hRise = hourAngle(decRise, true) ?: return null
        val hSet = hourAngle(decSet, false) ?: return null

        // Local mean time
        val localTimeRise = hRise + raRise - (0.06571 * tRise) - 6.622
        val localTimeSet = hSet + raSet - (0.06571 * tSet) - 6.622

        // Adjust to UTC, then to local timezone
        var utRise = localTimeRise - lngHour
        var utSet = localTimeSet - lngHour
        if (utRise < 0) utRise += 24
        if (utRise > 24) utRise -= 24
        if (utSet < 0) utSet += 24
        if (utSet > 24) utSet -= 24

        // Convert to local time zone
        val zone = ZoneId.systemDefault()
        val offsetSeconds = zone.rules.getOffset(date.atStartOfDay(zone).toInstant()).totalSeconds
        val offsetHours = offsetSeconds / 3600.0

        var localRise = utRise + offsetHours
        var localSet = utSet + offsetHours
        if (localRise < 0) localRise += 24
        if (localRise > 24) localRise -= 24
        if (localSet < 0) localSet += 24
        if (localSet > 24) localSet -= 24

        fun hoursToLocalTime(h: Double): LocalTime {
            val hours = h.toInt()
            val minutes = ((h - hours) * 60).toInt()
            return LocalTime.of(hours.coerceIn(0, 23), minutes.coerceIn(0, 59))
        }

        return hoursToLocalTime(localRise) to hoursToLocalTime(localSet)
    }
}
