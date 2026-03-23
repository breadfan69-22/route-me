package com.routeme.app.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.routeme.app.R
import com.routeme.app.SavedDestination
import com.routeme.app.databinding.ActivityMainBinding

class SuggestionUiController(
    private val binding: ActivityMainBinding,
    private val viewModel: MainViewModel,
    private val onSuggestionTapped: (com.routeme.app.ClientSuggestion) -> Unit = {},
    private val onNavigateToDestination: (SavedDestination) -> Unit = {}
) {
    private val adapter = SuggestionSlotAdapter(
        onSuggestionClicked = { suggestion ->
            viewModel.selectSuggestion(suggestion.client.id)
            showCurrentPage()
            onSuggestionTapped(suggestion)
        }
    )
    private val destinationAdapter = DestinationSlotAdapter(onNavigateToDestination)

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

        if (state.errandsModeEnabled) {
            showErrandsDestinations(state)
            return
        }

        binding.badgeSuggestedRefresh.visibility = View.VISIBLE
        val suggestions = state.suggestions

        if (binding.suggestionRecyclerView.adapter !== adapter) {
            binding.suggestionRecyclerView.adapter = adapter
        }

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

    private fun showErrandsDestinations(state: MainUiState) {
        binding.badgeSuggestedRefresh.visibility = View.GONE

        if (binding.suggestionRecyclerView.adapter !== destinationAdapter) {
            binding.suggestionRecyclerView.adapter = destinationAdapter
        }

        val queue = state.destinationQueue
        if (queue.isEmpty()) {
            binding.suggestionHeader.text = binding.root.context.getString(R.string.status_errands_no_destinations)
            binding.suggestionRecyclerView.visibility = View.GONE
            return
        }

        binding.suggestionHeader.text = binding.root.context.getString(
            R.string.errands_banner_active,
            queue.size
        )
        binding.suggestionRecyclerView.visibility = View.VISIBLE
        destinationAdapter.submit(queue, state.activeDestinationIndex)
    }

    private class DestinationSlotAdapter(
        private val onNavigateTo: (SavedDestination) -> Unit
    ) : RecyclerView.Adapter<DestinationSlotAdapter.DestinationViewHolder>() {

        private val items = mutableListOf<SavedDestination>()
        private var activeIndex: Int = 0

        fun submit(destinations: List<SavedDestination>, activeDestinationIndex: Int) {
            items.clear()
            items.addAll(destinations)
            activeIndex = activeDestinationIndex
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DestinationViewHolder {
            val context = parent.context
            val density = context.resources.displayMetrics.density
            val itemMargin = (4 * density).toInt()

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, itemMargin, 0, itemMargin) }
            }

            val tile = MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                isAllCaps = false
                textSize = 13f
                textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val navBtn = ImageButton(context).apply {
                setImageResource(R.drawable.ic_assistant_navigation)
                imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.md_theme_light_primary)
                )
                val tv = TypedValue()
                if (context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackgroundBorderless, tv, true
                    )) {
                    setBackgroundResource(tv.resourceId)
                } else {
                    background = null
                }
                val pad = (8 * density).toInt()
                setPadding(pad, pad, pad, pad)
                val size = (40 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = (4 * density).toInt()
                }
                contentDescription = context.getString(R.string.navigate_to_destination)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }

            row.addView(tile)
            row.addView(navBtn)
            return DestinationViewHolder(row, tile, navBtn)
        }

        override fun onBindViewHolder(holder: DestinationViewHolder, position: Int) {
            holder.bind(items[position], position, activeIndex, onNavigateTo)
        }

        override fun getItemCount(): Int = items.size

        class DestinationViewHolder(
            row: LinearLayout,
            private val tile: MaterialButton,
            private val navBtn: ImageButton
        ) : RecyclerView.ViewHolder(row) {

            fun bind(
                destination: SavedDestination,
                position: Int,
                activeIndex: Int,
                onNavigateTo: (SavedDestination) -> Unit
            ) {
                val isActive = position == activeIndex
                val prefix = if (isActive) "▶ " else ""
                tile.text = "$prefix${position + 1}. ${destination.name}"
                tile.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
                tile.alpha = if (isActive) 1f else 0.82f
                tile.strokeWidth = ((if (isActive) 2 else 1) * tile.resources.displayMetrics.density).toInt()
                navBtn.setOnClickListener { onNavigateTo(destination) }
            }
        }
    }
}
