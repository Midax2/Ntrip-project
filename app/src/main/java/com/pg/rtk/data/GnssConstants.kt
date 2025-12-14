package com.pg.rtk.data

/**
 * Shared GNSS/RTK-related physical and configuration constants.
 *
 * Centralizing these values avoids magic numbers scattered across the codebase
 * and keeps Kotlin and native (C++) code conceptually in sync.
 */
object GnssConstants {

    /**
     * Speed of light in vacuum (m/s).
     *
     * This should match the SPEED_OF_LIGHT constant in the native C++ code
     */
    const val SPEED_OF_LIGHT_M_PER_S: Double = 299_792_458.0

    /**
     * Duration of one GPS week in nanoseconds.
     * 7 days * 24 hours * 3600 seconds * 1e9 nanoseconds.
     */
    const val GPS_WEEK_NANOS: Long = 604_800_000_000_000L

    /**
     * Expected pseudorange bounds in meters for healthy GNSS signals.
     *
     * Rationale:
     * - GNSS satellites are roughly 20,000–26,000 km from Earth's surface.
     * - Geometric range is typically around 20,000 km.
     * - Add margin for atmospheric delays, clock errors, etc.
     * - Light travel time for 19–30 Mm is ~0.063–0.100 seconds.
     */
    const val MIN_PSEUDORANGE_METERS: Double = 19_000_000.0  // 19 Mm
    const val MAX_PSEUDORANGE_METERS: Double = 30_000_000.0  // 30 Mm
}