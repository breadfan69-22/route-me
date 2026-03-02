package com.routeme.app.data

import android.content.Context

class PreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var sheetsReadUrl: String
        get() = prefs.getString(PREF_SHEETS_READ_URL, "") ?: ""
        set(value) = prefs.edit().putString(PREF_SHEETS_READ_URL, value).apply()

    var sheetsWriteUrl: String
        get() = prefs.getString(PREF_SHEETS_WRITE_URL, "") ?: ""
        set(value) = prefs.edit().putString(PREF_SHEETS_WRITE_URL, value).apply()

    /** Whether non-client stop logging is enabled. Default: true. */
    var nonClientLoggingEnabled: Boolean
        get() = prefs.getBoolean(PREF_NON_CLIENT_LOGGING, true)
        set(value) = prefs.edit().putBoolean(PREF_NON_CLIENT_LOGGING, value).apply()

    /** Threshold in minutes before a stationary non-client stop is logged. Default: 5. */
    var nonClientStopThresholdMinutes: Int
        get() = prefs.getInt(PREF_NON_CLIENT_THRESHOLD, DEFAULT_NON_CLIENT_THRESHOLD)
        set(value) = prefs.edit().putInt(PREF_NON_CLIENT_THRESHOLD, value).apply()

    /** Persisted step selection as comma-separated ServiceType names (e.g. "ROUND_1,GRUB"). */
    var selectedSteps: String
        get() = prefs.getString(PREF_SELECTED_STEPS, "") ?: ""
        set(value) = prefs.edit().putString(PREF_SELECTED_STEPS, value).apply()

    /** The epoch day (millis / 86400000) when the step selection was last saved manually. */
    var selectedStepsDate: Long
        get() = prefs.getLong(PREF_SELECTED_STEPS_DATE, 0L)
        set(value) = prefs.edit().putLong(PREF_SELECTED_STEPS_DATE, value).apply()

    companion object {
        private const val PREFS_NAME = "routeme_prefs"
        private const val PREF_SHEETS_READ_URL = "sheets_read_url"
        private const val PREF_SHEETS_WRITE_URL = "sheets_write_url"
        private const val PREF_NON_CLIENT_LOGGING = "non_client_logging_enabled"
        private const val PREF_NON_CLIENT_THRESHOLD = "non_client_stop_threshold_min"
        private const val PREF_SELECTED_STEPS = "selected_steps"
        private const val PREF_SELECTED_STEPS_DATE = "selected_steps_date"
        const val DEFAULT_NON_CLIENT_THRESHOLD = 5
    }
}
