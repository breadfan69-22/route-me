package com.routeme.app

import java.time.LocalDate
import java.time.MonthDay

const val SHOP_LAT = 42.2478
const val SHOP_LNG = -85.5640

enum class RouteDirection {
    OUTWARD,
    HOMEWARD
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

/**
 * Seasonal date windows for each treatment step.
 * When today falls in a window, those service types are auto-selected.
 * Windows are checked in order; the first match wins.
 */
val STEP_DATE_WINDOWS: List<StepWindow> = listOf(
    // Step 3 + Grub combo: Jun 23 – Jul 15
    StepWindow(setOf(ServiceType.ROUND_3, ServiceType.GRUB), MonthDay.of(6, 23), MonthDay.of(7, 15), grubCombo = true),
    // Step 1: Mar 30 – May 15
    StepWindow(setOf(ServiceType.ROUND_1), MonthDay.of(3, 30), MonthDay.of(5, 15)),
    // Step 2: May 1 – Jun 15
    StepWindow(setOf(ServiceType.ROUND_2), MonthDay.of(5, 1), MonthDay.of(6, 15)),
    // Step 3 (no grub): Jun 15 – Jun 23
    StepWindow(setOf(ServiceType.ROUND_3), MonthDay.of(6, 15), MonthDay.of(6, 23)),
    // Step 4: Aug 1 – Sep 15
    StepWindow(setOf(ServiceType.ROUND_4), MonthDay.of(8, 1), MonthDay.of(9, 15)),
    // Step 5: Sep 15 – Nov 1
    StepWindow(setOf(ServiceType.ROUND_5), MonthDay.of(9, 15), MonthDay.of(11, 1)),
    // Step 6: Nov 1 – Dec 31
    StepWindow(setOf(ServiceType.ROUND_6), MonthDay.of(11, 1), MonthDay.of(12, 31)),
)

/** Returns the auto-suggested service types for [today], or null if no window matches. */
fun suggestedStepsForDate(today: LocalDate = LocalDate.now()): Set<ServiceType>? {
    val md = MonthDay.from(today)
    return STEP_DATE_WINDOWS.firstOrNull { window ->
        md >= window.start && md <= window.end
    }?.serviceTypes
}

data class ServiceRecord(
    val serviceType: ServiceType,
    val arrivedAtMillis: Long? = null,
    val completedAtMillis: Long,
    val durationMinutes: Long,
    val lat: Double?,
    val lng: Double?,
    val notes: String = ""
)

/**
 * Represents a stop at a location that is NOT a client property —
 * e.g. gas station, lunch break, supply store.
 * Logged silently by the tracking service when the user is stationary
 * for longer than the configured threshold.
 */
data class NonClientStop(
    val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    val arrivedAtMillis: Long,
    val departedAtMillis: Long? = null,
    val durationMinutes: Long = 0
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

data class Client(
    val id: String,
    val name: String,
    val address: String,
    val zone: String,
    val notes: String,
    val subscribedSteps: Set<Int>,
    val hasGrub: Boolean,
    val mowDayOfWeek: Int,
    val lawnSizeSqFt: Int,
    val sunShade: String,
    val terrain: String,
    val windExposure: String,
    val cuSpringPending: Boolean = false,
    val cuFallPending: Boolean = false,
    var latitude: Double?,
    var longitude: Double?,
    val records: MutableList<ServiceRecord> = mutableListOf()
)

data class ClientSuggestion(
    val client: Client,
    val daysSinceLast: Int?,
    val distanceMiles: Double?,
    val distanceToShopMiles: Double?,
    val mowWindowPreferred: Boolean,
    val requiresCuOverride: Boolean = false,
    var drivingTime: String? = null,
    var drivingDistance: String? = null,
    /** Which of the active service types this client is actually due for. */
    val eligibleSteps: Set<ServiceType> = emptySet()
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
