package com.pg.rtk.service

object RtkLibNative {
    init {
        System.loadLibrary("rtklib")
    }

    // Initialize RTK processing engine
    external fun initRtkEngine(): Boolean

    // Process raw GNSS measurements (pseudorange, carrier phase, doppler)
    external fun processGnssMeasurements(
        time: Long,
        svid: IntArray,
        constellation: IntArray,
        pseudorange: DoubleArray,
        carrierPhase: DoubleArray,
        cn0: DoubleArray,
        size: Int
    ): DoubleArray // Returns [lat, lon, height, status]

    // Process RTCM correction data
    external fun processRtcmData(data: ByteArray, length: Int): Boolean

    // Get current solution status (1=SINGLE, 2=DGPS, 4=FLOAT, 5=FIX)
    external fun getSolutionStatus(): Int

    // Cleanup
    external fun shutdownRtkEngine()
}
