package com.routeme.app.data

import android.content.Context
import com.routeme.app.SavedDestination
import com.routeme.app.ServiceType
import org.json.JSONArray
import org.json.JSONObject

class PreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var sheetsReadUrl: String
        get() {
            val configured = prefs.getString(PREF_SHEETS_READ_URL, null)?.trim().orEmpty()
            return if (configured.isBlank()) DEFAULT_READ_URL else configured
        }
        set(value) = prefs.edit().putString(PREF_SHEETS_READ_URL, value).apply()

    var sheetsWriteUrl: String
        get() {
            val configured = prefs.getString(PREF_SHEETS_WRITE_URL, null)?.trim().orEmpty()
            return if (configured.isBlank()) DEFAULT_WRITE_URL else configured
        }
        set(value) = prefs.edit().putString(PREF_SHEETS_WRITE_URL, value).apply()

    var propertySheetWriteUrl: String
        get() {
            val configured = prefs.getString(PREF_PROPERTY_SHEET_WRITE_URL, null)?.trim().orEmpty()
            return if (configured.isBlank()) DEFAULT_PROPERTY_WRITE_URL else configured
        }
        set(value) = prefs.edit().putString(PREF_PROPERTY_SHEET_WRITE_URL, value).apply()

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

    /** Whether destination-only errands routing mode is enabled. */
    var errandsModeEnabled: Boolean
        get() = prefs.getBoolean(PREF_ERRANDS_MODE, false)
        set(value) = prefs.edit().putBoolean(PREF_ERRANDS_MODE, value).apply()

    /** The epoch day (millis / 86400000) when the step selection was last saved manually. */
    var selectedStepsDate: Long
        get() = prefs.getLong(PREF_SELECTED_STEPS_DATE, 0L)
        set(value) = prefs.edit().putLong(PREF_SELECTED_STEPS_DATE, value).apply()

    // ─── Saved & active destinations ───────────────────────────

    /** Persistent list of saved destination presets (vendors, common locations). */
    var savedDestinations: List<SavedDestination>
        get() {
            val json = prefs.getString(PREF_SAVED_DESTINATIONS, null) ?: return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    SavedDestination(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        address = obj.getString("address"),
                        lat = obj.getDouble("lat"),
                        lng = obj.getDouble("lng")
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { d ->
                arr.put(JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                    put("address", d.address)
                    put("lat", d.lat)
                    put("lng", d.lng)
                })
            }
            prefs.edit().putString(PREF_SAVED_DESTINATIONS, arr.toString()).apply()
        }

    /**
     * The currently active destination (for tracking service dwell detection).
     * Null means no destination is active. This is a transient value set by the
     * ViewModel when a destination queue entry becomes active; it does NOT
     * persist across app restarts (the queue in SavedStateHandle handles that).
     */
    var activeDestination: SavedDestination?
        get() {
            val json = prefs.getString(PREF_ACTIVE_DESTINATION, null) ?: return null
            return try {
                val obj = JSONObject(json)
                SavedDestination(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    address = obj.getString("address"),
                    lat = obj.getDouble("lat"),
                    lng = obj.getDouble("lng")
                )
            } catch (_: Exception) { null }
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(PREF_ACTIVE_DESTINATION).apply()
            } else {
                val obj = JSONObject().apply {
                    put("id", value.id)
                    put("name", value.name)
                    put("address", value.address)
                    put("lat", value.lat)
                    put("lng", value.lng)
                }
                prefs.edit().putString(PREF_ACTIVE_DESTINATION, obj.toString()).apply()
            }
        }

    // ─── Granular application rates ──────────────────────────

    /** Get granular application rate (lbs/1000sqft) for a non-spray step. */
    fun getGranularRate(serviceType: ServiceType): Double {
        return prefs.getFloat("rate_${serviceType.name}_1", 0f).toDouble()
    }

    /** Set granular application rate (lbs/1000sqft) for a non-spray step. */
    fun setGranularRate(serviceType: ServiceType, rate: Double) {
        prefs.edit()
            .putFloat("rate_${serviceType.name}_1", rate.toFloat())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "routeme_prefs"
        private const val PREF_SHEETS_READ_URL = "sheets_read_url"
        private const val PREF_SHEETS_WRITE_URL = "sheets_write_url"
        private const val PREF_PROPERTY_SHEET_WRITE_URL = "property_sheet_write_url"
        private const val DEFAULT_READ_URL = "https://docs.google.com/spreadsheets/d/1yHe6BUUVBV-5PEEXwZolPK-d-kW6x6ZGrcnDfhR-zOY/edit?usp=sharing"
        private const val DEFAULT_WRITE_URL = "https://script.google.com/macros/s/AKfycbwqJDDeurHB6fW7wiAbm6YvtLY3nsTJHenlj0rIfBStWSGcinIxWOOKh8oEqdvTquT_/exec"
        private const val DEFAULT_PROPERTY_WRITE_URL = "https://script.google.com/macros/s/AKfycbwTNiYfKK69jugL-PxhJk-aZaAlzw9DXyjZrs7SX6ZaAxjYymDpBKJZUieKmo-9xsqHQg/exec"
        private const val PREF_NON_CLIENT_LOGGING = "non_client_logging_enabled"
        private const val PREF_NON_CLIENT_THRESHOLD = "non_client_stop_threshold_min"
        private const val PREF_SELECTED_STEPS = "selected_steps"
        private const val PREF_ERRANDS_MODE = "errands_mode"
        private const val PREF_SELECTED_STEPS_DATE = "selected_steps_date"
        private const val PREF_SAVED_DESTINATIONS = "saved_destinations"
        private const val PREF_ACTIVE_DESTINATION = "active_destination"

        data class SheetPreset(val label: String, val readUrl: String, val writeUrl: String)

        val SHEET_PRESETS = listOf(
            SheetPreset(
                label = "2026 Season",
                readUrl = "https://docs.google.com/spreadsheets/d/1yHe6BUUVBV-5PEEXwZolPK-d-kW6x6ZGrcnDfhR-zOY/edit?usp=sharing",
                writeUrl = "https://script.google.com/macros/s/AKfycbwqJDDeurHB6fW7wiAbm6YvtLY3nsTJHenlj0rIfBStWSGcinIxWOOKh8oEqdvTquT_/exec"
            ),
            SheetPreset(
                label = "2025 Season",
                readUrl = "https://docs.google.com/spreadsheets/d/1Oi7YpqdKwIqQKrl_StnOzCBCPDror28lS3a-gKxRx6g/edit?usp=sharing",
                writeUrl = "https://script.google.com/macros/s/AKfycbyZ3KBiS1eS48eBdNhPDnGz8wZNFryvPYFRVKO-Z2LV6DBhxPFthdzKXTFAb-jrE2oK/exec"
            )
        )

        /** Match a read URL to a preset by spreadsheet ID substring. */
        fun findMatchingPreset(readUrl: String): SheetPreset? {
            return SHEET_PRESETS.firstOrNull { preset ->
                val id = preset.readUrl.substringAfter("/d/").substringBefore("/")
                readUrl.contains(id)
            }
        }
        const val DEFAULT_NON_CLIENT_THRESHOLD = 5
    }
}
