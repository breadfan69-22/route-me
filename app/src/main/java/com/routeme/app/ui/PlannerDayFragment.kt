package com.routeme.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.routeme.app.R
import com.routeme.app.model.PlannedClient
import com.routeme.app.model.PlannedDay
import com.routeme.app.model.RouteItem

class PlannerDayFragment : Fragment() {

    private var dayIndex: Int = 0
    private var plannedDay: PlannedDay? = null
    private lateinit var chipAdapter: PlannerChipAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dayIndex = arguments?.getInt(ARG_DAY_INDEX, 0) ?: 0
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_planner_day, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chipAdapter = PlannerChipAdapter(
            dayIndex = dayIndex,
            onChipTap = ::showClientDetails,
            onDragStarted = { (activity as? WeeklyPlannerActivity)?.showDayPickerBar() },
            onRemoveClient = { client ->
                (activity as? WeeklyPlannerActivity)?.removeClient(dayIndex, client.client.id)
            },
            onToggleLock = { client ->
                (activity as? WeeklyPlannerActivity)?.toggleClientLock(dayIndex, client.client.id)
            },
            onSupplyHouseTap = ::navigateToSupplyHouse
        )

        val rv = view.findViewById<RecyclerView>(R.id.chipRecyclerView)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = chipAdapter

        // Drag to reorder (handle) + swipe left/right to dismiss
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun isLongPressDragEnabled() = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                chipAdapter.onItemDragTo(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                chipAdapter.removeAt(viewHolder.bindingAdapterPosition)
            }

