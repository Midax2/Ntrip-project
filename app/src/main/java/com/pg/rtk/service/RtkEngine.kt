package com.pg.rtk.service

import android.location.GnssMeasurementsEvent
import android.location.GnssMeasurement
import android.os.Build
import androidx.annotation.RequiresApi
import com.pg.rtk.data.Coordinate
import com.pg.rtk.data.RtkState
import com.pg.rtk.data.RtkStatus

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

    @RequiresApi(Build.VERSION_CODES.O)
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
            if (measurements[it].hasCarrierPhase()) measurements[it].carrierPhase else 0.0
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

        updateStateThrottled()
    }

    fun processRtcmData(data: ByteArray) {
        if (RtkLibNative.processRtcmData(data, data.size)) {
            rtcmMessageCount++
            val solutionStatus = RtkLibNative.getSolutionStatus()
            updateStatusFromSolution(solutionStatus)
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
     * Calculate distance between two coordinates in meters (simplified Haversine)
     */
    private fun calculateDistance(coord1: Coordinate, coord2: Coordinate): Double {
        val lat1Rad = Math.toRadians(coord1.latitude)
        val lat2Rad = Math.toRadians(coord2.latitude)
        val lon1Rad = Math.toRadians(coord1.longitude)
        val lon2Rad = Math.toRadians(coord2.longitude)

        val dLat = lat2Rad - lat1Rad
        val dLon = lon2Rad - lon1Rad
        val dHeight = coord2.height - coord1.height

        // Simplified distance calculation
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        val earthRadius = 6371000.0 // meters
        val horizontalDist = earthRadius * c

        return Math.sqrt(horizontalDist * horizontalDist + dHeight * dHeight)
    }

    /**
     * Update state with throttling to prevent excessive UI updates
     */
    private fun updateStateThrottled() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastUpdate = currentTime - lastUpdateTime

        val newState = RtkState(
            uncorrected = currentUncorrected,
            corrected = currentCorrected,
            status = status,
            rtcmMessageCount = rtcmMessageCount
        )

        // Always update if status changed or this is the first update
        val statusChanged = lastState?.status != newState.status
        val isFirstUpdate = lastState == null

        // Check if position changed significantly
        val positionChanged = lastState?.let {
            calculateDistance(it.corrected, newState.corrected) >= MIN_POSITION_CHANGE_M
        } ?: true

        // Update if: status changed, first update, or (enough time passed AND position changed)
        if (statusChanged || isFirstUpdate ||
            (timeSinceLastUpdate >= UPDATE_THROTTLE_MS && positionChanged)) {
            onRtkStatusUpdate(newState)
            lastUpdateTime = currentTime
            lastState = newState
        }
    }

    private fun updateState() {
        onRtkStatusUpdate(RtkState(
            uncorrected = currentUncorrected,
            corrected = currentCorrected,
            status = status,
            rtcmMessageCount = rtcmMessageCount
        ))
    }

    fun reset() {
        rtcmMessageCount = 0
        status = RtkStatus.SINGLE
        lastUpdateTime = 0L
        lastState = null
        RtkLibNative.initRtkEngine()
        updateState()
    }

    fun shutdown() {
        RtkLibNative.shutdownRtkEngine()
    }
}
