package com.routeme.app

import java.time.LocalDate
import java.time.MonthDay

const val SHOP_LAT = 42.3385
const val SHOP_LNG = -85.5576

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
