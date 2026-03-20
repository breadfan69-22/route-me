package com.routeme.app.util

object AppConfig {
    object Tracking {
        const val ARRIVAL_RADIUS_METERS = 60f
        const val ONSITE_RADIUS_METERS = 150f
        const val MODE_SWITCH_RADIUS_METERS = 200f
        const val CLUSTER_RADIUS_METERS = 125f

        const val DWELL_THRESHOLD_MS = 60_000L
        const val JOB_MIN_DURATION_MS = 3 * 60 * 1000L
        const val MODE_SWITCH_COOLDOWN_MS = 10_000L

        const val DRIVING_INTERVAL_MS = 30_000L
        const val DRIVING_MIN_DISTANCE_METERS = 25f

        const val ARRIVAL_INTERVAL_MS = 10_000L
        const val ARRIVAL_MIN_DISTANCE_METERS = 0f

        const val GPS_STALE_FOR_FALLBACK_MS = 25_000L
        const val GPS_WEAK_ACCURACY_METERS = 45f
        const val PROVIDER_REFRESH_COOLDOWN_MS = 30_000L

        const val NON_CLIENT_STOP_RADIUS_METERS = 150f
        const val NON_CLIENT_DEPART_RADIUS_METERS = 200f
        const val NON_CLIENT_SHOP_LABEL_RADIUS_METERS = 100f

        const val DESTINATION_RADIUS_METERS = 150f
        const val DESTINATION_DWELL_MS = 3 * 60 * 1000L
    }

    object SheetsWriteBack {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_REDIRECTS = 5
    }

    object RetryQueue {
        const val MAX_WRITE_BACK_RETRIES = 10
    }

    object Routing {
        const val ORDER_HOP_PENALTY_PER_MILE = 14.0
        const val ORDER_BASE_SCORE_WEIGHT = 0.65
        const val ORDER_OVERDUE_DIVISOR_DAYS = 10.0
        const val ORDER_OVERDUE_BONUS_MAX = 15.0

        const val DESTINATION_DELTA_BETTER_THRESHOLD_MILES = -0.2
        const val DESTINATION_DELTA_WORSE_THRESHOLD_MILES = 0.8
        const val DESTINATION_BETTER_BONUS = 28.0
        const val DESTINATION_WORSE_PENALTY = -35.0
        const val DESTINATION_NEUTRAL_ADJUSTMENT = -8.0

        const val OUTWARD_DELTA_BETTER_THRESHOLD_MILES = 0.2
        const val OUTWARD_DELTA_WORSE_THRESHOLD_MILES = -0.8
        const val OUTWARD_BETTER_BONUS = 28.0
        const val OUTWARD_WORSE_PENALTY = -35.0
        const val OUTWARD_NEUTRAL_ADJUSTMENT = -8.0

        const val HOMEWARD_DELTA_BETTER_THRESHOLD_MILES = -0.2
        const val HOMEWARD_DELTA_WORSE_THRESHOLD_MILES = 0.8
        const val HOMEWARD_BETTER_BONUS = 28.0
        const val HOMEWARD_WORSE_PENALTY = -35.0
        const val HOMEWARD_NEUTRAL_ADJUSTMENT = -8.0

        const val DISTANCE_LT_0_5_MILES = 0.5
        const val DISTANCE_LT_1_MILES = 1.0
        const val DISTANCE_LT_2_MILES = 2.0
        const val DISTANCE_LT_3_MILES = 3.0
        const val DISTANCE_LT_5_MILES = 5.0
        const val DISTANCE_LT_8_MILES = 8.0
        const val DISTANCE_LT_12_MILES = 12.0

        const val DISTANCE_LT_0_5_SCORE = 220.0
        const val DISTANCE_LT_1_SCORE = 160.0
        const val DISTANCE_LT_2_SCORE = 100.0
        const val DISTANCE_LT_3_SCORE = 55.0
        const val DISTANCE_LT_5_SCORE = 20.0
        const val DISTANCE_LT_8_SCORE = -20.0
        const val DISTANCE_LT_12_SCORE = -60.0
        const val DISTANCE_FAR_SCORE = -120.0
        const val DISTANCE_PENALTY_PER_MILE = 8.0

        const val NO_DISTANCE_BASE_SCORE = 40.0
        const val NO_DISTANCE_DISTANCE_MULTIPLIER = 3.0
        const val NO_DISTANCE_MIN_SCORE = -50.0

        const val DIRECTION_DELTA_NEAR_ZERO_MILES = 0.75
        const val DIRECTION_NEAR_ZERO_BONUS = 30.0

        const val OUTWARD_DELTA_RANGE_MIN_MILES = 0.75
        const val OUTWARD_DELTA_RANGE_MAX_MILES = 4.0
        const val OUTWARD_RANGE_BONUS = 60.0
        const val OUTWARD_FAR_BONUS = 20.0
        const val OUTWARD_REVERSE_THRESHOLD_MILES = -1.0
        const val OUTWARD_REVERSE_PENALTY = -55.0
        const val OUTWARD_DEFAULT_ADJUSTMENT = -15.0

        const val HOMEWARD_DELTA_RANGE_MIN_MILES = -4.0
        const val HOMEWARD_DELTA_RANGE_MAX_MILES = -0.75
        const val HOMEWARD_RANGE_BONUS = 60.0
        const val HOMEWARD_FAR_BONUS = 20.0
        const val HOMEWARD_REVERSE_THRESHOLD_MILES = 1.0
        const val HOMEWARD_REVERSE_PENALTY = -55.0
        const val HOMEWARD_DEFAULT_ADJUSTMENT = -15.0

        const val NEVER_SERVED_BONUS = 35.0
        const val OVERDUE_BASE_BONUS = 10.0
        const val OVERDUE_PER_DAY_BONUS = 0.8
        const val OVERDUE_60_DAY_THRESHOLD = 60
        const val OVERDUE_60_DAY_BONUS = 10.0
        const val OVERDUE_90_DAY_THRESHOLD = 90
        const val OVERDUE_90_DAY_BONUS = 15.0
        const val CU_OVERRIDE_PENALTY = -40.0

        const val MOW_SAME_DAY_PENALTY = -180.0
        const val MOW_ADJACENT_DAY_PENALTY = -120.0
        const val MOW_NEAR_TERM_BONUS = 15.0
        const val MOW_DEFAULT_BONUS = 5.0

        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        const val EARTH_RADIUS_MILES = 3958.7613

        const val TWO_OPT_MAX_PASSES = 50
        const val TWO_OPT_IMPROVEMENT_EPSILON = 0.01

        // When ordering the route, give a strong bonus to any candidate that is
        // within cluster radius of the current route position (i.e. a next-door
        // neighbor of the stop just picked). Keeps cluster properties adjacent.
        const val CLUSTER_RADIUS_MILES = 0.078   // ~125 m (~410 ft)
        // Cluster bonus requires BOTH proximity AND same street name.
        const val CLUSTER_NEIGHBOR_BONUS = 80.0
        // Driving distance ceiling: if a cluster pair exceeds this, the cluster
        // bonus is revoked (river/highway barrier makes them not truly adjacent).
        const val CLUSTER_MAX_DRIVING_MILES = 0.25
        // Applied when a candidate shares a street name with the last-picked stop,
        // even if outside cluster radius. Breaks ties between near-equal distances.
        const val SAME_STREET_BONUS = 20.0
    }
}
