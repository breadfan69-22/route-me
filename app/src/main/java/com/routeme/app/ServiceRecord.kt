package com.routeme.app

import java.time.MonthDay

data class ServiceRecord(
    val serviceType: ServiceType,
    val arrivedAtMillis: Long? = null,
    val completedAtMillis: Long,
    val durationMinutes: Long,
    val lat: Double?,
    val lng: Double?,
    val notes: String = "",
    val amountUsed: Double? = null,
    val amountUsed2: Double? = null
)

enum class ServiceType(val label: String, val stepNumber: Int) {
    ROUND_1("Step 1", 1),
    ROUND_2("Step 2", 2),
    ROUND_3("Step 3", 3),
    ROUND_4("Step 4", 4),
    ROUND_5("Step 5", 5),
    ROUND_6("Step 6", 6),
    GRUB("Grub", 7),
    INCIDENTAL("Incidental", -1);

    companion object {
        fun forStep(step: Int): ServiceType? = entries.firstOrNull { it.stepNumber == step }
    }
}

/**
 * A date window for a step, defined by MonthDay start/end (year-agnostic).
 * [grubCombo] means Grub should also be auto-checked alongside this step.
 */
data class StepWindow(
    val serviceTypes: Set<ServiceType>,
    val start: MonthDay,
    val end: MonthDay,
    val grubCombo: Boolean = false
)

/** Represents one option in the step-selection spinner (single or combo). */
data class StepOption(
    val label: String,
    val serviceTypes: Set<ServiceType>
)

/** All options available in the step spinner, including transition combos. */
val STEP_OPTIONS: List<StepOption> = listOf(
    StepOption("Step 1", setOf(ServiceType.ROUND_1)),
    StepOption("Step 2", setOf(ServiceType.ROUND_2)),
    StepOption("Step 3", setOf(ServiceType.ROUND_3)),
    StepOption("Step 4", setOf(ServiceType.ROUND_4)),
    StepOption("Step 5", setOf(ServiceType.ROUND_5)),
    StepOption("Step 6", setOf(ServiceType.ROUND_6)),
    StepOption("Grub", setOf(ServiceType.GRUB)),
    StepOption("Incidental", setOf(ServiceType.INCIDENTAL)),
    StepOption("Steps 1+2", setOf(ServiceType.ROUND_1, ServiceType.ROUND_2)),
    StepOption("Steps 2+3", setOf(ServiceType.ROUND_2, ServiceType.ROUND_3)),
    StepOption("Steps 3+4", setOf(ServiceType.ROUND_3, ServiceType.ROUND_4)),
    StepOption("Steps 4+5", setOf(ServiceType.ROUND_4, ServiceType.ROUND_5)),
    StepOption("Steps 5+6", setOf(ServiceType.ROUND_5, ServiceType.ROUND_6)),
)
