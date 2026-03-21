package com.routeme.app.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.routeme.app.ClientSuggestion
import com.routeme.app.R
import java.util.Locale

class SuggestionSlotAdapter(
    private val onSuggestionClicked: (ClientSuggestion) -> Unit
) : RecyclerView.Adapter<SuggestionSlotAdapter.SuggestionViewHolder>() {

    companion object {
        private const val WEATHER_SUMMARY_MAX_CHARS = 48
    }

    private val items = mutableListOf<ClientSuggestion>()
    private var selectedClientId: String? = null
    private var selectedServiceTypeCount: Int = 1

    fun submit(
        suggestions: List<ClientSuggestion>,
        selectedClientId: String?,
        selectedServiceTypeCount: Int
    ) {
        items.clear()
        items.addAll(suggestions)
        this.selectedClientId = selectedClientId
        this.selectedServiceTypeCount = selectedServiceTypeCount
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val context = parent.context
        val button = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            isAllCaps = false
            textSize = 13f
            textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            val margin = (4 * resources.displayMetrics.density).toInt()
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, margin, 0, margin)
            }
        }
        return SuggestionViewHolder(button)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        val suggestion = items[position]
        holder.bind(
            suggestion = suggestion,
            index = position,
            selectedClientId = selectedClientId,
            selectedServiceTypeCount = selectedServiceTypeCount,
            onSuggestionClicked = onSuggestionClicked
        )
    }

    override fun getItemCount(): Int = items.size

    class SuggestionViewHolder(
        private val button: MaterialButton
    ) : RecyclerView.ViewHolder(button) {

        fun bind(
            suggestion: ClientSuggestion,
            index: Int,
            selectedClientId: String?,
            selectedServiceTypeCount: Int,
            onSuggestionClicked: (ClientSuggestion) -> Unit
        ) {
            val daysText = suggestion.daysSinceLast?.toString() ?: "Never"
            val driveText = suggestion.drivingTime
            val distText = when {
                driveText != null -> "${suggestion.drivingDistance} (${driveText})"
                suggestion.distanceMiles != null -> String.format(Locale.US, "%.1f mi", suggestion.distanceMiles)
                else -> ""
            }
            val mowText = if (suggestion.mowWindowPreferred) " ✓mow" else ""
            val cuText = if (suggestion.requiresCuOverride) " ⚠CU" else ""
            val weatherText = compactWeatherSummary(suggestion.weatherFitSummary)
            val stepTag = if (suggestion.eligibleSteps.size == 1 && selectedServiceTypeCount == 1) {
                ""
            } else {
                val numbers = suggestion.eligibleSteps
                    .filter { it.stepNumber > 0 }
                    .map { it.stepNumber }
                    .sorted()
                if (numbers.isNotEmpty()) "[S${numbers.joinToString("+")}] " else ""
            }

            val topLine = "${index + 1}. $stepTag${suggestion.client.name}  •  ${daysText}d  •  $distText$mowText$cuText".trim()
            button.text = if (weatherText.isNullOrBlank()) {
                topLine
            } else {
                "$topLine\n↳ ${weatherText}"
            }
            val isSelected = selectedClientId == suggestion.client.id
            val context = button.context

            if (isSelected) {
                button.setBackgroundColor(ContextCompat.getColor(context, R.color.suggestion_selected_bg))
                button.setTextColor(ContextCompat.getColor(context, R.color.md_theme_dark_onPrimaryContainer))
                button.strokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.suggestion_selected_stroke)
                )
                button.strokeWidth = (2 * button.resources.displayMetrics.density).toInt()
                button.setTypeface(null, Typeface.BOLD)
                button.alpha = 1f
            } else {
                button.strokeWidth = (1 * button.resources.displayMetrics.density).toInt()
                button.setTypeface(null, Typeface.NORMAL)
                button.alpha = 0.82f
            }

            button.setOnClickListener {
                onSuggestionClicked(suggestion)
            }
        }

        private fun compactWeatherSummary(summary: String?): String? {
            val raw = summary?.trim().orEmpty()
            if (raw.isBlank()) return null

            val firstReason = raw.split(';').firstOrNull()?.trim().orEmpty()
            if (firstReason.isBlank()) return null

            val hasMore = raw.contains(';')
            val trimmed = if (firstReason.length > WEATHER_SUMMARY_MAX_CHARS) {
                firstReason.take(WEATHER_SUMMARY_MAX_CHARS).trimEnd() + "…"
            } else {
                firstReason
            }
            return if (hasMore && !trimmed.endsWith("…")) "$trimmed…" else trimmed
        }
    }
}
