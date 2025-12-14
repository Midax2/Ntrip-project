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
        val rtcmCountChanged: Boolean,
        val timeSinceLastUpdate: Long
    )

    private var rtcmMessageCount = 0
    private var currentUncorrected = Coordinate()
    private var currentCorrected = Coordinate()
    private var status = RtkStatus.SINGLE
    private var lastUpdateTime = 0L
    private var lastState: RtkState? = null
    private var isNativeInitialized = false

    init {
        try {
            isNativeInitialized = RtkLibNative.initRtkEngine()
            if (isNativeInitialized) {
                android.util.Log.i("RtkEngine", "✓ RTK Engine initialized successfully")
            } else {
                android.util.Log.e("RtkEngine", "✗ RTK Engine initialization FAILED")
            }
        } catch (e: Exception) {
            android.util.Log.e("RtkEngine", "✗ Exception initializing RTK Engine", e)
            isNativeInitialized = false
        }
    }

    /**
     * Calculate pseudorange from GNSS timing measurements
     * Based on Google's GnssLogger implementation
     * Reference: https://github.com/google/gps-measurement-tools
     */
    private fun calculatePseudorange(measurement: GnssMeasurement, clock: android.location.GnssClock): Double {
        val svid = measurement.svid

        // Check required fields
        if (!clock.hasFullBiasNanos()) {
            android.util.Log.w("RtkEngine", "SVID $svid: Clock missing fullBiasNanos")
            return 0.0
        }

        // Note: biasNanos is optional, we handle it below

        // Check measurement state - need at least code lock
        val state = measurement.state
        if ((state and GnssMeasurement.STATE_CODE_LOCK) == 0) {
            android.util.Log.d("RtkEngine", "SVID $svid: No code lock (state=0x${state.toString(16)})")
            return 0.0
        }

        // Basic sanity check on raw received SV time (must be positive)
        val tTxNanos = measurement.receivedSvTimeNanos
        if (tTxNanos <= 0L) {
            android.util.Log.w("RtkEngine", "SVID $svid: Invalid tTxNanos=$tTxNanos")
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

        // IMPORTANT: receivedSvTimeNanos is already full GPS time (not time-of-week!)
        // According to Android documentation, it's the received GNSS satellite time at the measurement time,
        // already in GPS time scale (nanoseconds since GPS epoch)
        val tTxGpsNanos = tTxNanos.toDouble()

        // Calculate travel time (receiver time - transmit time)
        val travelTimeNanos = tRxGpsNanos - tTxGpsNanos

        // Convert to pseudorange in meters
        val pseudorange = travelTimeNanos * GnssConstants.SPEED_OF_LIGHT_M_PER_S * 1e-9

        // Validate pseudorange is within expected bounds
        if (pseudorange < GnssConstants.MIN_PSEUDORANGE_METERS || pseudorange > GnssConstants.MAX_PSEUDORANGE_METERS) {
            android.util.Log.w(
                "RtkEngine",
                "SVID $svid: Pseudorange $pseudorange out of bounds [${GnssConstants.MIN_PSEUDORANGE_METERS}, ${GnssConstants.MAX_PSEUDORANGE_METERS}] " +
                        "(tRxGpsNanos=$tRxGpsNanos, tTxGpsNanos=$tTxGpsNanos, travelTimeNanos=$travelTimeNanos)"
            )
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

        val totalMeasurements = event.measurements.size
        val measurements = event.measurements.filter {
            it.hasCarrierFrequencyHz() &&
                    it.state and GnssMeasurement.STATE_CODE_LOCK != 0 &&
                    it.receivedSvTimeNanos != 0L
        }

        android.util.Log.d("RtkEngine", "Measurements: $totalMeasurements total, ${measurements.size} after filter")

        if (!isNativeInitialized) {
            android.util.Log.e("RtkEngine", "Cannot process measurements - native library not initialized!")
            return
        }

        if (measurements.isEmpty()) {
            android.util.Log.w("RtkEngine", "No measurements passed filter - need carrier freq, code lock, and valid SV time")
            return
        }

        // Build GPS-referenced receiver time (nanoseconds since GPS epoch) to pass to native
        if (!event.clock.hasFullBiasNanos()) {
            android.util.Log.w("RtkEngine", "Clock missing fullBiasNanos - cannot compute GPS receiver time")
            return
        }
        val clock = event.clock
        val biasNanos = if (clock.hasBiasNanos()) clock.biasNanos else 0.0
        val tRxGpsNanosDouble = clock.timeNanos - clock.fullBiasNanos - biasNanos
        val tRxGpsNanos = tRxGpsNanosDouble.toLong()

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
            // Use accumulatedDeltaRangeMeters when available (returns meters)
            try {
                measurements[it].accumulatedDeltaRangeMeters
            } catch (_: Exception) {
                0.0
            }
        }
        val cn0s = DoubleArray(measurements.size) { measurements[it].cn0DbHz }

        // Log measurement summary for debugging
        val validPseudoranges = pseudoranges.count { it > 0.0 }
        android.util.Log.d("RtkEngine", "Sending to native: ${measurements.size} measurements, " +
                "$validPseudoranges valid pseudoranges")

        // Log first few pseudoranges
        if (validPseudoranges > 0) {
            val samples = pseudoranges.take(3).filter { it > 0.0 }
            android.util.Log.d("RtkEngine", "Sample pseudoranges: ${samples.joinToString()}")
        } else {
            android.util.Log.w("RtkEngine", "WARNING: No valid pseudoranges calculated!")
        }

        // Call native RTKLIB function - pass GPS-referenced receiver time
        val result = RtkLibNative.processGnssMeasurements(
            tRxGpsNanos,
            svids,
            constellations,
            pseudoranges,
            carrierPhases,
            cn0s,
            measurements.size
        )

        // Log result from native
        android.util.Log.d("RtkEngine", "Native returned array size: ${result.size}")
        if (result.isNotEmpty()) {
            android.util.Log.d("RtkEngine", "Result: lat=${result[0]}, lon=${result[1]}, " +
                    "height=${result[2]}, status=${result.getOrNull(3)}")
        } else {
            android.util.Log.e("RtkEngine", "ERROR: Native returned empty array!")
        }

        // Result array format: [lat, lon, height, status, uncorrected_lat, uncorrected_lon, uncorrected_height]
        // If the native library doesn't provide uncorrected position (result.size < 7),
        // we'll use the corrected position as a fallback
        if (result.size >= 4) {
            // Check if we got actual position data or just zeros
            if (result[0] == 0.0 && result[1] == 0.0 && result[2] == 0.0) {
                android.util.Log.w("RtkEngine", "Native returned zeros for position - " +
                        "positioning failed or insufficient measurements")
                // Don't update position if it's all zeros
            } else {
                synchronized(this) {
                    currentCorrected = Coordinate(result[0], result[1], result[2])
                    updateStatusFromSolution(result[3].toInt())

                    android.util.Log.i("RtkEngine", "Position updated: " +
                            "${result[0]}, ${result[1]}, ${result[2]}m, status=${result[3].toInt()}")

                    // Update uncorrected position if available, otherwise use corrected as fallback
                    if (result.size >= 7) {
                        currentUncorrected = Coordinate(result[4], result[5], result[6])
                    } else {
                        // Use corrected position as uncorrected (single point solution)
                        currentUncorrected = currentCorrected
                    }
                }
            }
        } else {
            android.util.Log.e("RtkEngine", "Result array too small: ${result.size} < 4")
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
        // RTKLIB status codes (from rtklib.h SOLQ_ constants):
        // 0 = NONE (no solution)
        // 1 = FIX (ambiguity fixed, cm-level accuracy)
        // 2 = FLOAT (ambiguity float, dm-level accuracy)
        // 3 = SBAS (satellite-based augmentation)
        // 4 = DGPS (differential GPS, code-based, m-level accuracy)
        // 5 = SINGLE (single point positioning, 5-10m accuracy)
        // 6 = PPP (precise point positioning)
        // 7 = DR (dead reckoning)

        val newStatus = when (statusCode) {
            1 -> RtkStatus.FIX       // SOLQ_FIX
            2 -> RtkStatus.FLOAT     // SOLQ_FLOAT
            4 -> RtkStatus.RECEIVING_RTCM  // SOLQ_DGPS (we received corrections)
            5 -> RtkStatus.SINGLE    // SOLQ_SINGLE
            else -> RtkStatus.SINGLE // Default to SINGLE for unknown codes
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

            // Check if RTCM count changed (important for showing ongoing data reception)
            val rtcmChange = lastState?.rtcmMessageCount != state.rtcmMessageCount

            ThrottleCheckResult(
                newState = state,
                statusChanged = statusChange,
                isFirstUpdate = firstUpdate,
                positionChanged = posChange,
                rtcmCountChanged = rtcmChange,
                timeSinceLastUpdate = timeSince
            )
        }

        // Update if: status changed, first update, RTCM count changed, or (enough time passed AND position changed)
        if (checkResult.statusChanged || checkResult.isFirstUpdate || checkResult.rtcmCountChanged ||
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
