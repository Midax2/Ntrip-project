package com.pg.rtk.service

import android.location.GnssMeasurementsEvent
import android.location.GnssMeasurement
import android.os.Build
import androidx.annotation.RequiresApi
import com.pg.rtk.data.Coordinate
import com.pg.rtk.data.RtkState
import com.pg.rtk.data.RtkStatus

class RtkEngine(private val onRtkStatusUpdate: (RtkState) -> Unit) {

    private var rtcmMessageCount = 0
    private var currentUncorrected = Coordinate()
    private var currentCorrected = Coordinate()
    private var status = RtkStatus.SINGLE

    init {
        RtkLibNative.initRtkEngine()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun processRawGnssData(event: GnssMeasurementsEvent) {
        val measurements = event.measurements.filter {
            it.hasCarrierFrequencyHz() &&
                    it.state and GnssMeasurement.STATE_CODE_LOCK != 0
        }

        if (measurements.isEmpty()) return

        val svids = IntArray(measurements.size) { measurements[it].svid }
        val constellations = IntArray(measurements.size) { measurements[it].constellationType }
        val pseudoranges = DoubleArray(measurements.size) {
            measurements[it].pseudorangeRateMetersPerSecond
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

        if (result.size >= 4) {
            currentCorrected = Coordinate(result[0], result[1], result[2])
            updateStatusFromSolution(result[3].toInt())
        }

        updateState()
    }

    fun processRtcmData(data: ByteArray) {
        if (RtkLibNative.processRtcmData(data, data.size)) {
            rtcmMessageCount++
            val solutionStatus = RtkLibNative.getSolutionStatus()
            updateStatusFromSolution(solutionStatus)
            updateState()
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
        RtkLibNative.initRtkEngine()
        updateState()
    }

    fun shutdown() {
        RtkLibNative.shutdownRtkEngine()
    }
}
