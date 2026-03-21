package com.routeme.app.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.routeme.app.ClusterMember
import com.routeme.app.PropertyInput
import com.routeme.app.R
import com.routeme.app.ServiceType
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.estimateGranularSqFt
import com.routeme.app.estimateSpraySqFt
import com.routeme.app.isSpray
import java.util.Calendar

object DialogFactory {
    private fun doubleBuzz(context: Context) {
        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 60, 80, 60),
                    intArrayOf(0, 255, 0, 255),
                    -1
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(longArrayOf(0, 60, 80, 60), -1)
        }
    }

    fun showDailySummaryDialog(context: Context, summary: String) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_daily_summary_title))
            .setMessage(summary)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showStartTrackingPrompt(context: Context, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_start_tracking_title))
            .setMessage(context.getString(R.string.dialog_start_tracking_message))
            .setPositiveButton(R.string.dialog_start_tracking_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.dialog_start_tracking_cancel, null)
            .show()
    }

    fun showRouteHistoryDialog(
        context: Context,
        event: MainEvent.ShowRouteHistory,
        onNavigate: (dateMillis: Long, delta: Int) -> Unit,
        onPickDate: (dateMillis: Long) -> Unit = {},
        onWeekSummary: (dateMillis: Long) -> Unit = {}
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val navRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val olderGapLabel = if (event.gapDaysToOlder > 0) " (${event.gapDaysToOlder}d gap)" else ""
        val newerGapLabel = if (event.gapDaysToNewer > 0) " (${event.gapDaysToNewer}d gap)" else ""

        val prevBtn = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "◀ Older$olderGapLabel"
            textSize = 11f
            isAllCaps = false
            isEnabled = event.hasPrevDay
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val dateLabel = TextView(context).apply {
            text = event.dateLabel
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }

        val nextBtn = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Newer$newerGapLabel ▶"
            textSize = 11f
            isAllCaps = false
            isEnabled = event.hasNextDay
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        navRow.addView(prevBtn)
        navRow.addView(dateLabel)
        navRow.addView(nextBtn)
        container.addView(navRow)

        // Action row: Pick Date + Week Summary
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        val pickDateBtn = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "\uD83D\uDCC5 Pick Date"
            textSize = 11f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
        }

        val weekBtn = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "\uD83D\uDCC6 This Week"
            textSize = 11f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
            }
        }

        actionRow.addView(pickDateBtn)
        actionRow.addView(weekBtn)
        container.addView(actionRow)

        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                topMargin = 12
                bottomMargin = 12
            }
            setBackgroundColor(0x33FFFFFF)
        })

        val summaryView = TextView(context).apply {
            text = event.summary
            textSize = 13f
            setLineSpacing(0f, 1.2f)
        }
        container.addView(summaryView)

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_route_history_title))
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        prevBtn.setOnClickListener {
            dialog.dismiss()
            onNavigate(event.dateMillis, 1)
        }

        nextBtn.setOnClickListener {
            dialog.dismiss()
            onNavigate(event.dateMillis, -1)
        }

        pickDateBtn.setOnClickListener {
            dialog.dismiss()
            val cal = Calendar.getInstance().apply { timeInMillis = event.dateMillis }
            DatePickerDialog(
                context,
                { _, year, month, dayOfMonth ->
                    val picked = Calendar.getInstance().apply {
                        set(year, month, dayOfMonth, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPickDate(picked.timeInMillis)
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        weekBtn.setOnClickListener {
            dialog.dismiss()
            onWeekSummary(event.dateMillis)
        }
    }

    fun showWeekSummaryDialog(context: Context, summary: String) {
        AlertDialog.Builder(context)
            .setTitle("Week Summary")
            .setMessage(summary)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showEditNotesDialog(
        context: Context,
        clientId: String,
        clientName: String,
        currentNotes: String,
        onSave: (clientId: String, notes: String) -> Unit
    ) {
        val editText = EditText(context).apply {
            setText(currentNotes)
            hint = "Notes for $clientName"
            setPadding(48, 24, 48, 24)
            minLines = 2
            maxLines = 6
        }

        AlertDialog.Builder(context)
            .setTitle("Edit Notes – $clientName")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                onSave(clientId, editText.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showStaleArrivalDialog(
        context: Context,
        clientName: String,
        minutesElapsed: Long,
        onMarkComplete: () -> Unit,
        onDiscard: () -> Unit,
        onHide: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_stale_arrival_title))
            .setMessage(
                context.getString(
                    R.string.dialog_stale_arrival_message,
                    clientName,
                    minutesElapsed
                )
            )
            .setPositiveButton(context.getString(R.string.dialog_stale_mark_complete)) { _, _ ->
                onMarkComplete()
            }
            .setNegativeButton(context.getString(R.string.dialog_stale_discard)) { _, _ ->
                onDiscard()
            }
            .setNeutralButton(context.getString(R.string.dialog_stale_hide)) { _, _ ->
                onHide()
            }
            .setCancelable(false)
            .show()
    }

    fun showClusterCompletionDialog(
        context: Context,
        members: List<ClusterMember>,
        onConfirmSelection: (selectedMembers: List<ClusterMember>) -> Unit
    ) {
        val names = members.map { member ->
            val mins = (member.timeOnSiteMillis / 60_000).toInt().coerceAtLeast(1)
            "${member.client.name} (${mins}m)"
        }.toTypedArray()
        val checked = BooleanArray(members.size) { true }

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_cluster_title, members.size))
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(context.getString(R.string.dialog_cluster_confirm)) { _, _ ->
                val selected = members.filterIndexed { index, _ -> checked[index] }
                onConfirmSelection(selected)
            }
            .setNegativeButton(context.getString(R.string.dialog_cluster_cancel), null)
            .setCancelable(false)
            .show()
    }

    fun showBreakThresholdDialog(
        context: Context,
        currentThreshold: Int,
        onSave: (minutes: Int) -> Unit
    ) {
        val input = EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentThreshold.toString())
            hint = "Minutes (1–30)"
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(input)
        }

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_break_threshold_title))
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val mins = input.text.toString().toIntOrNull() ?: currentThreshold
                onSave(mins)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showMinDaysDialog(
        context: Context,
        currentMinDays: Int,
        onSave: (days: Int) -> Unit
    ) {
        val input = EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentMinDays.toString())
            hint = "Days (1–90)"
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(input)
        }

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_min_days_title))
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val days = input.text.toString().toIntOrNull() ?: currentMinDays
                onSave(days)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showClientActionDialog(
        context: Context,
        clientName: String,
        details: String,
        arrivalActive: Boolean,
        serviceTypes: Set<ServiceType>,
        granularRate: Double,
        onArrive: () -> Unit,
        onCancelArrival: () -> Unit,
        onMaps: () -> Unit,
        onSkip: () -> Unit,
        onConfirm: (notes: String, amountUsed: Double?, amountUsed2: Double?, property: PropertyInput) -> Unit,
        onEditNotes: () -> Unit
    ) {
        val view = android.view.LayoutInflater.from(context)
            .inflate(R.layout.dialog_client_action, null)

        val nameText = view.findViewById<TextView>(R.id.dialogClientName)
        val detailsText = view.findViewById<TextView>(R.id.dialogClientDetails)
        val arriveBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialogArriveButton)
        val notesLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.dialogVisitNotesLayout)
        val notesInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.dialogVisitNotesInput)
        val mapsBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialogMapsButton)
        val skipBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialogSkipButton)
        val confirmBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialogConfirmButton)
        val editNotesBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialogEditNotesButton)
        val propertyBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialogPropertyButton)

        // Consumable fields
        val consumableSection = view.findViewById<LinearLayout>(R.id.dialogConsumableSection)
        val amount1Layout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.dialogAmount1Layout)
        val amount1Input = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.dialogAmount1Input)
        val amount2Layout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.dialogAmount2Layout)
        val amount2Input = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.dialogAmount2Input)
        val estSqFtLabel = view.findViewById<TextView>(R.id.dialogEstSqFtLabel)

        // Property stat fields
        val propertySection = view.findViewById<LinearLayout>(R.id.dialogPropertySection)
        val sunShadeSpinner = view.findViewById<Spinner>(R.id.dialogSunShadeSpinner)
        val windSpinner = view.findViewById<Spinner>(R.id.dialogWindSpinner)
        val slopesSpinner = view.findViewById<Spinner>(R.id.dialogSteepSlopesSpinner)
        val irrigationSpinner = view.findViewById<Spinner>(R.id.dialogIrrigationSpinner)

        fun setupSpinner(spinner: Spinner, options: List<String>) {
            spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, options).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        setupSpinner(sunShadeSpinner, PropertyInput.SUN_SHADE_OPTIONS)
        setupSpinner(windSpinner, PropertyInput.WIND_OPTIONS)
        setupSpinner(slopesSpinner, PropertyInput.YES_NO_OPTIONS)
        setupSpinner(irrigationSpinner, PropertyInput.YES_NO_OPTIONS)

        nameText.text = clientName
        detailsText.text = details

        val primaryType = serviceTypes.firstOrNull() ?: ServiceType.ROUND_1
        val isSprayer = primaryType.isSpray

        fun updateSections(visible: Boolean) {
            if (!visible) {
                consumableSection.visibility = View.GONE
                propertySection.visibility = View.GONE
                return
            }
            // Consumable section
            if (primaryType == ServiceType.INCIDENTAL) {
                consumableSection.visibility = View.GONE
            } else {
                consumableSection.visibility = View.VISIBLE
                if (isSprayer) {
                    amount1Layout.hint = "Hose (gal)"
                    amount2Layout.visibility = View.VISIBLE
                    amount2Layout.hint = "PG (gal)"
                } else {
                    amount1Layout.hint = "Lbs used"
                    amount2Layout.visibility = View.GONE
                }
            }
            // Property section always visible during arrival
            propertySection.visibility = View.VISIBLE
        }

        fun updateEstimate() {
            val amt1 = amount1Input.text?.toString()?.toDoubleOrNull()
            val amt2 = amount2Input.text?.toString()?.toDoubleOrNull()
            val est = if (isSprayer) {
                estimateSpraySqFt(amt1, amt2)
            } else {
                estimateGranularSqFt(amt1, granularRate)
            }
            if (est != null) {
                estSqFtLabel.text = "≈ %,d sqft".format(est)
                estSqFtLabel.visibility = View.VISIBLE
            } else {
                estSqFtLabel.visibility = View.GONE
            }
        }

        val amountWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateEstimate() }
        }
        amount1Input.addTextChangedListener(amountWatcher)
        amount2Input.addTextChangedListener(amountWatcher)

        if (arrivalActive) {
            arriveBtn.text = context.getString(R.string.dialog_cancel_arrival)
            notesLayout.visibility = View.VISIBLE
            updateSections(true)
        } else {
            arriveBtn.text = context.getString(R.string.dialog_arrive)
            notesLayout.visibility = View.GONE
            updateSections(false)
        }

        propertyBtn.isEnabled = false
        propertyBtn.alpha = 0.4f

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()

        arriveBtn.setOnClickListener {
            if (arrivalActive) {
                onCancelArrival()
                arriveBtn.text = context.getString(R.string.dialog_arrive)
                notesLayout.visibility = View.GONE
                updateSections(false)
            } else {
                onArrive()
                arriveBtn.text = context.getString(R.string.dialog_cancel_arrival)
                notesLayout.visibility = View.VISIBLE
                updateSections(true)
            }
        }
        mapsBtn.setOnClickListener { doubleBuzz(context); onMaps(); dialog.dismiss() }
        skipBtn.setOnClickListener { doubleBuzz(context); onSkip(); dialog.dismiss() }
        confirmBtn.setOnClickListener {
            doubleBuzz(context)
            val notes = notesInput.text?.toString().orEmpty()
            val amt1 = amount1Input.text?.toString()?.toDoubleOrNull()
            val amt2 = amount2Input.text?.toString()?.toDoubleOrNull()
            val property = PropertyInput(
                sunShade = sunShadeSpinner.selectedItem?.toString().orEmpty(),
                windExposure = windSpinner.selectedItem?.toString().orEmpty(),
                steepSlopes = slopesSpinner.selectedItem?.toString().orEmpty(),
                irrigation = irrigationSpinner.selectedItem?.toString().orEmpty()
            )
            onConfirm(notes, amt1, amt2, property)
            dialog.dismiss()
        }
        editNotesBtn.setOnClickListener { doubleBuzz(context); onEditNotes(); dialog.dismiss() }
        propertyBtn.setOnClickListener {
            android.widget.Toast.makeText(context, "Property stats coming soon", android.widget.Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    /**
     * Standalone property-stats dialog launched from the notification action.
     * Does NOT dismiss the "job done?" notification — caller decides that.
     */
    fun showPropertyStatsDialog(
        context: Context,
        clientName: String,
        onSave: (PropertyInput) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun addSpinnerRow(label: String, options: List<String>): android.widget.Spinner {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() }
            }
            val tv = android.widget.TextView(context).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val spinner = android.widget.Spinner(context).apply {
                adapter = android.widget.ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_dropdown_item,
                    options
                )
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f
                )
            }
            row.addView(tv)
            row.addView(spinner)
            container.addView(row)
            return spinner
        }

        val sunShadeSpinner = addSpinnerRow("Sun/Shade", PropertyInput.SUN_SHADE_OPTIONS)
        val windSpinner = addSpinnerRow("Wind", PropertyInput.WIND_OPTIONS)
        val slopesSpinner = addSpinnerRow("Steep Slopes", PropertyInput.YES_NO_OPTIONS)
        val irrigationSpinner = addSpinnerRow("Irrigation", PropertyInput.YES_NO_OPTIONS)

        AlertDialog.Builder(context)
            .setTitle("Property Stats — $clientName")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val property = PropertyInput(
                    sunShade = sunShadeSpinner.selectedItem?.toString().orEmpty(),
                    windExposure = windSpinner.selectedItem?.toString().orEmpty(),
                    steepSlopes = slopesSpinner.selectedItem?.toString().orEmpty(),
                    irrigation = irrigationSpinner.selectedItem?.toString().orEmpty()
                )
                if (property.hasAnyData) onSave(property)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showApplicationRatesDialog(
        context: Context,
        preferencesRepository: PreferencesRepository,
    ) {
        // Only granular (non-spray) steps need configurable rates.
        // Spray steps (2, 5) have fixed rates: Hose = 1 gal/1000sqft, PG = 1 gal/5500sqft.
        val granularSteps = listOf(
            ServiceType.ROUND_1, ServiceType.ROUND_3,
            ServiceType.ROUND_4, ServiceType.ROUND_6, ServiceType.GRUB
        )

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }

        val inputs = mutableListOf<Pair<ServiceType, EditText>>()

        for (step in granularSteps) {
            val current = preferencesRepository.getGranularRate(step)

            val label = TextView(context).apply {
                text = "${step.label} (lbs/1000sqft)"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            container.addView(label)

            val input = EditText(context).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                hint = "Rate"
                textSize = 13f
                if (current > 0.0) setText(current.toBigDecimal().stripTrailingZeros().toPlainString())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(input)
            inputs.add(step to input)
        }

        val note = TextView(context).apply {
            text = "Spray steps (2 & 5) use fixed rates:\nHose = 1 gal/1,000 sqft, PG = 1 gal/5,500 sqft"
            textSize = 12f
            setPadding(0, (12 * context.resources.displayMetrics.density).toInt(), 0, 0)
        }
        container.addView(note)

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Granular Application Rates")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                for ((step, input) in inputs) {
                    val rate = input.text.toString().toDoubleOrNull() ?: 0.0
                    preferencesRepository.setGranularRate(step, rate)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

}
