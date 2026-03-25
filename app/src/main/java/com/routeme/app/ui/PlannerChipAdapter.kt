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
import com.routeme.app.model.RouteItem
import java.util.Collections

class PlannerChipAdapter(
    private val dayIndex: Int,
    private val onChipTap: (PlannedClient) -> Unit,
    private val onDragStarted: () -> Unit,
    private val onRemoveClient: (PlannedClient) -> Unit,
    private val onToggleLock: (PlannedClient) -> Unit,
    private val onSupplyHouseTap: ((RouteItem.SupplyHouseStop) -> Unit)? = null
) : ListAdapter<RouteItem, RecyclerView.ViewHolder>(DIFF) {

    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    private val dragItems = mutableListOf<RouteItem>()

    override fun submitList(list: List<RouteItem>?) {
        dragItems.clear()
        if (list != null) dragItems.addAll(list)
        super.submitList(list?.toList())
    }

    fun onItemDragTo(from: Int, to: Int) {
        if (from in dragItems.indices && to in dragItems.indices) {
            // Don't allow dragging supply house items
            if (dragItems[from] is RouteItem.SupplyHouseStop || dragItems[to] is RouteItem.SupplyHouseStop) return
            Collections.swap(dragItems, from, to)
            notifyItemMoved(from, to)
        }
    }

    fun getDraggedList(): List<PlannedClient> = dragItems
        .filterIsInstance<RouteItem.ClientStop>()
        .map { it.planned }

    /** Called by ItemTouchHelper swipe — removes item from local list and notifies adapter. */
    fun removeAt(position: Int) {
        if (position !in dragItems.indices) return
        val removed = dragItems.removeAt(position)
        // Can only remove client items
        if (removed is RouteItem.ClientStop) {
            notifyItemRemoved(position)
            onRemoveClient(removed.planned)
        } else {
            // Re-add supply house (can't be removed)
            dragItems.add(position, removed)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is RouteItem.ClientStop -> VIEW_TYPE_CLIENT
            is RouteItem.SupplyHouseStop -> VIEW_TYPE_SUPPLY
        }
    }

    class ClientViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
        val fitnessDot: View = view.findViewById(R.id.fitnessDot)
        val clientName: TextView = view.findViewById(R.id.clientName)
        val clientStep: TextView = view.findViewById(R.id.clientStep)
        val clientOverdue: TextView = view.findViewById(R.id.clientOverdue)
        val clientPinned: TextView = view.findViewById(R.id.clientPinned)
        val clientFitness: TextView = view.findViewById(R.id.clientFitness)
        val lockButton: ImageButton = view.findViewById(R.id.lockButton)
    }

    class SupplyViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val supplyName: TextView = view.findViewById(R.id.supplyName)
        val supplyLabel: TextView = view.findViewById(R.id.supplyLabel)
        val navigateButton: ImageButton = view.findViewById(R.id.navigateButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_SUPPLY -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_planner_supply_row, parent, false)
                SupplyViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_planner_client_row, parent, false)
                ClientViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is RouteItem.ClientStop -> bindClient(holder as ClientViewHolder, item.planned, position)
            is RouteItem.SupplyHouseStop -> bindSupply(holder as SupplyViewHolder, item)
        }
    }

    private fun bindSupply(holder: SupplyViewHolder, item: RouteItem.SupplyHouseStop) {
        holder.supplyName.text = item.name
        holder.supplyLabel.text = "Refill stop"
        holder.navigateButton.setOnClickListener { onSupplyHouseTap?.invoke(item) }
        holder.itemView.setOnClickListener { onSupplyHouseTap?.invoke(item) }
    }

    private fun bindClient(holder: ClientViewHolder, planned: PlannedClient, position: Int) {

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
        holder.lockButton.setColorFilter(
            if (planned.locked) 0xFF1976D2.toInt() else android.graphics.Color.GRAY
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
        private const val VIEW_TYPE_CLIENT = 0
        private const val VIEW_TYPE_SUPPLY = 1

        private val DIFF = object : DiffUtil.ItemCallback<RouteItem>() {
            override fun areItemsTheSame(a: RouteItem, b: RouteItem): Boolean {
                return when {
                    a is RouteItem.ClientStop && b is RouteItem.ClientStop -> a.planned.client.id == b.planned.client.id
                    a is RouteItem.SupplyHouseStop && b is RouteItem.SupplyHouseStop -> true
                    else -> false
                }
            }
            override fun areContentsTheSame(a: RouteItem, b: RouteItem) = a == b
        }
    }
}
