package com.routeme.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.routeme.app.R
import com.routeme.app.ServiceType
import com.routeme.app.model.PlannedClient

class PlannerChipAdapter(
    private val dayIndex: Int,
    private val onChipTap: (PlannedClient) -> Unit,
    private val onDragStarted: () -> Unit
) : ListAdapter<PlannedClient, PlannerChipAdapter.ChipViewHolder>(DIFF) {

    class ChipViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fitnessDot: View = view.findViewById(R.id.fitnessDot)
        val chipName: TextView = view.findViewById(R.id.chipName)
        val chipStep: TextView = view.findViewById(R.id.chipStep)
        val chipOverdue: TextView = view.findViewById(R.id.chipOverdue)
        val chipPinned: TextView = view.findViewById(R.id.chipPinned)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_planner_chip, parent, false)
        return ChipViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        val planned = getItem(position)

        holder.chipName.text = planned.client.name

        // Fitness dot color
        val dotColor = when {
            planned.fitnessScore >= 80 -> 0xFF4CAF50.toInt() // green
            planned.fitnessScore >= 60 -> 0xFF8BC34A.toInt() // light green
            planned.fitnessScore >= 40 -> 0xFFFFC107.toInt() // amber
            else -> 0xFFF44336.toInt() // red
        }
        (holder.fitnessDot.background.mutate() as? GradientDrawable)?.setColor(dotColor)

        // Step label
        holder.chipStep.text = formatStepTag(planned.eligibleSteps)
        holder.chipStep.visibility = if (planned.eligibleSteps.isNotEmpty()) View.VISIBLE else View.GONE

        // Overdue
        val overdue = planned.daysOverdue
        if (overdue != null && overdue > 0) {
            holder.chipOverdue.text = "${overdue}d"
            holder.chipOverdue.visibility = View.VISIBLE
        } else {
            holder.chipOverdue.visibility = View.GONE
        }

        // Pinned indicator
        holder.chipPinned.visibility = if (planned.manuallyPlaced) View.VISIBLE else View.GONE

        // Tap → details
        holder.itemView.setOnClickListener { onChipTap(planned) }

        // Long-press → drag
        holder.itemView.setOnLongClickListener { view ->
            val clipData = ClipData(
                ClipDescription("client", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)),
                ClipData.Item("${planned.client.id}|$dayIndex")
            )
            val shadow = View.DragShadowBuilder(view)
            view.startDragAndDrop(clipData, shadow, null, 0)
            view.alpha = 0.3f
            onDragStarted()
            true
        }
    }

    private fun formatStepTag(steps: Set<ServiceType>): String {
        if (steps.isEmpty()) return ""
        val sorted = steps.sortedBy { it.stepNumber }
        return if (sorted.size == 1) sorted.first().label
        else "S" + sorted.joinToString("+") { it.stepNumber.toString() }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PlannedClient>() {
            override fun areItemsTheSame(a: PlannedClient, b: PlannedClient) =
                a.client.id == b.client.id

            override fun areContentsTheSame(a: PlannedClient, b: PlannedClient) = a == b
        }
    }
}
