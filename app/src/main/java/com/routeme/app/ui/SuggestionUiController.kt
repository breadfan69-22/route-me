package com.routeme.app.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.routeme.app.ClientSuggestion
import com.routeme.app.R
import com.routeme.app.databinding.SectionSuggestionsBinding
import java.util.Locale

class SuggestionUiController(
    private val binding: SectionSuggestionsBinding,
    private val viewModel: MainViewModel
) {
    fun bindPaginationActions() {
        binding.moreSuggestionsButton.setOnClickListener {
            viewModel.nextSuggestionPage()
            showCurrentPage()
        }

        binding.prevSuggestionsButton.setOnClickListener {
            viewModel.previousSuggestionPage()
            showCurrentPage()
        }
    }

    fun showCurrentPage() {
        binding.suggestionsContainer.removeAllViews()

        val state = viewModel.uiState.value
        val page = viewModel.currentPageSuggestions()
        if (page.isEmpty()) {
            binding.paginationRow.visibility = View.GONE
            return
        }

        val context = binding.root.context

        val stepsLabel = state.selectedServiceTypes.joinToString("+") { it.label }
        val header = TextView(context).apply {
            text = context.getString(
                R.string.suggestion_header,
                stepsLabel,
                state.minDays,
                state.suggestionOffset + 1,
                state.suggestionOffset + page.size,
                state.suggestions.size
            )
            setTypeface(null, Typeface.BOLD)
            textSize = 13f
        }
        binding.suggestionsContainer.addView(header)

        page.forEachIndexed { indexOnPage, suggestion ->
            val globalIndex = state.suggestionOffset + indexOnPage
            binding.suggestionsContainer.addView(
                buildSuggestionRow(
                    suggestion = suggestion,
                    index = globalIndex,
                    selectedClientId = state.selectedClient?.id,
                    selectedServiceTypeCount = state.selectedServiceTypes.size
                )
            )
        }

        val hasMore = viewModel.canShowMoreSuggestions()
        val hasPrev = viewModel.canShowPreviousSuggestions()
        binding.paginationRow.visibility = if (hasMore || hasPrev) View.VISIBLE else View.GONE
        binding.prevSuggestionsButton.isEnabled = hasPrev
        binding.moreSuggestionsButton.isEnabled = hasMore
    }

    private fun buildSuggestionRow(
        suggestion: ClientSuggestion,
        index: Int,
        selectedClientId: String?,
        selectedServiceTypeCount: Int
    ): MaterialButton {
        val daysText = suggestion.daysSinceLast?.toString() ?: "Never"
        val driveText = suggestion.drivingTime
        val distText = when {
            driveText != null -> "${suggestion.drivingDistance} (${driveText})"
            suggestion.distanceMiles != null -> String.format(Locale.US, "%.1f mi", suggestion.distanceMiles)
            else -> ""
        }
        val mowText = if (suggestion.mowWindowPreferred) " ✓mow" else ""
        val cuText = if (suggestion.requiresCuOverride) " ⚠CU" else ""
        val stepTag = if (suggestion.eligibleSteps.size == 1 && selectedServiceTypeCount == 1) {
            ""
        } else {
            val numbers = suggestion.eligibleSteps
                .filter { it.stepNumber > 0 }
                .map { it.stepNumber }
                .sorted()
            if (numbers.isNotEmpty()) "[S${numbers.joinToString("+")}] " else ""
        }

        val label = "${index + 1}. $stepTag${suggestion.client.name}  •  ${daysText}d  •  $distText$mowText$cuText".trim()
        val isSelected = selectedClientId == suggestion.client.id
        val context = binding.root.context

        return MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            textAlignment = View.TEXT_ALIGNMENT_TEXT_START

            val topMarginPx = (4 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = topMarginPx
            }

            if (isSelected) {
                setBackgroundColor(ContextCompat.getColor(context, R.color.suggestion_selected_bg))
                setTextColor(ContextCompat.getColor(context, R.color.md_theme_dark_onPrimaryContainer))
                strokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.suggestion_selected_stroke)
                )
                strokeWidth = (2 * resources.displayMetrics.density).toInt()
                setTypeface(null, Typeface.BOLD)
            } else {
                strokeWidth = (1 * resources.displayMetrics.density).toInt()
            }

            setOnClickListener {
                selectSuggestion(suggestion)
                showCurrentPage()
            }
        }
    }

    private fun selectSuggestion(suggestion: ClientSuggestion) {
        viewModel.selectSuggestion(suggestion.client.id)
    }
}