package com.pg.rtk.service

import android.location.GnssMeasurementsEvent
import android.location.GnssMeasurement
import com.pg.rtk.data.Coordinate
import com.pg.rtk.data.GnssConstants
import com.pg.rtk.data.RtkState
import com.pg.rtk.data.RtkStatus
import kotlin.math.cos
import kotlin.math.sqrt

class RtkEngine(private val onRtkStatusUpdate: (RtkState) -> Unit) {

    companion object {

        /**
         * Minimum time between UI updates in milliseconds (5 Hz max update rate)
         *
         * Rationale for 200ms (5 Hz) throttling:
         * - GNSS measurements arrive at 1-10 Hz (typically 1 Hz for consumer devices)
         * - RTK position updates can occur at similar rates
         * - UI recomposition is expensive on mobile devices
         * - Human perception doesn't benefit from >5 Hz position updates for navigation
         * - Balances between responsiveness and battery/CPU efficiency
         * - Still allows near-real-time tracking for RTK applications
         *
         * Higher rates (e.g., 10+ Hz) can be used for specialized applications like
         * machine control or surveying equipment, but are unnecessary for typical
         * RTK navigation use cases on mobile devices.
         */
        private const val UPDATE_THROTTLE_MS = 200L

        // Minimum position change to trigger update (meters)
        private const val MIN_POSITION_CHANGE_M = 0.1
    }

    /**
     * Data class for returning throttle check results from synchronized block.
     * Encapsulates all state needed to decide whether to trigger a UI update.
     */
    private data class ThrottleCheckResult(
        val newState: RtkState,
        val statusChanged: Boolean,
        val isFirstUpdate: Boolean,
        val positionChanged: Boolean,
        val timeSinceLastUpdate: Long
    )

    private var rtcmMessageCount = 0
    private var currentUncorrected = Coordinate()
    private var currentCorrected = Coordinate()
    private var status = RtkStatus.SINGLE
    private var lastUpdateTime = 0L
    private var lastState: RtkState? = null

    init {
        RtkLibNative.initRtkEngine()
    }

    /**
     * Calculate pseudorange from GNSS timing measurements
     * Based on Google's GnssLogger implementation
     * Reference: https://github.com/google/gps-measurement-tools
     */
    private fun calculatePseudorange(measurement: GnssMeasurement, clock: android.location.GnssClock): Double {
        // Check required fields
        if (!clock.hasFullBiasNanos() || !clock.hasBiasNanos()) {
            return 0.0
        }

        // Check measurement state - need at least code lock
        val state = measurement.state
        if ((state and GnssMeasurement.STATE_CODE_LOCK) == 0) {
            // No code lock, measurement not valid
            return 0.0
        }

        // Basic sanity check on raw received SV time (must be positive)
        // We intentionally DO NOT enforce an upper bound here, because the
        // interpretation of receivedSvTimeNanos as time-of-week is handled
        // later when we combine it with the receiver's GPS week. A value
        // near the end of the week (close to GPS_WEEK_NANOS) is still valid.
        val tTxNanos = measurement.receivedSvTimeNanos
        if (tTxNanos <= 0L) {
            return 0.0
        }

        val tRxNanos = clock.timeNanos

        val fullBiasNanos = clock.fullBiasNanos

        // Additional bias (sub-millisecond corrections)
        val biasNanos = if (clock.hasBiasNanos()) clock.biasNanos else 0.0

        // Time offset (measurement-specific)
        val timeOffsetNanos = measurement.timeOffsetNanos


        // Receiver time in GPS time scale (nanoseconds since GPS epoch: Jan 6, 1980)
        val tRxGpsNanos = tRxNanos - fullBiasNanos - biasNanos - timeOffsetNanos

        // Get receiver's GPS week and time within week
        val rxWeekNumber = tRxGpsNanos / GnssConstants.GPS_WEEK_NANOS
        val rxTimeInWeek = tRxGpsNanos % GnssConstants.GPS_WEEK_NANOS // kept for clarity / potential future use

        // Satellite transmission time is time-of-week; convert to full GPS time
        val tTxGpsNanos = rxWeekNumber * GnssConstants.GPS_WEEK_NANOS + tTxNanos

        // Calculate time of flight
        var travelTimeNanos = tRxGpsNanos - tTxGpsNanos

        // Handle week rollover: if the computed travel time is more than half a
        // week off, adjust by ±1 week. This allows tTxNanos values near 0 or
        // near GPS_WEEK_NANOS to still be valid after wrapping.
        if (travelTimeNanos > GnssConstants.GPS_WEEK_NANOS / 2) {
            travelTimeNanos -= GnssConstants.GPS_WEEK_NANOS
        } else if (travelTimeNanos < -GnssConstants.GPS_WEEK_NANOS / 2) {
            travelTimeNanos += GnssConstants.GPS_WEEK_NANOS
        }

        // Convert to meters using speed of light
        val pseudorange = travelTimeNanos * GnssConstants.SPEED_OF_LIGHT_M_PER_S * 1e-9

        // Sanity check: satellites at ~20,000–26,000 km
        // Valid pseudorange: MIN_PSEUDORANGE_METERS .. MAX_PSEUDORANGE_METERS
        if (pseudorange < GnssConstants.MIN_PSEUDORANGE_METERS ||
            pseudorange > GnssConstants.MAX_PSEUDORANGE_METERS) {
            return 0.0
        }

        android.util.Log.d(
            "RtkEngine",
            "Valid PR for SVID ${measurement.svid}: $pseudorange m (state=0x${state.toString(16)})"
        )
        return pseudorange
    }

