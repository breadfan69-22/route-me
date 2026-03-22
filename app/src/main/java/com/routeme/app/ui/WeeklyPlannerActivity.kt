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
import com.routeme.app.SavedDestination
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.db.SavedWeekPlanEntity
import com.routeme.app.data.db.WeekPlanDao
import com.routeme.app.domain.DayAnchor
import com.routeme.app.domain.WeeklyPlannerUseCase
import com.routeme.app.model.PlannedClient
import com.routeme.app.model.PlannedDay
import com.routeme.app.model.WeekPlan
import com.routeme.app.network.GeocodingHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import org.koin.android.ext.android.inject

class WeeklyPlannerActivity : AppCompatActivity() {

    private val weekPlanDao: WeekPlanDao by inject()
    private val clientRepository: ClientRepository by inject()
    private val weeklyPlannerUseCase: WeeklyPlannerUseCase by inject()

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

        computeZoneCentroids()
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
        viewPager.isUserInputEnabled = false  // Tab-only navigation; swipe used for within-day client dismiss

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

    fun removeClient(dayIndex: Int, clientId: String) {
        val plan = weekPlan ?: return
        val days = plan.days.toMutableList()
        if (dayIndex !in days.indices) return
        val day = days[dayIndex]
        val client = day.clients.find { it.client.id == clientId } ?: return
        days[dayIndex] = day.copy(clients = day.clients.filter { it.client.id != clientId })
        weekPlan = plan.copy(days = days)
        findDayFragment(dayIndex)?.updateDay(days[dayIndex])
        savePlanToRoom()
        Snackbar.make(viewPager, "${client.client.name} removed", Snackbar.LENGTH_SHORT)
            .setAction("Undo") {
                val restored = weekPlan?.days?.toMutableList() ?: return@setAction
                restored[dayIndex] = restored[dayIndex].copy(clients = restored[dayIndex].clients + client)
                weekPlan = weekPlan?.copy(days = restored)
                findDayFragment(dayIndex)?.updateDay(restored[dayIndex])
                savePlanToRoom()
            }.show()
    }

    fun toggleClientLock(dayIndex: Int, clientId: String) {
        val plan = weekPlan ?: return
        val days = plan.days.toMutableList()
        if (dayIndex !in days.indices) return
        val day = days[dayIndex]
        days[dayIndex] = day.copy(
            clients = day.clients.map {
                if (it.client.id == clientId) it.copy(locked = !it.locked) else it
            }
        )
        weekPlan = plan.copy(days = days)
        findDayFragment(dayIndex)?.updateDay(days[dayIndex])
        savePlanToRoom()
    }

