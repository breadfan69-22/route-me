package com.routeme.app.ui

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.routeme.app.R
import com.routeme.app.databinding.ActivityMainBinding

class SuggestionUiController(
    private val binding: ActivityMainBinding,
    private val viewModel: MainViewModel,
    private val onSuggestionTapped: (com.routeme.app.ClientSuggestion) -> Unit = {}
) {
    private val adapter = SuggestionSlotAdapter(
        onSuggestionClicked = { suggestion ->
            viewModel.selectSuggestion(suggestion.client.id)
            showCurrentPage()
            onSuggestionTapped(suggestion)
        }
    )

    private val snapHelper = LinearSnapHelper()
    private var isInitialized = false

    fun bindPaginationActions() {
        if (isInitialized) return
        isInitialized = true

        binding.suggestionRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
            adapter = this@SuggestionUiController.adapter
        }
        snapHelper.attachToRecyclerView(binding.suggestionRecyclerView)
    }

    fun showCurrentPage() {
        val state = viewModel.uiState.value
        val suggestions = state.suggestions

        if (suggestions.isEmpty()) {
            binding.suggestionHeader.text = binding.root.context.getString(
                R.string.no_eligible_clients,
                state.selectedServiceTypes.joinToString("+") { it.label },
                state.minDays
            )
            binding.suggestionRecyclerView.visibility = View.GONE
            return
        }

        val stepsLabel = state.selectedServiceTypes.joinToString("+") { it.label }
        binding.suggestionHeader.text = binding.root.context.getString(
            R.string.suggestion_header,
            stepsLabel,
            state.minDays,
            1,
            suggestions.size,
            suggestions.size
        )

        binding.suggestionRecyclerView.visibility = View.VISIBLE

        adapter.submit(
            suggestions = suggestions,
            selectedClientId = state.selectedClient?.id,
            selectedServiceTypeCount = state.selectedServiceTypes.size
        )
    }
}
