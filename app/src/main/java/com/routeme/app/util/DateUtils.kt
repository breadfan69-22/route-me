package com.routeme.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateUtils {
    fun dayName(day: Int): String {
        return when (day) {
            java.util.Calendar.SUNDAY -> "Sunday"
            java.util.Calendar.MONDAY -> "Monday"
            java.util.Calendar.TUESDAY -> "Tuesday"
            java.util.Calendar.WEDNESDAY -> "Wednesday"
            java.util.Calendar.THURSDAY -> "Thursday"
            java.util.Calendar.FRIDAY -> "Friday"
            java.util.Calendar.SATURDAY -> "Saturday"
            else -> "Unknown"
        }
    }

    fun formatTimestamp(millis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
    }

    fun formatTime(millis: Long): String {
        return SimpleDateFormat("h:mm a", Locale.US).format(Date(millis))
    }

    fun formatDate(millis: Long): String {
        return SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(millis))
    }

    fun formatDateFull(millis: Long): String {
        return SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date(millis))
    }
}
