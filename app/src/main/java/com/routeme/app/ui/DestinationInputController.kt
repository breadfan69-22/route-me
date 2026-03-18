package com.routeme.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.location.Location
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.button.MaterialButton
import com.routeme.app.R
import com.routeme.app.SavedDestination
import com.routeme.app.databinding.ActivityMainBinding
import com.routeme.app.network.GeocodingHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class DestinationInputController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val viewModel: MainViewModel,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val launchVoiceRecognizer: (Intent) -> Unit,
    private val launchCameraCapture: (Uri) -> Unit,
    private val getLastLocation: () -> Location?,
    private val rerunSuggestionsIfVisible: () -> Unit,
    private val launchDestinationsScreen: () -> Unit
) {
    private var destinationDialog: AlertDialog? = null
    private var pendingCameraImageUri: Uri? = null

    fun showDestinationDialog() {
        destinationDialog?.dismiss()
        val state = viewModel.uiState.value
        val queue = state.destinationQueue
        val activeIdx = state.activeDestinationIndex

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val activeDest = state.activeDestination
        if (activeDest != null) {
            container.addView(TextView(activity).apply {
                text = activity.getString(R.string.dialog_dest_active, activeDest.name)
                setTypeface(null, Typeface.BOLD)
                textSize = 14f
                setPadding(0, 0, 0, 16)
            })
        }

        if (queue.isEmpty()) {
            container.addView(TextView(activity).apply {
                text = activity.getString(R.string.dialog_dest_empty)
                setPadding(0, 8, 0, 16)
            })
        } else {
            if (queue.size >= 2) {
                container.addView(TextView(activity).apply {
                    text = activity.getString(R.string.dialog_dest_drag_hint)
                    textSize = 12f
                    setPadding(0, 0, 0, 8)
                })
            }

            val queueAdapter = DestinationQueueAdapter(
                items = queue.toMutableList(),
                activeIndex = activeIdx,
                onRemoveAt = { index ->
                    viewModel.removeFromDestinationQueue(index)
                    showDestinationDialog()
                    rerunSuggestionsIfVisible()
                }
            )

            val queueRecyclerView = RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                adapter = queueAdapter
                isNestedScrollingEnabled = false
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            }

            if (queue.size >= 2) {
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

                        queueAdapter.moveItem(fromIndex, toIndex)
                        viewModel.moveDestinationInQueue(fromIndex, toIndex)
                        queueAdapter.setActiveIndex(viewModel.uiState.value.activeDestinationIndex)
                        return true
                    }

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                    override fun isLongPressDragEnabled(): Boolean = true
                })
                touchHelper.attachToRecyclerView(queueRecyclerView)
            }

            container.addView(queueRecyclerView)
        }

        val addressInput = EditText(activity).apply {
            hint = activity.getString(R.string.dialog_dest_add_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            setPadding(0, 24, 0, 8)
        }
        container.addView(addressInput)

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }

        buttonRow.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = activity.getString(R.string.dialog_dest_add)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 4
            }
            setOnClickListener {
                val address = addressInput.text.toString().trim()
                if (address.isNotBlank()) {
                    geocodeAndAddDestination(address)
                }
            }
        })

        buttonRow.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = activity.getString(R.string.dialog_dest_voice)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 4
                marginEnd = 4
            }
            setOnClickListener { launchVoiceInput() }
        })

        buttonRow.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = activity.getString(R.string.dialog_dest_paste)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 4
                marginEnd = 4
            }
            setOnClickListener { pasteDestinations() }
        })

        buttonRow.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = activity.getString(R.string.dialog_dest_camera)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 4
            }
            setOnClickListener { launchCameraForOcr() }
        })
        container.addView(buttonRow)

        if (queue.size >= 2) {
            val actionRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 0)
            }
            actionRow.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = activity.getString(R.string.dialog_dest_optimize)
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 4
                }
                setOnClickListener {
                    viewModel.optimizeDestinationQueue(getLastLocation())
                    showDestinationDialog()
                }
            })
            actionRow.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = activity.getString(R.string.dialog_dest_clear)
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 4
                }
                setOnClickListener {
                    viewModel.clearDestinationQueue()
                    showDestinationDialog()
                }
            })
            container.addView(actionRow)
        }

        if (queue.isNotEmpty()) {
            container.addView(
                MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = activity.getString(R.string.dialog_dest_open_full_screen)
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 8
                    }
                    setOnClickListener {
                        destinationDialog?.dismiss()
                        launchDestinationsScreen()
                    }
                }
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dialog_destinations_title))
            .setView(container)
            .setPositiveButton("Done", null)
            .create()

        if (queue.isNotEmpty() && activeIdx < queue.size) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Skip") { _, _ ->
                viewModel.skipDestination()
                rerunSuggestionsIfVisible()
            }
        }

        destinationDialog = dialog
        dialog.show()
    }

    private class DestinationQueueAdapter(
        private val items: MutableList<SavedDestination>,
        private var activeIndex: Int,
        private val onRemoveAt: (Int) -> Unit
    ) : RecyclerView.Adapter<DestinationQueueAdapter.DestinationViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DestinationViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
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

        fun moveItem(fromIndex: Int, toIndex: Int) {
            if (fromIndex == toIndex || fromIndex !in items.indices || toIndex !in items.indices) return
            val moved = items.removeAt(fromIndex)
            items.add(toIndex, moved)

            notifyItemMoved(fromIndex, toIndex)
            val rangeStart = minOf(fromIndex, toIndex)
            val rangeCount = abs(fromIndex - toIndex) + 1
            notifyItemRangeChanged(rangeStart, rangeCount)
        }

        fun setActiveIndex(newActiveIndex: Int) {
            if (activeIndex == newActiveIndex) return
            activeIndex = newActiveIndex
            notifyDataSetChanged()
        }

        class DestinationViewHolder(
            view: LinearLayout,
            val nameView: TextView,
            val removeView: TextView
        ) : RecyclerView.ViewHolder(view)
    }

    fun onVoiceInputResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        val spoken = data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            geocodeAndAddDestination(spoken)
        }
    }

    fun onCameraCaptureResult(success: Boolean) {
        if (!success) return
        val uri = pendingCameraImageUri ?: return
        runOcrOnImage(uri)
    }

    private fun geocodeAndAddDestination(address: String) {
        Snackbar.make(
            binding.root,
            activity.getString(R.string.dialog_dest_geocoding),
            Snackbar.LENGTH_SHORT
        ).show()

        lifecycleScope.launch {
            val enrichedAddress = GeocodingHelper.enrichAddress(address, "")
            val coords = withContext(Dispatchers.IO) {
                GeocodingHelper.geocodeAddress(enrichedAddress)
            }
            if (coords != null) {
                val destination = SavedDestination(
                    id = java.util.UUID.randomUUID().toString(),
                    name = enrichedAddress,
                    address = enrichedAddress,
                    lat = coords.first,
                    lng = coords.second
                )
                viewModel.addToDestinationQueue(destination)
                showDestinationDialog()
                rerunSuggestionsIfVisible()
            } else {
                Snackbar.make(
                    binding.root,
                    activity.getString(R.string.dialog_dest_geocode_failed, address),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun launchVoiceInput() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say an address or place name")
        }
        try {
            launchVoiceRecognizer(intent)
        } catch (_: Exception) {
            Snackbar.make(binding.root, "Voice input not available", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun pasteDestinations() {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            Snackbar.make(binding.root, "Clipboard is empty", Snackbar.LENGTH_SHORT).show()
            return
        }

        val lines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return

        if (lines.size == 1) {
            geocodeAndAddDestination(lines.first())
        } else {
            showLinePickerDialog(lines)
        }
    }

    private fun launchCameraForOcr() {
        try {
            val imageFile = java.io.File(activity.cacheDir, "dest_ocr_${System.currentTimeMillis()}.jpg")
            pendingCameraImageUri = androidx.core.content.FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                imageFile
            )
            launchCameraCapture(pendingCameraImageUri!!)
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Camera not available: ${e.message}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun runOcrOnImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                val image = com.google.mlkit.vision.common.InputImage.fromFilePath(activity, uri)
                val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                    com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
                )
                val visionText = kotlin.coroutines.suspendCoroutine<com.google.mlkit.vision.text.Text> { cont ->
                    recognizer.process(image)
                        .addOnSuccessListener { cont.resumeWith(Result.success(it)) }
                        .addOnFailureListener { cont.resumeWith(Result.failure(it)) }
                }
                val lines = visionText.textBlocks
                    .flatMap { block -> block.lines }
                    .map { line -> line.text.trim() }
                    .filter { text -> text.isNotBlank() }

                if (lines.isEmpty()) {
                    Snackbar.make(binding.root, activity.getString(R.string.dialog_dest_no_text), Snackbar.LENGTH_SHORT).show()
                } else {
                    showLinePickerDialog(lines)
                }
            } catch (e: Exception) {
                Snackbar.make(binding.root, "OCR failed: ${e.message}", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLinePickerDialog(lines: List<String>) {
        val checked = BooleanArray(lines.size) { true }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dialog_dest_select_lines))
            .setMultiChoiceItems(lines.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Add Selected") { _, _ ->
                lines.forEachIndexed { index, line ->
                    if (checked[index]) geocodeAndAddDestination(line)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}