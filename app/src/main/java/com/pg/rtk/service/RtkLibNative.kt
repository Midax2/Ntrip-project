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

    // Get current solution status
    // RTKLIB codes: 0=NONE, 1=FIX, 2=FLOAT, 3=SBAS, 4=DGPS, 5=SINGLE, 6=PPP, 7=DR
    external fun getSolutionStatus(): Int

    // Cleanup
    external fun shutdownRtkEngine()
}
