package com.routeme.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.location.Location
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.routeme.app.domain.DestinationQueueUseCase
import org.json.JSONArray
import org.json.JSONObject
import org.koin.android.ext.android.inject

class DestinationsActivity : AppCompatActivity() {
    private val destinationQueueUseCase: DestinationQueueUseCase by inject()

    private lateinit var activeDestinationText: TextView
    private lateinit var dragHintText: TextView
    private lateinit var emptyText: TextView
    private lateinit var destinationRecyclerView: RecyclerView
    private lateinit var optimizeButton: MaterialButton
    private lateinit var clearButton: MaterialButton
    private lateinit var skipButton: MaterialButton
    private lateinit var doneButton: MaterialButton

    private lateinit var queueAdapter: DestinationQueueAdapter

    private var destinationQueue: List<SavedDestination> = emptyList()
    private var activeDestinationIndex: Int = 0
    private var optimizeStartPoint: DestinationQueueUseCase.GeoPoint? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_destinations)

        val toolbar = findViewById<MaterialToolbar>(R.id.topToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        activeDestinationText = findViewById(R.id.activeDestinationText)
        dragHintText = findViewById(R.id.dragHintText)
        emptyText = findViewById(R.id.emptyText)
        destinationRecyclerView = findViewById(R.id.destinationRecyclerView)
        optimizeButton = findViewById(R.id.optimizeButton)
        clearButton = findViewById(R.id.clearButton)
        skipButton = findViewById(R.id.skipButton)
        doneButton = findViewById(R.id.doneButton)

        destinationQueue = decodeQueue(intent.getStringExtra(EXTRA_QUEUE_JSON))
        activeDestinationIndex = clampActiveIndex(
            queue = destinationQueue,
            activeIndex = intent.getIntExtra(EXTRA_ACTIVE_INDEX, 0)
        )

        val startLat = intent.getDoubleExtra(EXTRA_START_LAT, Double.NaN)
        val startLng = intent.getDoubleExtra(EXTRA_START_LNG, Double.NaN)
        if (!startLat.isNaN() && !startLng.isNaN()) {
            optimizeStartPoint = DestinationQueueUseCase.GeoPoint(startLat, startLng)
        }

        queueAdapter = DestinationQueueAdapter { index ->
            val result = destinationQueueUseCase.removeFromDestinationQueue(
                destinationQueue = destinationQueue,
                activeDestinationIndex = activeDestinationIndex,
                indexToRemove = index
            )
            applyQueueMutation(result)
        }

        destinationRecyclerView.layoutManager = LinearLayoutManager(this)
        destinationRecyclerView.adapter = queueAdapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromIndex = viewHolder.adapterPosition
                val toIndex = target.adapterPosition
                if (fromIndex == RecyclerView.NO_POSITION || toIndex == RecyclerView.NO_POSITION || fromIndex == toIndex) {
                    return false
                }

                val result = destinationQueueUseCase.moveDestinationInQueue(
                    destinationQueue = destinationQueue,
                    activeDestinationIndex = activeDestinationIndex,
                    fromIndex = fromIndex,
                    toIndex = toIndex
                )
                applyQueueMutation(result)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = true
        })
        touchHelper.attachToRecyclerView(destinationRecyclerView)

        optimizeButton.setOnClickListener {
            val result = destinationQueueUseCase.optimizeDestinationQueue(
                destinationQueue = destinationQueue,
                currentLocation = optimizeStartPoint
            ) ?: return@setOnClickListener
            applyQueueMutation(result)
            result.statusMessage?.let { message ->
                Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
            }
        }

        clearButton.setOnClickListener {
            val result = destinationQueueUseCase.clearDestinationQueue()
            applyQueueMutation(result)
            result.statusMessage?.let { message ->
                Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
            }
        }

        skipButton.setOnClickListener {
            val result = destinationQueueUseCase.skipDestination(
                destinationQueue = destinationQueue,
                activeDestinationIndex = activeDestinationIndex
            )
            applyQueueMutation(result)
        }

        doneButton.setOnClickListener {
            finish()
        }

        renderQueue()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun finish() {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_QUEUE_JSON, encodeQueue(destinationQueue))
                putExtra(EXTRA_ACTIVE_INDEX, activeDestinationIndex)
            }
        )
        super.finish()
    }

    private fun applyQueueMutation(result: DestinationQueueUseCase.QueueMutationResult) {
        destinationQueue = result.destinationQueue
        activeDestinationIndex = result.activeDestinationIndex
        renderQueue()
    }

    private fun renderQueue() {
        val activeDestination = destinationQueue.getOrNull(activeDestinationIndex)
        if (activeDestination != null) {
            activeDestinationText.visibility = View.VISIBLE
            activeDestinationText.text = getString(R.string.dialog_dest_active, activeDestination.name)
        } else {
            activeDestinationText.visibility = View.GONE
        }

        val hasQueue = destinationQueue.isNotEmpty()
        emptyText.visibility = if (hasQueue) View.GONE else View.VISIBLE
        destinationRecyclerView.visibility = if (hasQueue) View.VISIBLE else View.GONE
        dragHintText.visibility = if (destinationQueue.size >= 2) View.VISIBLE else View.GONE

        optimizeButton.isEnabled = destinationQueue.size >= 2
        clearButton.isEnabled = hasQueue
        skipButton.isEnabled = hasQueue

        queueAdapter.submitQueue(destinationQueue, activeDestinationIndex)
    }

    private fun clampActiveIndex(queue: List<SavedDestination>, activeIndex: Int): Int {
        return when {
            queue.isEmpty() -> 0
            activeIndex < 0 -> 0
            activeIndex >= queue.size -> queue.lastIndex
            else -> activeIndex
        }
    }

    private class DestinationQueueAdapter(
        private val onRemoveAt: (Int) -> Unit
    ) : RecyclerView.Adapter<DestinationQueueAdapter.DestinationViewHolder>() {
        private val items = mutableListOf<SavedDestination>()
        private var activeIndex: Int = 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DestinationViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10, 0, 10)
            }

            val dragHandle = TextView(parent.context).apply {
                text = "☰"
                textSize = 16f
                setPadding(0, 0, 16, 0)
            }

            val name = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val remove = TextView(parent.context).apply {
                text = "✖"
                textSize = 18f
                setPadding(16, 0, 0, 0)
            }

            row.addView(dragHandle)
            row.addView(name)
            row.addView(remove)

            return DestinationViewHolder(row, name, remove)
        }

        override fun onBindViewHolder(holder: DestinationViewHolder, position: Int) {
            val destination = items[position]
            val prefix = if (position == activeIndex) "▶ " else "${position + 1}. "
            holder.nameView.text = "$prefix${destination.name}"
            holder.nameView.setTypeface(null, if (position == activeIndex) Typeface.BOLD else Typeface.NORMAL)

            holder.removeView.setOnClickListener {
                val adapterPosition = holder.adapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onRemoveAt(adapterPosition)
                }
            }
        }

        override fun getItemCount(): Int = items.size

        fun submitQueue(queue: List<SavedDestination>, newActiveIndex: Int) {
            items.clear()
            items.addAll(queue)
            activeIndex = newActiveIndex
            notifyDataSetChanged()
        }

        class DestinationViewHolder(
            view: LinearLayout,
            val nameView: TextView,
            val removeView: TextView
        ) : RecyclerView.ViewHolder(view)
    }

    companion object {
        private const val EXTRA_QUEUE_JSON = "destinations_queue_json"
        private const val EXTRA_ACTIVE_INDEX = "destinations_active_index"
        private const val EXTRA_START_LAT = "destinations_start_lat"
        private const val EXTRA_START_LNG = "destinations_start_lng"

        data class QueueResult(
            val destinationQueue: List<SavedDestination>,
            val activeDestinationIndex: Int
        )

        fun createIntent(
            context: Context,
            destinationQueue: List<SavedDestination>,
            activeDestinationIndex: Int,
            currentLocation: Location?
        ): Intent {
            return Intent(context, DestinationsActivity::class.java).apply {
                putExtra(EXTRA_QUEUE_JSON, encodeQueue(destinationQueue))
                putExtra(EXTRA_ACTIVE_INDEX, activeDestinationIndex)
                currentLocation?.let {
                    putExtra(EXTRA_START_LAT, it.latitude)
                    putExtra(EXTRA_START_LNG, it.longitude)
                }
            }
        }

        fun extractQueueResult(intent: Intent?): QueueResult? {
            if (intent == null) return null
            val queue = decodeQueue(intent.getStringExtra(EXTRA_QUEUE_JSON))
            val activeIndex = when {
                queue.isEmpty() -> 0
                else -> intent.getIntExtra(EXTRA_ACTIVE_INDEX, 0).coerceIn(0, queue.lastIndex)
            }
            return QueueResult(
                destinationQueue = queue,
                activeDestinationIndex = activeIndex
            )
        }

        private fun encodeQueue(queue: List<SavedDestination>): String {
            val arr = JSONArray()
            queue.forEach { destination ->
                arr.put(
                    JSONObject().apply {
                        put("id", destination.id)
                        put("name", destination.name)
                        put("address", destination.address)
                        put("lat", destination.lat)
                        put("lng", destination.lng)
                    }
                )
            }
            return arr.toString()
        }

        private fun decodeQueue(json: String?): List<SavedDestination> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { index ->
                    val obj = arr.getJSONObject(index)
                    SavedDestination(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        address = obj.getString("address"),
                        lat = obj.getDouble("lat"),
                        lng = obj.getDouble("lng")
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}