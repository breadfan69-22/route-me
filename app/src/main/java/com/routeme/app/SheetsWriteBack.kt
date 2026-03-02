package com.routeme.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Writes service completion back to a Google Sheet via a Google Apps Script
 * web app endpoint.
 *
 * The Apps Script finds the client row by name and updates the corresponding
 * step column with √M.D (e.g. √3.15 for March 15).
 *
 * Set [webAppUrl] to the deployed Apps Script URL before calling [markDone].
 */
object SheetsWriteBack {

    private const val TAG = "SheetsWriteBack"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 5

    /** Set this to the deployed Apps Script web app URL */
    var webAppUrl: String = ""

    data class WriteResult(
        val success: Boolean,
        val message: String
    )

    private data class HttpResult(
        val code: Int,
        val body: String,
        val transportError: Boolean = false
    )

    /**
     * Writes a persistent note for a client to the "Notes" column in the sheet.
     * Must be called on a background thread.
     */
    fun updateNotes(clientName: String, notes: String): WriteResult {
        if (webAppUrl.isBlank()) {
            return WriteResult(false, "No Apps Script URL configured.")
        }
        return try {
            val json = JSONObject().apply {
                put("clientName", clientName)
                put("column", "Notes")
                put("value", notes)
            }
            val http = postJsonWithRedirects(json, "notes update")
            if (http.code in 200..299) {
                WriteResult(true, "Updated Notes for $clientName")
            } else if (http.transportError) {
                WriteResult(false, http.body)
            } else {
                WriteResult(false, "HTTP ${http.code}: ${http.body.take(100)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Notes write-back failed", e)
            WriteResult(false, "${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
    }

    /**
     * Posts a raw column/value update to the Google Sheet.
     * Used by the retry queue which already has the formatted value (e.g. √3.15).
     * Must be called on a background thread.
     */
    fun postRaw(clientName: String, column: String, value: String): WriteResult {
        if (webAppUrl.isBlank()) {
            return WriteResult(false, "No Apps Script URL configured.")
        }
        return try {
            val json = JSONObject().apply {
                put("clientName", clientName)
                put("column", column)
                put("value", value)
            }
            val http = postJsonWithRedirects(json, "raw retry")
            if (http.code in 200..299) {
                WriteResult(true, "Updated $column for $clientName")
            } else if (http.transportError) {
                WriteResult(false, http.body)
            } else {
                WriteResult(false, "HTTP ${http.code}: ${http.body.take(100)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Raw write-back failed", e)
            WriteResult(false, "${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
    }

    /**
     * Posts a completion to the Google Sheet.
     * Must be called on a background thread.
     *
     * @param clientName  Exact name as it appears in the sheet (column A)
     * @param serviceType The completed service type
     * @param dateMillis  When the service was completed
     */
    fun markDone(
        clientName: String,
        serviceType: ServiceType,
        dateMillis: Long
    ): WriteResult {
        if (webAppUrl.isBlank()) {
            return WriteResult(false, "No Apps Script URL configured.")
        }

        // Build the √M.D value
        val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val checkValue = "\u221A$month.$day"

        // Map service type to column header the script should look for
        val columnHeader = when (serviceType) {
            ServiceType.ROUND_1 -> "Step 1"
            ServiceType.ROUND_2 -> "Step 2"
            ServiceType.ROUND_3 -> "Step 3"
            ServiceType.ROUND_4 -> "Step 4"
            ServiceType.ROUND_5 -> "Step 5"
            ServiceType.ROUND_6 -> "Step 6"
            ServiceType.GRUB -> "Grub"
            ServiceType.INCIDENTAL -> return WriteResult(false, "Incidental services are not tracked in the sheet.")
        }

        return try {
            val json = JSONObject().apply {
                put("clientName", clientName)
                put("column", columnHeader)
                put("value", checkValue)
            }
            val http = postJsonWithRedirects(json, "completion")
            Log.d(TAG, "Final response (${http.code}): ${http.body}")

            if (http.code in 200..299) {
                WriteResult(true, "Updated $columnHeader for $clientName -> $checkValue")
            } else if (http.transportError) {
                WriteResult(false, http.body)
            } else {
                WriteResult(false, "HTTP ${http.code}: ${http.body.take(100)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write-back failed", e)
            WriteResult(false, "${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
    }

    private fun postJsonWithRedirects(json: JSONObject, label: String): HttpResult {
        Log.d(TAG, "Posting $label: $json to $webAppUrl")
        val jsonBytes = json.toString().toByteArray(Charsets.UTF_8)

        var conn = URL(webAppUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = false
        conn.outputStream.use { it.write(jsonBytes) }

        var code = conn.responseCode
        Log.d(TAG, "Initial POST response: $code")

        var remaining = MAX_REDIRECTS
        while (code in 301..303 || code == 307 || code == 308) {
            val location = conn.getHeaderField("Location")
            conn.disconnect()

            if (location == null || remaining-- <= 0) {
                return HttpResult(-1, "Too many redirects or missing Location header", transportError = true)
            }

            Log.d(TAG, "Following redirect ($code) -> $location")
            conn = URL(location).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            code = conn.responseCode
        }

        val body = readBody(conn)
        conn.disconnect()
        return HttpResult(code, body)
    }

    private fun readBody(conn: HttpURLConnection): String {
        return try {
            conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: "No body"
        }
    }
}