    fun processRawGnssData(event: GnssMeasurementsEvent) {
        // Log clock information for debugging
        android.util.Log.d("RtkEngine", "Clock: timeNanos=${event.clock.timeNanos}, " +
                "fullBiasNanos=${if (event.clock.hasFullBiasNanos()) event.clock.fullBiasNanos else "N/A"}, " +
                "biasNanos=${if (event.clock.hasBiasNanos()) event.clock.biasNanos else "N/A"}")

        val measurements = event.measurements.filter {
            it.hasCarrierFrequencyHz() &&
                    it.state and GnssMeasurement.STATE_CODE_LOCK != 0 &&
                    it.receivedSvTimeNanos != 0L
        }

        if (measurements.isEmpty()) return

        // Log first measurement details
        val m = measurements[0]
        android.util.Log.d("RtkEngine", "First measurement: svid=${m.svid}, " +
                "receivedSvTimeNanos=${m.receivedSvTimeNanos}, " +
                "timeOffsetNanos=${m.timeOffsetNanos}, " +
                "cn0=${m.cn0DbHz}")

        val svids = IntArray(measurements.size) { measurements[it].svid }
        val constellations = IntArray(measurements.size) { measurements[it].constellationType }
        val pseudoranges = DoubleArray(measurements.size) {
            calculatePseudorange(measurements[it], event.clock)
        }
        val carrierPhases = DoubleArray(measurements.size) {
            // Check if carrier phase is available via state flags instead of deprecated hasCarrierPhase()
            if (measurements[it].state and GnssMeasurement.STATE_TOW_DECODED != 0) {
                // Carrier phase available - access directly (property not deprecated, only hasCarrierPhase() method)
                try {
                    measurements[it].accumulatedDeltaRangeMeters
                } catch (_: Exception) {
                    0.0
                }
            } else {
                0.0
            }
        }
        val cn0s = DoubleArray(measurements.size) { measurements[it].cn0DbHz }

        // Call native RTKLIB function
        val result = RtkLibNative.processGnssMeasurements(
            event.clock.timeNanos,
            svids,
            constellations,
            pseudoranges,
            carrierPhases,
            cn0s,
            measurements.size
        )

        // Result array format: [lat, lon, height, status, uncorrected_lat, uncorrected_lon, uncorrected_height]
        // If the native library doesn't provide uncorrected position (result.size < 7),
        // we'll use the corrected position as a fallback
        if (result.size >= 4) {
            synchronized(this) {
                currentCorrected = Coordinate(result[0], result[1], result[2])
                updateStatusFromSolution(result[3].toInt())

                // Update uncorrected position if available, otherwise use corrected as fallback
                if (result.size >= 7) {
                    currentUncorrected = Coordinate(result[4], result[5], result[6])
                } else {
                    // Use corrected position as uncorrected (single point solution)
                    currentUncorrected = currentCorrected
                }
            }
        }

        updateStateThrottled()
    }

    fun processRtcmData(data: ByteArray) {
        if (RtkLibNative.processRtcmData(data, data.size)) {
            synchronized(this) {
                rtcmMessageCount++
                val solutionStatus = RtkLibNative.getSolutionStatus()
                updateStatusFromSolution(solutionStatus)
            }
            updateStateThrottled()
        }
    }

