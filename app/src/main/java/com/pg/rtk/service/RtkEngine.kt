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
    private var hasLoggedFullBiasWarning = false // Track if we've already warned about fullBiasNanos

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
        val biasNanos = if (clock.hasBiasNanos()) clock.biasNanos else 0.0
        val timeOffsetNanos = measurement.timeOffsetNanos

        // Receiver time in GPS time scale (nanoseconds since GPS epoch: Jan 6, 1980)
        val tRxGpsNanos = tRxNanos - fullBiasNanos - biasNanos - timeOffsetNanos

        // Satellite transmit time calculation
        val receivedSvTimeNanos = measurement.receivedSvTimeNanos

        // Convert satellite time to GPS time scale
        val tTxGpsNanos = receivedSvTimeNanos.toDouble() - fullBiasNanos

        // Calculate travel time (receiver time - transmit time)
        // This should be positive (typically 0.06-0.08 seconds for ~20,000 km distance)
        val travelTimeNanos = tRxGpsNanos - tTxGpsNanos

        // Convert to pseudorange in meters
        val pseudorange = travelTimeNanos * GnssConstants.SPEED_OF_LIGHT_M_PER_S * 1e-9

        // Validate pseudorange is within expected bounds
        if (pseudorange < GnssConstants.MIN_PSEUDORANGE_METERS || pseudorange > GnssConstants.MAX_PSEUDORANGE_METERS) {
            // Log detailed information for first failed measurement
            if (!hasLoggedFullBiasWarning) {
                android.util.Log.e("RtkEngine", "════════════════════════════════════════════════════════")
                android.util.Log.e("RtkEngine", "PSEUDORANGE OUT OF BOUNDS for SVID $svid")
                android.util.Log.e("RtkEngine", "Calculated pseudorange: $pseudorange meters")
                android.util.Log.e("RtkEngine", "Valid range: ${GnssConstants.MIN_PSEUDORANGE_METERS} to ${GnssConstants.MAX_PSEUDORANGE_METERS} meters")
                android.util.Log.e("RtkEngine", "────────────────────────────────────────────────────────")
                android.util.Log.e("RtkEngine", "Clock values:")
                android.util.Log.e("RtkEngine", "  timeNanos: $tRxNanos")
                android.util.Log.e("RtkEngine", "  fullBiasNanos: $fullBiasNanos")
                android.util.Log.e("RtkEngine", "  biasNanos: $biasNanos")
                android.util.Log.e("RtkEngine", "  hasFullBiasNanos: ${clock.hasFullBiasNanos()}")
                android.util.Log.e("RtkEngine", "  hasBiasNanos: ${clock.hasBiasNanos()}")
                android.util.Log.e("RtkEngine", "────────────────────────────────────────────────────────")
                android.util.Log.e("RtkEngine", "Measurement values:")
                android.util.Log.e("RtkEngine", "  receivedSvTimeNanos: $tTxNanos")
                android.util.Log.e("RtkEngine", "  timeOffsetNanos: $timeOffsetNanos")
                android.util.Log.e("RtkEngine", "  state: 0x${state.toString(16)}")
                android.util.Log.e("RtkEngine", "  STATE_CODE_LOCK: ${(state and GnssMeasurement.STATE_CODE_LOCK) != 0}")
                android.util.Log.e("RtkEngine", "────────────────────────────────────────────────────────")
                android.util.Log.e("RtkEngine", "Calculated values:")
                android.util.Log.e("RtkEngine", "  tRxGpsNanos: $tRxGpsNanos")
                android.util.Log.e("RtkEngine", "  tTxGpsNanos: $tTxGpsNanos")
                android.util.Log.e("RtkEngine", "  travelTimeNanos: $travelTimeNanos")
                android.util.Log.e("RtkEngine", "════════════════════════════════════════════════════════")
                hasLoggedFullBiasWarning = true
            }
            return 0.0
        }

        return pseudorange
    }

    fun processRawGnssData(event: GnssMeasurementsEvent) {
        val totalMeasurements = event.measurements.size
        val measurements = event.measurements.filter {
            it.hasCarrierFrequencyHz() &&
                    it.state and GnssMeasurement.STATE_CODE_LOCK != 0 &&
                    it.receivedSvTimeNanos != 0L
        }

        if (!isNativeInitialized) {
            android.util.Log.e("RtkEngine", "Cannot process measurements - native library not initialized!")
            return
        }

        if (measurements.isEmpty()) {
            android.util.Log.w("RtkEngine", "No measurements passed filter (total: $totalMeasurements)")
            return
        }

        // Build GPS-referenced receiver time (nanoseconds since GPS epoch) to pass to native
        if (!event.clock.hasFullBiasNanos()) {
            if (!hasLoggedFullBiasWarning) {
                android.util.Log.e("RtkEngine", "════════════════════════════════════════════════════════")
                android.util.Log.e("RtkEngine", "CRITICAL: Clock missing fullBiasNanos")
                android.util.Log.e("RtkEngine", "Device provides raw GNSS measurements but NOT fullBiasNanos")
                android.util.Log.e("RtkEngine", "This is required to calculate pseudoranges for RTK positioning")
                android.util.Log.e("RtkEngine", "════════════════════════════════════════════════════════")
                android.util.Log.e("RtkEngine", "Your device: Can receive GNSS measurements ✓")
                android.util.Log.e("RtkEngine", "Your device: Can provide fullBiasNanos ✗")
                android.util.Log.e("RtkEngine", "════════════════════════════════════════════════════════")
                android.util.Log.e("RtkEngine", "This is a hardware/firmware limitation, NOT a software bug")
                android.util.Log.e("RtkEngine", "Compatible devices: Pixel 4+, Samsung S20+, Xiaomi Mi 8+")
                android.util.Log.e("RtkEngine", "════════════════════════════════════════════════════════")
                hasLoggedFullBiasWarning = true
            }
            // Don't return - this is a critical error but we logged it
            return
        }
        val clock = event.clock
        val biasNanos = if (clock.hasBiasNanos()) clock.biasNanos else 0.0
        val tRxGpsNanosDouble = clock.timeNanos - clock.fullBiasNanos - biasNanos
        val tRxGpsNanos = tRxGpsNanosDouble.toLong()

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

        // Only log summary if there are issues with pseudorange calculation
        val validPseudoranges = pseudoranges.count { it > 0.0 }
        if (validPseudoranges == 0) {
            android.util.Log.w("RtkEngine", "WARNING: No valid pseudoranges calculated from ${measurements.size} measurements!")
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

        if (result.isEmpty()) {
            android.util.Log.e("RtkEngine", "ERROR: Native returned empty array!")
            return
        }

        // Result array format: [lat, lon, height, status, uncorrected_lat, uncorrected_lon, uncorrected_height]
        // If the native library doesn't provide uncorrected position (result.size < 7),
        // we'll use the corrected position as a fallback
        if (result.size >= 4) {
            // Check if we got actual position data or just zeros
            if (result[0] == 0.0 && result[1] == 0.0 && result[2] == 0.0) {
                // Don't update position if it's all zeros (positioning failed)
                // This prevents "blinking" where valid positions get overwritten with zeros
                android.util.Log.w("RtkEngine", "Received zero position from native, keeping last valid position")
                return
            } else {
                synchronized(this) {
                    val oldStatus = status
                    val newCorrected = Coordinate(result[0], result[1], result[2])

                    // Update status first
                    updateStatusFromSolution(result[3].toInt())

                    // Only log when status changes (e.g., SINGLE -> FIX) or position changes significantly
                    val posChanged = calculateDistanceFast(currentCorrected, newCorrected) > MIN_POSITION_CHANGE_M
                    if (oldStatus != status || posChanged) {
                        android.util.Log.i("RtkEngine", "Position update: Status=$status at " +
                                "${result[0]}, ${result[1]}, ${result[2]}m")
                    }

                    currentCorrected = newCorrected

                    // Update uncorrected position if available, otherwise use corrected as fallback
                    if (result.size >= 7 && !(result[4] == 0.0 && result[5] == 0.0 && result[6] == 0.0)) {
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
