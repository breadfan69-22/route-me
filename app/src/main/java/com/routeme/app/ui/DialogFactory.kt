package com.routeme.app.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.routeme.app.ClusterMember
import com.routeme.app.R
import java.util.Calendar

object DialogFactory {
    fun showDailySummaryDialog(context: Context, summary: String) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_daily_summary_title))
            .setMessage(summary)
            .setPositiveButton(android.R.string.ok, null)
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
        onGoBack: () -> Unit
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
            .setNeutralButton(context.getString(R.string.dialog_stale_go_back)) { _, _ ->
                onGoBack()
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
            .setTitle(context.getString(R.string.dialog_cluster_title))
            .setMessage(context.getString(R.string.dialog_cluster_message, members.size))
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

    fun showSheetsUrlDialog(
        context: Context,
        currentReadUrl: String,
        currentWriteUrl: String,
        onSyncNow: (enteredReadUrl: String, enteredWriteUrl: String) -> Unit
    ) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val syncInput = EditText(context).apply {
            hint = context.getString(R.string.dialog_sheets_read_hint)
            if (currentReadUrl.isNotBlank()) setText(currentReadUrl)
            textSize = 13f
        }
        layout.addView(syncInput)

        val writeInput = EditText(context).apply {
            hint = context.getString(R.string.dialog_sheets_write_hint)
            if (currentWriteUrl.isNotBlank()) setText(currentWriteUrl)
            textSize = 13f
        }
        layout.addView(writeInput)

        AlertDialog.Builder(context)
            .setTitle(R.string.dialog_sheets_title)
            .setMessage(R.string.dialog_sheets_message)
            .setView(layout)
            .setPositiveButton(R.string.dialog_sync_now) { _, _ ->
                onSyncNow(
                    syncInput.text.toString().trim(),
                    writeInput.text.toString().trim()
                )
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}