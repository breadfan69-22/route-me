package com.routeme.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.routeme.app.data.PreferencesRepository
import org.koin.android.ext.android.inject

class SyncSheetActivity : AppCompatActivity() {

    private val preferencesRepository: PreferencesRepository by inject()

    private lateinit var presetSpinner: Spinner
    private lateinit var readUrlLayout: TextInputLayout
    private lateinit var readUrlInput: TextInputEditText
    private lateinit var writeUrlLayout: TextInputLayout
    private lateinit var writeUrlInput: TextInputEditText
    private lateinit var saveButton: MaterialButton
    private lateinit var syncNowButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_sheet)

        val toolbar = findViewById<MaterialToolbar>(R.id.topToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        presetSpinner = findViewById(R.id.presetSpinner)
        readUrlLayout = findViewById(R.id.readUrlLayout)
        readUrlInput = findViewById(R.id.readUrlInput)
        writeUrlLayout = findViewById(R.id.writeUrlLayout)
        writeUrlInput = findViewById(R.id.writeUrlInput)
        saveButton = findViewById(R.id.saveButton)
        syncNowButton = findViewById(R.id.syncNowButton)

        setupPresetSpinner()

        saveButton.setOnClickListener {
            saveUrls()
            setResult(RESULT_OK)
            finish()
        }

        syncNowButton.setOnClickListener {
            val (readUrl, writeUrl) = resolveUrls()
            saveUrls()
            setResult(
                RESULT_OK,
                Intent().apply {
                    putExtra(EXTRA_SYNC_REQUESTED, true)
                    putExtra(EXTRA_READ_URL, readUrl)
                    putExtra(EXTRA_WRITE_URL, writeUrl)
                }
            )
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupPresetSpinner() {
        val presets = PreferencesRepository.SHEET_PRESETS
        val customLabel = getString(R.string.sheet_preset_custom)
        val spinnerItems = presets.map { it.label } + customLabel

        presetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, spinnerItems)

        val currentReadUrl = preferencesRepository.sheetsReadUrl
        val currentWriteUrl = preferencesRepository.sheetsWriteUrl
        val matchedPreset = PreferencesRepository.findMatchingPreset(currentReadUrl)
        val initialIndex = if (matchedPreset != null) presets.indexOf(matchedPreset) else spinnerItems.lastIndex

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isCustom = position >= presets.size
                readUrlLayout.visibility = if (isCustom) View.VISIBLE else View.GONE
                writeUrlLayout.visibility = if (isCustom) View.VISIBLE else View.GONE
                if (isCustom) {
                    if (readUrlInput.text.isNullOrBlank() && matchedPreset == null && currentReadUrl.isNotBlank()) {
                        readUrlInput.setText(currentReadUrl)
                    }
                    if (writeUrlInput.text.isNullOrBlank() && matchedPreset == null && currentWriteUrl.isNotBlank()) {
                        writeUrlInput.setText(currentWriteUrl)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        presetSpinner.setSelection(initialIndex)
    }

    private fun resolveUrls(): Pair<String, String> {
        val presets = PreferencesRepository.SHEET_PRESETS
        val pos = presetSpinner.selectedItemPosition
        return if (pos < presets.size) {
            val preset = presets[pos]
            preset.readUrl to preset.writeUrl
        } else {
            readUrlInput.text.toString().trim() to writeUrlInput.text.toString().trim()
        }
    }

    private fun saveUrls() {
        val (readUrl, writeUrl) = resolveUrls()
        preferencesRepository.sheetsReadUrl = readUrl
        preferencesRepository.sheetsWriteUrl = writeUrl
    }

    companion object {
        const val EXTRA_SYNC_REQUESTED = "sync_requested"
        const val EXTRA_READ_URL = "read_url"
        const val EXTRA_WRITE_URL = "write_url"

        fun createIntent(context: Context): Intent =
            Intent(context, SyncSheetActivity::class.java)

        data class SyncSheetResult(
            val syncRequested: Boolean,
            val readUrl: String,
            val writeUrl: String
        )

        fun extractResult(intent: Intent?): SyncSheetResult? {
            if (intent == null) return null
            return SyncSheetResult(
                syncRequested = intent.getBooleanExtra(EXTRA_SYNC_REQUESTED, false),
                readUrl = intent.getStringExtra(EXTRA_READ_URL).orEmpty(),
                writeUrl = intent.getStringExtra(EXTRA_WRITE_URL).orEmpty()
            )
        }
    }
}
