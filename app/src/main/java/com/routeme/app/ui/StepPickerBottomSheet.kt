package com.routeme.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.routeme.app.R
import com.routeme.app.ServiceType
import org.koin.androidx.viewmodel.ext.android.activityViewModel

/**
 * 8-chip multi-select step picker shown as a bottom sheet.
 *
 * Layout (4×2 grid, built programmatically):
 * ```
 *  [ 1 ] [ 2 ] [ 3 ] [3+G]
 *  [ 4 ] [ 5 ] [ 6 ] [ I  ]
 *                   [ Done ]
 * ```
 *
 * **3+G** emits `{ROUND_3, GRUB}` and is mutually exclusive with standalone **3**.
 */
class StepPickerBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: MainViewModel by activityViewModel()

    /** Currently toggled chips — mutated as user taps. */
    private val selected = mutableSetOf<ServiceType>()

    /** Maps each chip to the ServiceType(s) it represents. */
    private data class ChipDef(val label: String, val types: Set<ServiceType>, val isCombo: Boolean = false)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        // Seed from arguments or current ViewModel state
        val initial: Set<ServiceType> = arguments?.getStringArray(ARG_INITIAL_TYPES)
            ?.mapNotNull { name -> runCatching { ServiceType.valueOf(name) }.getOrNull() }
            ?.toSet()
            ?: viewModel.uiState.value.selectedServiceTypes
        selected.addAll(initial)

        val chipDefs = listOf(
            ChipDef("1", setOf(ServiceType.ROUND_1)),
            ChipDef("2", setOf(ServiceType.ROUND_2)),
            ChipDef("3", setOf(ServiceType.ROUND_3)),
            ChipDef(ctx.getString(R.string.step_picker_chip_3g), setOf(ServiceType.ROUND_3, ServiceType.GRUB), isCombo = true),
            ChipDef("4", setOf(ServiceType.ROUND_4)),
            ChipDef("5", setOf(ServiceType.ROUND_5)),
            ChipDef("6", setOf(ServiceType.ROUND_6)),
            ChipDef(ctx.getString(R.string.step_picker_chip_incidental), setOf(ServiceType.INCIDENTAL))
        )

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // Title
        val title = android.widget.TextView(ctx).apply {
            text = ctx.getString(R.string.step_picker_title)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        root.addView(title)

        // 4×2 chip grid
        val grid = GridLayout(ctx).apply {
            columnCount = 4
            rowCount = 2
        }

        val chipViews = mutableListOf<Pair<Chip, ChipDef>>()

        chipDefs.forEachIndexed { index, def ->
            val chip = Chip(ctx).apply {
                text = def.label
                isCheckable = true
                isChecked = isDefSelected(def)
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(index / 4),
                    GridLayout.spec(index % 4, 1f)
                ).apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    val margin = (4 * density).toInt()
                    setMargins(margin, margin, margin, margin)
                }
            }

            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    applySelection(def, chipViews)
                } else {
                    def.types.forEach { selected.remove(it) }
                }
            }

            chipViews.add(chip to def)
            grid.addView(chip)
        }

        root.addView(grid)

        // Done button
        val doneButton = MaterialButton(ctx).apply {
            text = ctx.getString(R.string.step_picker_done)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = (12 * density).toInt()
            }
        }

        doneButton.setOnClickListener {
            viewModel.setServiceTypes(selected.toSet())
            dismiss()
        }

        root.addView(doneButton)
        return root
    }

    /**
     * Check whether [def]'s types are all present in [selected].
     * For the 3+G combo: both ROUND_3 and GRUB must be selected.
     * For standalone 3: ROUND_3 must be selected AND GRUB must NOT be.
     */
    private fun isDefSelected(def: ChipDef): Boolean {
        if (def.isCombo) {
            return def.types.all { it in selected }
        }
        // Standalone "3" chip: selected only if ROUND_3 is in but GRUB is not
        if (def.types == setOf(ServiceType.ROUND_3)) {
            return ServiceType.ROUND_3 in selected && ServiceType.GRUB !in selected
        }
        return def.types.all { it in selected }
    }

    /**
     * When a chip is toggled ON, add its types and enforce mutual exclusion
     * between standalone 3 and 3+G.
     */
    private fun applySelection(def: ChipDef, chipViews: List<Pair<Chip, ChipDef>>) {
        selected.addAll(def.types)

        // Mutual exclusion: 3 and 3+G
        if (def.isCombo && def.types.contains(ServiceType.ROUND_3)) {
            // 3+G was checked → uncheck standalone 3
            chipViews.firstOrNull { !it.second.isCombo && it.second.types == setOf(ServiceType.ROUND_3) }
                ?.let { (chip, _) -> chip.isChecked = false }
        } else if (!def.isCombo && def.types == setOf(ServiceType.ROUND_3)) {
            // Standalone 3 was checked → uncheck 3+G and remove GRUB
            selected.remove(ServiceType.GRUB)
            chipViews.firstOrNull { it.second.isCombo && it.second.types.contains(ServiceType.ROUND_3) }
                ?.let { (chip, _) -> chip.isChecked = false }
        }
    }

    companion object {
        private const val ARG_INITIAL_TYPES = "initial_types"

        fun newInstance(currentTypes: Set<ServiceType>): StepPickerBottomSheet {
            return StepPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putStringArray(ARG_INITIAL_TYPES, currentTypes.map { it.name }.toTypedArray())
                }
            }
        }
    }
}
