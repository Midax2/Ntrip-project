package com.pg.rtk.service

import android.location.GnssMeasurementsEvent
import android.location.GnssMeasurement
import com.pg.rtk.data.Coordinate
import com.pg.rtk.data.RtkState
import com.pg.rtk.data.RtkStatus
import kotlin.math.cos
import kotlin.math.sqrt

class RtkEngine(private val onRtkStatusUpdate: (RtkState) -> Unit) {

    companion object {
        // Speed of light in m/s
        private const val SPEED_OF_LIGHT = 299792458.0
        // Minimum time between UI updates in milliseconds
        private const val UPDATE_THROTTLE_MS = 200L // 5 Hz max update rate
        // Minimum position change to trigger update (meters)
        private const val MIN_POSITION_CHANGE_M = 0.1
    }

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
     * Pseudorange = (received time - transmit time) * speed of light
     */
    private fun calculatePseudorange(measurement: GnssMeasurement, receiverTimeNanos: Long): Double {
        val tRxNanos = receiverTimeNanos
        val tTxNanos = measurement.receivedSvTimeNanos
        val timeOffsetNanos = measurement.timeOffsetNanos

        // Calculate time difference in nanoseconds and convert to meters
        val travelTimeNanos = tRxNanos - tTxNanos - timeOffsetNanos
        return travelTimeNanos * SPEED_OF_LIGHT / 1e9
    }

    fun processRawGnssData(event: GnssMeasurementsEvent) {
        val measurements = event.measurements.filter {
            it.hasCarrierFrequencyHz() &&
                    it.state and GnssMeasurement.STATE_CODE_LOCK != 0 &&
                    it.receivedSvTimeNanos != 0L
        }

        if (measurements.isEmpty()) return

        val svids = IntArray(measurements.size) { measurements[it].svid }
        val constellations = IntArray(measurements.size) { measurements[it].constellationType }
        val pseudoranges = DoubleArray(measurements.size) {
            calculatePseudorange(measurements[it], event.clock.timeNanos)
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
        status = when (statusCode) {
            5 -> RtkStatus.FIX
            4 -> RtkStatus.FLOAT
            2 -> RtkStatus.RECEIVING_RTCM
            else -> RtkStatus.SINGLE
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

        val (newState, statusChanged, isFirstUpdate, positionChanged, timeSinceLastUpdate) = synchronized(this) {
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
            } ?: true

            Tuple5(state, statusChange, firstUpdate, posChange, timeSince)
        }

        // Update if: status changed, first update, or (enough time passed AND position changed)
        if (statusChanged || isFirstUpdate ||
            (timeSinceLastUpdate >= UPDATE_THROTTLE_MS && positionChanged)) {
            onRtkStatusUpdate(newState)
            synchronized(this) {
                lastUpdateTime = currentTime
                lastState = newState
            }
        }
    }

    // Helper data class for returning multiple values from synchronized block
    private data class Tuple5<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )

    fun reset() {
        synchronized(this) {
            rtcmMessageCount = 0
            status = RtkStatus.SINGLE
            lastUpdateTime = 0L
            lastState = null
        }
        RtkLibNative.initRtkEngine()
        // Force immediate update after reset
        synchronized(this) {
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