    fun reorderDay(dayIndex: Int, reorderedClients: List<PlannedClient>) {
        val plan = weekPlan ?: return
        val days = plan.days.toMutableList()
        if (dayIndex !in days.indices) return
        days[dayIndex] = days[dayIndex].copy(clients = reorderedClients)
        weekPlan = plan.copy(days = days)
        savePlanToRoom()
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
            R.id.action_start_route -> {
                startCurrentDayRoute()
                true
            }
            R.id.action_regenerate -> {
                confirmRegenerate()
                true
            }
            else -> false
        }
    }

    private fun startCurrentDayRoute() {
        val plan = weekPlan ?: return
        val currentDayIndex = viewPager.currentItem
        val day = plan.days.getOrNull(currentDayIndex) ?: return

        if (day.clients.isEmpty()) {
            Snackbar.make(viewPager, "No clients planned for ${day.dayName}", Snackbar.LENGTH_SHORT).show()
            return
        }

        // Convert PlannedClients to SavedDestinations
        val destinations = day.clients.mapNotNull { plannedClient ->
            val c = plannedClient.client
            if (c.latitude == null || c.longitude == null) return@mapNotNull null
            SavedDestination(
                id = UUID.randomUUID().toString(),
                name = c.name,
                address = c.address,
                lat = c.latitude,
                lng = c.longitude
            )
        }

        if (destinations.isEmpty()) {
            Snackbar.make(viewPager, "No clients with GPS coordinates", Snackbar.LENGTH_SHORT).show()
            return
        }

        // Return result to MainActivity
        val intent = Intent().apply {
            putExtra(EXTRA_ROUTE_DESTINATIONS, encodeDestinations(destinations))
        }
        setResult(RESULT_START_ROUTE, intent)
        finish()
    }

    private fun encodeDestinations(destinations: List<SavedDestination>): String {
        val arr = JSONArray()
        destinations.forEach { dest ->
            arr.put(JSONObject().apply {
                put("id", dest.id)
                put("name", dest.name)
                put("address", dest.address)
                put("lat", dest.lat)
                put("lng", dest.lng)
            })
        }
        return arr.toString()
    }

    private fun confirmRegenerate() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Regenerate Plan?")
            .setMessage("This will discard any manual edits and create a fresh plan.\nAnchors will be preserved.")
            .setPositiveButton("Regenerate") { _, _ ->
                // Pass current anchors back so they survive the regeneration
                val anchorsJson = encodeAnchors()
                val intent = Intent().apply {
                    putExtra(EXTRA_ANCHORS_JSON, anchorsJson)
                }
                setResult(RESULT_REGENERATE, intent)
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Anchors ──

    /** Zone centroid data for the anchor picker dialog. */
    data class ZoneCentroid(val label: String, val lat: Double, val lng: Double)

    private var zoneCentroids: List<ZoneCentroid> = emptyList()

    /** Compute zone centroids from all geocoded clients — called once after plan loads. */
    private fun computeZoneCentroids() {
        lifecycleScope.launch {
            val clients = withContext(Dispatchers.IO) { clientRepository.loadAllClients() }
            val geocoded = clients.filter { it.latitude != null && it.longitude != null && it.zone.isNotBlank() }
            zoneCentroids = geocoded
                .groupBy { it.zone }
                .map { (zone, members) ->
                    val avgLat = members.map { it.latitude!! }.average()
                    val avgLng = members.map { it.longitude!! }.average()
                    ZoneCentroid(zone, avgLat, avgLng)
                }
                .sortedBy { it.label }
        }
    }

    fun getAvailableZones(): List<ZoneCentroid> = zoneCentroids

    fun setAnchor(dayIndex: Int, lat: Double, lng: Double, label: String) {
        val plan = weekPlan ?: return
        val days = plan.days.toMutableList()
        if (dayIndex !in days.indices) return
        days[dayIndex] = days[dayIndex].copy(anchorLat = lat, anchorLng = lng, anchorLabel = label)
        weekPlan = plan.copy(days = days)
        findDayFragment(dayIndex)?.updateDay(days[dayIndex])
        savePlanToRoom()
        Snackbar.make(viewPager, "Anchor set: $label", Snackbar.LENGTH_SHORT).show()
    }

    fun clearAnchor(dayIndex: Int) {
        val plan = weekPlan ?: return
        val days = plan.days.toMutableList()
        if (dayIndex !in days.indices) return
        days[dayIndex] = days[dayIndex].copy(anchorLat = null, anchorLng = null, anchorLabel = null)
        weekPlan = plan.copy(days = days)
        findDayFragment(dayIndex)?.updateDay(days[dayIndex])
        savePlanToRoom()
        Snackbar.make(viewPager, "Anchor cleared", Snackbar.LENGTH_SHORT).show()
    }

    fun reoptimiseRoute(dayIndex: Int) {
        val plan = weekPlan ?: return
        lifecycleScope.launch {
            val newDay = weeklyPlannerUseCase.reoptimiseRoute(plan, dayIndex)
            if (newDay != null) {
                val days = plan.days.toMutableList()
                days[dayIndex] = newDay
                weekPlan = plan.copy(days = days)
                findDayFragment(dayIndex)?.updateDay(newDay)
                savePlanToRoom()
                Snackbar.make(viewPager, "Route reordered for ${newDay.dayName}", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    fun refillDay(dayIndex: Int) {
        val plan = weekPlan ?: return
        val allCurrentIds = plan.days.getOrNull(dayIndex)?.clients
            ?.map { it.client.id }?.toSet() ?: emptySet()
        lifecycleScope.launch {
            val newDay = weeklyPlannerUseCase.rebuildDay(plan, dayIndex, lockedClientIds = allCurrentIds)
            if (newDay != null && newDay.clients.size > allCurrentIds.size) {
                val days = plan.days.toMutableList()
                days[dayIndex] = newDay
                val updated = plan.copy(days = days)
                weekPlan = updated
                findDayFragment(dayIndex)?.updateDay(newDay)
                toolbar.subtitle = "${updated.totalClients} clients \u2022 ${updated.unassignedCount} unassigned"
                savePlanToRoom()
                val added = newDay.clients.size - allCurrentIds.size
                Snackbar.make(viewPager, "Added $added client${if (added != 1) "s" else ""} to ${newDay.dayName}", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(viewPager, "No additional clients fit the corridor", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    fun rebuildDay(dayIndex: Int) {
        val plan = weekPlan ?: return
        val lockedIds = plan.days.getOrNull(dayIndex)?.clients
            ?.filter { it.locked }
            ?.map { it.client.id }
            ?.toSet() ?: emptySet()
        lifecycleScope.launch {
            val newDay = weeklyPlannerUseCase.rebuildDay(plan, dayIndex, lockedClientIds = lockedIds)
            if (newDay != null) {
                val days = plan.days.toMutableList()
                days[dayIndex] = newDay
                val updated = plan.copy(days = days)
                weekPlan = updated
                findDayFragment(dayIndex)?.updateDay(newDay)
                toolbar.subtitle = "${updated.totalClients} clients \u2022 ${updated.unassignedCount} unassigned"
                savePlanToRoom()
                Snackbar.make(viewPager, "${newDay.dayName} rebuilt \u2014 ${newDay.clients.size} clients", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(viewPager, "Cannot rebuild this day", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    fun geocodeAndSetAnchor(dayIndex: Int, address: String) {
        lifecycleScope.launch {
            val coords = withContext(Dispatchers.IO) { GeocodingHelper.geocodeAddress(address) }
            if (coords != null) {
                setAnchor(dayIndex, coords.first, coords.second, address)
            } else {
                Snackbar.make(viewPager, "Could not geocode: $address", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun encodeAnchors(): String {
        val plan = weekPlan ?: return "{}"
        val obj = JSONObject()
        for (day in plan.days) {
            if (day.anchorLat != null && day.anchorLng != null && day.anchorLabel != null) {
                obj.put(day.dayOfWeek.toString(), JSONObject().apply {
                    put("lat", day.anchorLat)
                    put("lng", day.anchorLng)
                    put("label", day.anchorLabel)
                })
            }
        }
        return obj.toString()
    }

    companion object {
        private const val EXTRA_PLAN_JSON = "plan_json"
        const val EXTRA_ROUTE_DESTINATIONS = "route_destinations"
        const val EXTRA_ANCHORS_JSON = "anchors_json"
        const val RESULT_REGENERATE = 42
        const val RESULT_START_ROUTE = 43

        fun createIntent(context: Context, plan: WeekPlan): Intent {
            return Intent(context, WeeklyPlannerActivity::class.java).apply {
                putExtra(EXTRA_PLAN_JSON, plan.toJson().toString())
            }
        }

        fun extractAnchors(intent: Intent?): Map<Int, DayAnchor> {
            val json = intent?.getStringExtra(EXTRA_ANCHORS_JSON) ?: return emptyMap()
            return runCatching {
                val obj = JSONObject(json)
                obj.keys().asSequence().associate { key ->
                    val dayObj = obj.getJSONObject(key)
                    key.toInt() to DayAnchor(
                        lat = dayObj.getDouble("lat"),
                        lng = dayObj.getDouble("lng"),
                        label = dayObj.getString("label")
                    )
                }
            }.getOrDefault(emptyMap())
        }

        fun extractRouteDestinations(intent: Intent?): List<SavedDestination>? {
            val json = intent?.getStringExtra(EXTRA_ROUTE_DESTINATIONS) ?: return null
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    SavedDestination(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        address = obj.getString("address"),
                        lat = obj.getDouble("lat"),
                        lng = obj.getDouble("lng")
                    )
                }
            }.getOrNull()
        }
    }
}
