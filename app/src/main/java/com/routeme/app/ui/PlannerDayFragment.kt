package com.routeme.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.routeme.app.R
import com.routeme.app.model.PlannedClient
import com.routeme.app.model.PlannedDay

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
            onDragStarted = { (activity as? WeeklyPlannerActivity)?.showDayPickerBar() }
        )

        val rv = view.findViewById<RecyclerView>(R.id.chipRecyclerView)
        rv.layoutManager = GridLayoutManager(requireContext(), 2)
        rv.adapter = chipAdapter

        plannedDay?.let { bindDay(it) }
    }

    fun updateDay(day: PlannedDay) {
        plannedDay = day
        view?.let { bindDay(day) }
    }

    private fun bindDay(day: PlannedDay) {
        val v = view ?: return
        val weatherHeader = v.findViewById<TextView>(R.id.weatherHeader)
        val scoreBadge = v.findViewById<TextView>(R.id.dayScoreBadge)
        val rv = v.findViewById<RecyclerView>(R.id.chipRecyclerView)
        val emptyText = v.findViewById<TextView>(R.id.emptyText)

        // Weather
        val forecast = day.forecast
        weatherHeader.text = if (forecast != null) {
            "${forecast.toWeatherEmoji()} ${forecast.highTempF}°/${forecast.lowTempF}° • Rain ${forecast.precipProbabilityPct}% • Wind ${forecast.windSpeedMph} mph"
        } else {
            "Forecast unavailable"
        }

        // Score badge
        scoreBadge.text = "${day.dayScoreLabel} (${day.dayScore})"
        val badgeColor = when {
            day.dayScore >= 80 -> 0xFF4CAF50.toInt()
            day.dayScore >= 60 -> 0xFF8BC34A.toInt()
            day.dayScore >= 40 -> 0xFFFFC107.toInt()
            else -> 0xFFF44336.toInt()
        }
        val bg = scoreBadge.background?.mutate()
        if (bg is android.graphics.drawable.GradientDrawable) bg.setColor(badgeColor)

        // Clients
        if (day.clients.isEmpty() || !day.isWorkDay) {
            rv.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            emptyText.text = if (!day.isWorkDay) "Not a work day" else "No clients scheduled"
        } else {
            rv.visibility = View.VISIBLE
            emptyText.visibility = View.GONE
            chipAdapter.submitList(day.clients)
        }
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

    companion object {
        private const val ARG_DAY_INDEX = "day_index"

        fun newInstance(dayIndex: Int): PlannerDayFragment {
            return PlannerDayFragment().apply {
                arguments = Bundle().apply { putInt(ARG_DAY_INDEX, dayIndex) }
            }
        }
    }
}