            override fun canDropOver(
                recyclerView: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                // Don’t allow dropping a client onto a locked client’s position
                val targetPos = target.bindingAdapterPosition
                return targetPos !in chipAdapter.getDraggedList().indices ||
                    !chipAdapter.getDraggedList()[targetPos].locked
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                (activity as? WeeklyPlannerActivity)?.reorderDay(dayIndex, chipAdapter.getDraggedList())
            }
        })
        itemTouchHelper.attachToRecyclerView(rv)
        chipAdapter.onStartDrag = { holder -> itemTouchHelper.startDrag(holder) }

        view.findViewById<Chip>(R.id.anchorChip).setOnClickListener { showAnchorDialog() }
        view.findViewById<Chip>(R.id.reorderChip).setOnClickListener {
            (activity as? WeeklyPlannerActivity)?.reoptimiseRoute(dayIndex)
        }
        view.findViewById<Chip>(R.id.refillChip).setOnClickListener {
            (activity as? WeeklyPlannerActivity)?.refillDay(dayIndex)
        }
        view.findViewById<Chip>(R.id.rebuildChip).setOnClickListener { confirmRebuild() }

        // Pull data from Activity — survives fragment recreation by ViewPager2
        val day = (activity as? WeeklyPlannerActivity)?.getPlannedDay(dayIndex)
        if (day != null) bindDay(day)
    }

    fun updateDay(day: PlannedDay) {
        plannedDay = day
        view?.let { bindDay(day) }
    }

    private fun bindDay(day: PlannedDay) {
        val v = view ?: return
        plannedDay = day
        val weatherHeader = v.findViewById<TextView>(R.id.weatherHeader)
        val scoreBadge = v.findViewById<TextView>(R.id.dayScoreBadge)
        val rv = v.findViewById<RecyclerView>(R.id.chipRecyclerView)
        val emptyText = v.findViewById<TextView>(R.id.emptyText)
        val anchorChip = v.findViewById<Chip>(R.id.anchorChip)

        // Weather
        val forecast = day.forecast
        weatherHeader.text = if (forecast != null) {
            "${forecast.toWeatherEmoji()} ${forecast.highTempF}°/${forecast.lowTempF}° • Rain ${forecast.precipProbabilityPct}% • Wind ${forecast.windSpeedMph} mph"
        } else {
            "Forecast unavailable"
        }

        // Score badge
        val refillBadge = if (day.supplyStopNeeded) " • 📦" else ""
        scoreBadge.text = "${day.dayScoreLabel} (${day.dayScore})$refillBadge"
        val badgeColor = when {
            day.dayScore >= 80 -> 0xFF4CAF50.toInt()
            day.dayScore >= 60 -> 0xFF8BC34A.toInt()
            day.dayScore >= 40 -> 0xFFFFC107.toInt()
            else -> 0xFFF44336.toInt()
        }
        val bg = scoreBadge.background?.mutate()
        if (bg is android.graphics.drawable.GradientDrawable) bg.setColor(badgeColor)

        // Anchor chip
        if (day.anchorLabel != null) {
            anchorChip.text = "📍 ${day.anchorLabel}"
            anchorChip.isCloseIconVisible = true
            anchorChip.setOnCloseIconClickListener {
                (activity as? WeeklyPlannerActivity)?.clearAnchor(dayIndex)
            }
        } else {
            anchorChip.text = "📍 Set anchor"
            anchorChip.isCloseIconVisible = false
        }

        // Clients
        if (day.clients.isEmpty() || !day.isWorkDay) {
            rv.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            emptyText.text = if (!day.isWorkDay) "Not a work day" else "No clients scheduled"
        } else {
            rv.visibility = View.VISIBLE
            emptyText.visibility = View.GONE
            chipAdapter.submitList(buildRouteItemList(day))
        }
    }

    /** Builds the route item list, inserting supply house stop at the right position if needed. */
    private fun buildRouteItemList(day: PlannedDay): List<RouteItem> {
        val items = mutableListOf<RouteItem>()
        day.clients.forEachIndexed { index, plannedClient ->
            items += RouteItem.ClientStop(plannedClient)
            // Insert supply house after this client if this is the insertion point
            if (day.supplyStopAfterIndex == index) {
                items += RouteItem.SupplyHouseStop()
            }
        }
        return items
    }

    private fun confirmRebuild() {
        val day = plannedDay ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rebuild ${day.dayName}?")
            .setMessage("Replace all ${day.clients.size} clients with different picks.")
            .setPositiveButton("Rebuild") { _, _ ->
                (activity as? WeeklyPlannerActivity)?.rebuildDay(dayIndex)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAnchorDialog() {
        val plannerActivity = activity as? WeeklyPlannerActivity ?: return
        val day = plannedDay ?: return

        val zoneOptions = plannerActivity.getAvailableZones()
        if (zoneOptions.isEmpty()) return

        // Option list: zone centroids + "Custom address…"
        val labels = zoneOptions.map { it.label }.toTypedArray() + "Custom address…"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Set anchor for ${day.dayName}")
            .setItems(labels) { _, which ->
                if (which < zoneOptions.size) {
                    val zone = zoneOptions[which]
                    plannerActivity.setAnchor(dayIndex, zone.lat, zone.lng, zone.label)
                } else {
                    showCustomAddressDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomAddressDialog() {
        val plannerActivity = activity as? WeeklyPlannerActivity ?: return
        val day = plannedDay ?: return

        val input = EditText(requireContext()).apply {
            hint = "Address or place name"
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val container = LinearLayout(requireContext()).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Custom anchor for ${day.dayName}")
            .setView(container)
            .setPositiveButton("Geocode & Set") { _, _ ->
                val address = input.text.toString().trim()
                if (address.isNotBlank()) {
                    plannerActivity.geocodeAndSetAnchor(dayIndex, address)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showClientDetails(planned: PlannedClient) {
        val c = planned.client
        val overdue = planned.daysOverdue?.let { "\nOverdue: ${it} days" } ?: ""
        val pinned = if (planned.manuallyPlaced) "\n📌 Manually placed" else ""
        val message = """
            ${c.name}
            ${c.address}
            
            Fitness: ${planned.fitnessLabel} (${planned.fitnessScore})
            ${planned.primaryReason}$overdue$pinned
        """.trimIndent()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(c.name)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun navigateToSupplyHouse(stop: RouteItem.SupplyHouseStop) {
        val uri = Uri.parse("google.navigation:q=${stop.lat},${stop.lng}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage("com.google.android.apps.maps")
        if (intent.resolveActivity(requireActivity().packageManager) != null) {
            startActivity(intent)
        } else {
            // Fallback to browser
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    companion object {
        private const val ARG_DAY_INDEX = "day_index"

        fun newInstance(dayIndex: Int): PlannerDayFragment {
            return PlannerDayFragment().apply {
                arguments = Bundle().apply { putInt(ARG_DAY_INDEX, dayIndex) }
            }
        }
    }
}
