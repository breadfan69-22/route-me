package com.routeme.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.routeme.app.R
import com.routeme.app.ServiceType
import com.routeme.app.model.PlannedClient
import java.util.Collections

class PlannerChipAdapter(
    private val dayIndex: Int,
    private val onChipTap: (PlannedClient) -> Unit,
    private val onDragStarted: () -> Unit,
    private val onRemoveClient: (PlannedClient) -> Unit,
    private val onToggleLock: (PlannedClient) -> Unit
) : ListAdapter<PlannedClient, PlannerChipAdapter.RowViewHolder>(DIFF) {

    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    private val dragItems = mutableListOf<PlannedClient>()

    override fun submitList(list: List<PlannedClient>?) {
        dragItems.clear()
        if (list != null) dragItems.addAll(list)
        super.submitList(list?.toList())
    }

    fun onItemDragTo(from: Int, to: Int) {
        if (from in dragItems.indices && to in dragItems.indices) {
            Collections.swap(dragItems, from, to)
            notifyItemMoved(from, to)
        }
    }

    fun getDraggedList(): List<PlannedClient> = dragItems.toList()

    /** Called by ItemTouchHelper swipe — removes item from local list and notifies adapter. */
    fun removeAt(position: Int) {
        if (position !in dragItems.indices) return
        val removed = dragItems.removeAt(position)
        notifyItemRemoved(position)
        onRemoveClient(removed)
    }

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
        val fitnessDot: View = view.findViewById(R.id.fitnessDot)
        val clientName: TextView = view.findViewById(R.id.clientName)
        val clientStep: TextView = view.findViewById(R.id.clientStep)
        val clientOverdue: TextView = view.findViewById(R.id.clientOverdue)
        val clientPinned: TextView = view.findViewById(R.id.clientPinned)
        val clientFitness: TextView = view.findViewById(R.id.clientFitness)
        val lockButton: ImageButton = view.findViewById(R.id.lockButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_planner_client_row, parent, false)
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        val planned = getItem(position)

        holder.clientName.text = planned.client.name
        holder.clientFitness.text = "${planned.fitnessLabel} (${planned.fitnessScore})"

        // Fitness bar color
        val dotColor = when {
            planned.fitnessScore >= 80 -> 0xFF4CAF50.toInt()
            planned.fitnessScore >= 60 -> 0xFF8BC34A.toInt()
            planned.fitnessScore >= 40 -> 0xFFFFC107.toInt()
            else -> 0xFFF44336.toInt()
        }
        (holder.fitnessDot.background.mutate() as? android.graphics.drawable.GradientDrawable)?.setColor(dotColor)

        // Step tag
        holder.clientStep.text = formatStepTag(planned.eligibleSteps)
        holder.clientStep.visibility = if (planned.eligibleSteps.isNotEmpty()) View.VISIBLE else View.GONE

        // Overdue
        val overdue = planned.daysOverdue
        if (overdue != null && overdue > 0) {
            holder.clientOverdue.text = "${overdue}d overdue"
            holder.clientOverdue.visibility = View.VISIBLE
        } else {
            holder.clientOverdue.visibility = View.GONE
        }

        // Manually placed
        holder.clientPinned.visibility = if (planned.manuallyPlaced) View.VISIBLE else View.GONE

        // Lock button
        holder.lockButton.alpha = if (planned.locked) 1f else 0.25f
        holder.lockButton.setImageResource(
            if (planned.locked) android.R.drawable.ic_lock_idle_lock
            else android.R.drawable.ic_lock_idle_lock
        )
        // Tint locked vs unlocked
        holder.lockButton.setColorFilter(
            if (planned.locked)
                androidx.core.content.ContextCompat.getColor(holder.itemView.context, com.google.android.material.R.color.material_dynamic_primary40)
            else
                android.graphics.Color.GRAY
        )
        holder.lockButton.setOnClickListener { onToggleLock(planned) }

        // Tap → details
        holder.itemView.setOnClickListener { onChipTap(planned) }

        // Handle touch → within-day reorder
        @Suppress("ClickableViewAccessibility")
        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag?.invoke(holder)
            }
            false
        }

        // Long-press → between-day drag
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
