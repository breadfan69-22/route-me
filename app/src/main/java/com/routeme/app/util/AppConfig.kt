package com.routeme.app.util

object AppConfig {
    object WeeklyPlanner {
        const val IDEAL_TEMP_LOW_F = 50
        const val IDEAL_TEMP_HIGH_F = 85
        const val TEMP_PENALTY_PER_DEGREE = 2

        const val WIND_CALM_MPH = 8
        const val WIND_MODERATE_MPH = 15
        const val WIND_HIGH_MPH = 20
        const val WIND_CALM_SCORE = 25
        const val WIND_MODERATE_PENALTY = -15
        const val WIND_HIGH_PENALTY = -35
        const val WIND_SEVERE_PENALTY = -50

        const val PRECIP_LOW_PCT = 20
        const val PRECIP_MODERATE_PCT = 50
        const val PRECIP_HIGH_PCT = 80
        const val PRECIP_LOW_SCORE = 25
        const val PRECIP_MODERATE_PENALTY = -15
        const val PRECIP_HIGH_PENALTY = -40
        const val PRECIP_SEVERE_PENALTY = -60

        const val SEVERE_WEATHER_FLOOR = 10

        /** Saturday and Sunday are never auto-scheduled; Saturday is for manual commercial jobs. */
        const val SUNDAY_EXCLUDED = true

        const val FITNESS_GREAT_THRESHOLD = 80
        const val FITNESS_GOOD_THRESHOLD = 60
        const val FITNESS_FAIR_THRESHOLD = 40

        const val PRECIP_PROB_TO_INCHES_THRESHOLD_PCT = 50
        const val PRECIP_PROB_ESTIMATED_INCHES = 0.25

        const val WORKDAY_SEVERE_WEATHER_MIN_SCORE = 20

        /** Hard cap: no more than this many client stops per work day. */
        const val MAX_CLIENTS_PER_DAY = 25
        /** Hard cap: no more than this many sq ft of lawn per work day (when sq ft data available). */
        const val MAX_SQFT_PER_DAY = 500_000

        /** Radius within which a client is considered "nearby" an already-assigned day client. */
        const val GEO_AFFINITY_RADIUS_MILES = 3.0
        /** Maximum bonus score added when a client is right next to an already-assigned day client. */
        const val GEO_AFFINITY_MAX_BONUS = 30
        /** Bonus added to keep cluster members on the same day as the first-assigned member. */
        const val CLUSTER_COHESION_BONUS = 50

        /**
         * Zone density bonus: per same-zone client already assigned to a day.
         * Accumulates up to ZONE_DENSITY_BONUS_MAX so that zones cluster together
         * without overriding a meaningful mow-window or weather score difference.
         */
        const val ZONE_DENSITY_BONUS_PER_CLIENT = 12
        const val ZONE_DENSITY_BONUS_MAX = 30

        /**
         * Corridor bonus: when a day has an anchor point set, clients near the
         * shop→anchor travel line receive this bonus.  Scales linearly from max
         * at 0 miles off-corridor to zero at CORRIDOR_MAX_OFFSET_MILES.
         */
        const val CORRIDOR_BONUS_MAX = 40
        const val CORRIDOR_MAX_OFFSET_MILES = 8.0

        /** Steps that are liquid applications and must not be rained on for 24 hours. */
        val LIQUID_STEPS = setOf(2, 5)
        /** Steps that are granular — rain logic takes precedence when mixed with liquid steps. */
        val GRANULAR_STEPS = setOf(1, 3, 4, 6)
        /** Rain probability threshold above which liquid steps incur a heavy penalty. */
        const val LIQUID_STEP_RAIN_PENALTY_PCT = 70
        /** Score penalty applied when rain is expected on a liquid-step day. */
        const val LIQUID_STEP_RAIN_PENALTY = 40
    }

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
        const val DESTINATION_DWELL_MS = 90 * 1000L
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
        /** Set to true to log per-client score breakdowns to Logcat (tag: RoutingScore). */
        const val DEBUG_SCORING_ENABLED = false

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

        const val WEATHER_WIND_EXPOSED_PENALTY = -80.0
        const val WEATHER_WIND_THRESHOLD_MPH = 20
        const val WEATHER_WIND_GUST_THRESHOLD_MPH = 30
        const val WEATHER_CALM_EXPOSED_BONUS = 40.0
        const val WEATHER_CALM_THRESHOLD_MPH = 8
        const val WEATHER_CALM_LARGE_LAWN_SQFT = 15_000
        const val WEATHER_SHADE_HOT_BONUS = 25.0
        const val WEATHER_HOT_THRESHOLD_F = 90
        const val WEATHER_SLOPE_RAIN_PENALTY = -70.0
        const val WEATHER_RAIN_LOOKBACK_DAYS = 2
        const val WEATHER_SLOPE_RAIN_THRESHOLD_INCHES = 0.25
        const val WEATHER_RECENT_RAIN_24H_WET_THRESHOLD_INCHES = 0.10
        const val WEATHER_RECENT_SLOPE_RAIN_PENALTY = -12.0
        const val WEATHER_RECENT_LIQUID_RAIN_PENALTY = -10.0
        const val WEATHER_RAIN_SERVICE_PENALTY = -50.0
        const val WEATHER_RAIN_LIGHT_THRESHOLD = 0.10
        const val WEATHER_IRRIGATED_DRY_BONUS = 10.0
        const val WEATHER_DRY_HOT_THRESHOLD_F = 85

        const val WEATHER_SOIL_MOISTURE_MODERATE_THRESHOLD = 0.30
        const val WEATHER_SOIL_MOISTURE_HIGH_THRESHOLD = 0.45
        const val WEATHER_SLOPE_SOIL_MODERATE_PENALTY = -6.0
        const val WEATHER_SLOPE_SOIL_HIGH_PENALTY = -10.0
        const val WEATHER_SOIL_HIGH_GENERAL_PENALTY = -3.0

        const val WEATHER_DRY_WINDOW_RAIN24_MAX_INCHES = 0.03
        const val WEATHER_SOIL_DRY_THRESHOLD = 0.20
        const val WEATHER_DRY_WINDOW_NON_IRRIGATED_BONUS = 4.0

        const val WEATHER_ADJUSTMENT_MIN = -24.0
        const val WEATHER_ADJUSTMENT_MAX = 16.0
    }
}