    private fun updateStatusFromSolution(statusCode: Int) {
        val newStatus = when (statusCode) {
            5 -> RtkStatus.FIX
            4 -> RtkStatus.FLOAT
            2 -> RtkStatus.RECEIVING_RTCM
            else -> RtkStatus.SINGLE
        }

        // Don't downgrade from RECEIVING_RTCM to SINGLE if we're actually receiving RTCM data
        // Only upgrade status or stay at RECEIVING_RTCM if we have RTCM messages
        status = when {
            newStatus == RtkStatus.FIX || newStatus == RtkStatus.FLOAT -> newStatus
            rtcmMessageCount > 0 && status == RtkStatus.SINGLE -> RtkStatus.RECEIVING_RTCM
            rtcmMessageCount > 0 && newStatus == RtkStatus.SINGLE -> RtkStatus.RECEIVING_RTCM
            else -> newStatus
        }
    }

    /**
     * Fast distance calculation using Euclidean approximation.
     * Suitable for small distances (< few km) which is typical for RTK positioning.
     * Avoids expensive trigonometric operations (sin, cos, atan2).
     *
     * Approximation: at mid-latitudes, 1° latitude ≈ 111km, 1° longitude ≈ 111km * cos(lat)
     */
    private fun calculateDistanceFast(coord1: Coordinate, coord2: Coordinate): Double {
        // Degrees to meters conversion factors
        val metersPerDegreeLat = 111000.0 // approximately constant
        val avgLat = (coord1.latitude + coord2.latitude) / 2.0
        val metersPerDegreeLon = 111000.0 * cos(Math.toRadians(avgLat))

        // Calculate differences in meters
        val dLatMeters = (coord2.latitude - coord1.latitude) * metersPerDegreeLat
        val dLonMeters = (coord2.longitude - coord1.longitude) * metersPerDegreeLon
        val dHeight = coord2.height - coord1.height

        // Euclidean distance (much faster than Haversine)
        return sqrt(dLatMeters * dLatMeters + dLonMeters * dLonMeters + dHeight * dHeight)
    }

    /**
     * Update state with throttling to prevent excessive UI updates
     */
    private fun updateStateThrottled() {
        val currentTime = System.currentTimeMillis()

        val checkResult = synchronized(this) {
            val timeSince = currentTime - lastUpdateTime

            val state = RtkState(
                uncorrected = currentUncorrected,
                corrected = currentCorrected,
                status = status,
                rtcmMessageCount = rtcmMessageCount
            )

            // Always update if status changed or this is the first update
            val statusChange = lastState?.status != state.status
            val firstUpdate = lastState == null

            // Check if position changed significantly
            val posChange = lastState?.let {
                calculateDistanceFast(it.corrected, state.corrected) >= MIN_POSITION_CHANGE_M
            } != false

            ThrottleCheckResult(
                newState = state,
                statusChanged = statusChange,
                isFirstUpdate = firstUpdate,
                positionChanged = posChange,
                timeSinceLastUpdate = timeSince
            )
        }

        // Update if: status changed, first update, or (enough time passed AND position changed)
        if (checkResult.statusChanged || checkResult.isFirstUpdate ||
            (checkResult.timeSinceLastUpdate >= UPDATE_THROTTLE_MS && checkResult.positionChanged)) {
            onRtkStatusUpdate(checkResult.newState)
            synchronized(this) {
                lastUpdateTime = currentTime
                lastState = checkResult.newState
            }
        }
    }

    /**
     * Reset the RTK engine state and reinitialize the native library.
     *
     * Thread-safe: The entire reset operation is synchronized to prevent race conditions
     * with concurrent GNSS measurement or RTCM data processing.
     */
    fun reset() {
        synchronized(this) {
            // Clear all state variables
            rtcmMessageCount = 0
            status = RtkStatus.SINGLE
            lastUpdateTime = 0L
            lastState = null

            // Reinitialize native engine while holding lock
            // This prevents processRawGnssData/processRtcmData from reading
            // cleared state before initialization completes
            RtkLibNative.initRtkEngine()

            // Force immediate update after reset
            onRtkStatusUpdate(RtkState(
                uncorrected = currentUncorrected,
                corrected = currentCorrected,
                status = status,
                rtcmMessageCount = rtcmMessageCount
            ))
        }
    }

    fun shutdown() {
        RtkLibNative.shutdownRtkEngine()
    }
}
