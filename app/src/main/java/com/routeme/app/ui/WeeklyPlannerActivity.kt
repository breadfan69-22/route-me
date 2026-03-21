package com.routeme.app.ui

import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.DragEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.routeme.app.R
import com.routeme.app.data.db.SavedWeekPlanEntity
import com.routeme.app.data.db.WeekPlanDao
import com.routeme.app.model.PlannedDay
import com.routeme.app.model.WeekPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.android.ext.android.inject

class WeeklyPlannerActivity : AppCompatActivity() {

    private val weekPlanDao: WeekPlanDao by inject()

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var dayPickerBar: LinearLayout
    private lateinit var toolbar: MaterialToolbar

    private var weekPlan: WeekPlan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weekly_planner)

        toolbar = findViewById(R.id.topToolbar)
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        dayPickerBar = findViewById(R.id.dayPickerBar)

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.weekly_planner_menu)
        toolbar.setOnMenuItemClickListener(::onMenuItemClick)

        val json = intent.getStringExtra(EXTRA_PLAN_JSON)
        if (json != null) {
            loadPlan(WeekPlan.fromJson(JSONObject(json)))
            savePlanToRoom()
        } else {
            loadPlanFromRoom()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshFromRoom()
    }

    private fun loadPlan(plan: WeekPlan) {
        weekPlan = plan

        val days = plan.days
        toolbar.subtitle = "${plan.totalClients} clients • ${plan.unassignedCount} unassigned"

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = days.size
            override fun createFragment(position: Int) = PlannerDayFragment.newInstance(position)
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = days[position].dayName
        }.attach()

        buildDayPickerButtons(days)
    }

    private fun loadPlanFromRoom() {
        lifecycleScope.launch {
            val entity = withContext(Dispatchers.IO) { weekPlanDao.loadPlan() }
            if (entity != null) {
                loadPlan(WeekPlan.fromJson(JSONObject(entity.planJson)))
            } else {
                Snackbar.make(viewPager, "No saved plan. Generate one from the main screen.", Snackbar.LENGTH_LONG).show()
                finish()
            }
        }
    }

    fun getPlannedDay(index: Int): PlannedDay? = weekPlan?.days?.getOrNull(index)

    private fun refreshFromRoom() {
        lifecycleScope.launch {
            val entity = withContext(Dispatchers.IO) { weekPlanDao.loadPlan() } ?: return@launch
            val plan = WeekPlan.fromJson(JSONObject(entity.planJson))
            weekPlan = plan
            // Refresh any currently-attached fragments
            refreshVisibleFragments()
            toolbar.subtitle = "${plan.totalClients} clients • ${plan.unassignedCount} unassigned"
        }
    }

    private fun savePlanToRoom() {
        val plan = weekPlan ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            weekPlanDao.savePlan(
                SavedWeekPlanEntity(
                    planJson = plan.toJson().toString(),
                    generatedAtMillis = plan.generatedAtMillis
                )
            )
        }
    }

    // ── Drag-and-Drop ──

    fun showDayPickerBar() {
        dayPickerBar.visibility = View.VISIBLE
    }

    private fun hideDayPickerBar() {
        dayPickerBar.visibility = View.GONE
    }

    private fun buildDayPickerButtons(days: List<PlannedDay>) {
        dayPickerBar.removeAllViews()
        for ((index, day) in days.withIndex()) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = day.dayName.take(3)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 12f

                setOnDragListener { v, event ->
                    handleDayButtonDrag(v, event, index)
                }
            }
            dayPickerBar.addView(btn)
        }

        // Global drag listener on the root to detect drag end
        findViewById<View>(android.R.id.content).setOnDragListener { _, event ->
            if (event.action == DragEvent.ACTION_DRAG_ENDED) {
                hideDayPickerBar()
                // Restore any faded chip alphas
                restoreChipAlphas()
            }
            true
        }
    }

    private fun handleDayButtonDrag(view: View, event: DragEvent, targetDayIndex: Int): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                return event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
            }
            DragEvent.ACTION_DRAG_ENTERED -> {
                (view as? MaterialButton)?.setBackgroundColor(
                    getColor(com.google.android.material.R.color.design_default_color_secondary)
                )
                return true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                (view as? MaterialButton)?.setBackgroundColor(0)
                return true
            }
            DragEvent.ACTION_DROP -> {
                (view as? MaterialButton)?.setBackgroundColor(0)
                val data = event.clipData?.getItemAt(0)?.text?.toString() ?: return false
                val parts = data.split("|")
                if (parts.size != 2) return false
                val clientId = parts[0]
                val fromDayIndex = parts[1].toIntOrNull() ?: return false
                if (fromDayIndex == targetDayIndex) return true // same day = no-op
                moveClient(clientId, fromDayIndex, targetDayIndex)
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                (view as? MaterialButton)?.setBackgroundColor(0)
                return true
            }
            else -> return false
        }
    }

    private fun moveClient(clientId: String, fromDayIndex: Int, toDayIndex: Int) {
        val plan = weekPlan ?: return
        val days = plan.days.toMutableList()
        if (fromDayIndex !in days.indices || toDayIndex !in days.indices) return

        val fromDay = days[fromDayIndex]
        val client = fromDay.clients.find { it.client.id == clientId } ?: return

        // Remove from source
        val updatedFromClients = fromDay.clients.filter { it.client.id != clientId }
        days[fromDayIndex] = fromDay.copy(clients = updatedFromClients)

        // Add to target (marked as manually placed)
        val toDay = days[toDayIndex]
        val movedClient = client.copy(manuallyPlaced = true)
        days[toDayIndex] = toDay.copy(clients = toDay.clients + movedClient)

        val updated = plan.copy(days = days)
        weekPlan = updated

        // Refresh affected fragments
        findDayFragment(fromDayIndex)?.updateDay(days[fromDayIndex])
        findDayFragment(toDayIndex)?.updateDay(days[toDayIndex])

        savePlanToRoom()

        Snackbar.make(viewPager, "Moved ${client.client.name} to ${days[toDayIndex].dayName}", Snackbar.LENGTH_SHORT).show()
    }

    private fun restoreChipAlphas() {
        for (frag in supportFragmentManager.fragments) {
            val rv = frag.view?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chipRecyclerView) ?: continue
            for (i in 0 until rv.childCount) {
                rv.getChildAt(i)?.alpha = 1f
            }
        }
    }

    /** Find an attached PlannerDayFragment by its day index. */
    private fun findDayFragment(dayIndex: Int): PlannerDayFragment? {
        return supportFragmentManager.fragments
            .filterIsInstance<PlannerDayFragment>()
            .firstOrNull { it.arguments?.getInt("day_index") == dayIndex }
    }

    private fun refreshVisibleFragments() {
        val plan = weekPlan ?: return
        for (frag in supportFragmentManager.fragments.filterIsInstance<PlannerDayFragment>()) {
            val idx = frag.arguments?.getInt("day_index") ?: continue
            plan.days.getOrNull(idx)?.let { frag.updateDay(it) }
        }
    }

    // ── Menu ──

    private fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_regenerate -> {
                confirmRegenerate()
                true
            }
            else -> false
        }
    }

    private fun confirmRegenerate() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Regenerate Plan?")
            .setMessage("This will discard any manual edits and create a fresh plan.")
            .setPositiveButton("Regenerate") { _, _ ->
                setResult(RESULT_REGENERATE)
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_PLAN_JSON = "plan_json"
        const val RESULT_REGENERATE = 42

        fun createIntent(context: Context, plan: WeekPlan): Intent {
            return Intent(context, WeeklyPlannerActivity::class.java).apply {
                putExtra(EXTRA_PLAN_JSON, plan.toJson().toString())
            }
        }
    }
}
