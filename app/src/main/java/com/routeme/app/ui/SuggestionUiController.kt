package com.routeme.app.ui

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.routeme.app.R
import com.routeme.app.databinding.ActivityMainBinding

class SuggestionUiController(
    private val binding: ActivityMainBinding,
    private val viewModel: MainViewModel
) {
    private val adapter = SuggestionSlotAdapter(
        onSuggestionClicked = { suggestion ->
            viewModel.selectSuggestion(suggestion.client.id)
            showCurrentPage()
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

        binding.suggestionRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val layoutManager = recyclerView.layoutManager ?: return
                val snapped = snapHelper.findSnapView(layoutManager) ?: return
                val position = layoutManager.getPosition(snapped)
                val suggestion = viewModel.uiState.value.suggestions.getOrNull(position) ?: return
                if (viewModel.uiState.value.selectedClient?.id != suggestion.client.id) {
                    viewModel.selectSuggestion(suggestion.client.id)
                    showCurrentPage()
                }
            }
        })
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

        val selectedIndex = suggestions.indexOfFirst { it.client.id == state.selectedClient?.id }
        if (selectedIndex >= 0) {
            binding.suggestionRecyclerView.scrollToPosition(selectedIndex)
        }
    }
}
