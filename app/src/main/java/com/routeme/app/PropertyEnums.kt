package com.routeme.app

enum class SunShade {
    FULL_SUN,
    PARTIAL_SHADE,
    FULL_SHADE,
    UNKNOWN;

    companion object {
        fun fromStorage(value: String?): SunShade {
            val normalized = value
                ?.trim()
                ?.uppercase()
                ?.replace('/', '_')
                ?.replace(' ', '_')
                .orEmpty()

            return when (normalized) {
                "FULL_SUN" -> FULL_SUN
                "PARTIAL_SHADE" -> PARTIAL_SHADE
                "FULL_SHADE" -> FULL_SHADE
                else -> UNKNOWN
            }
        }
    }
}

enum class WindExposure {
    EXPOSED,
    SHELTERED,
    MIXED,
    UNKNOWN;

    companion object {
        fun fromStorage(value: String?): WindExposure {
            val normalized = value
                ?.trim()
                ?.uppercase()
                ?.replace('/', '_')
                ?.replace(' ', '_')
                .orEmpty()

            return when (normalized) {
                "EXPOSED" -> EXPOSED
                "SHELTERED" -> SHELTERED
                "MIXED" -> MIXED
                else -> UNKNOWN
            }
        }
    }
}
