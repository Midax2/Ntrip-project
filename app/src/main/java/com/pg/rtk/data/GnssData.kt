package com.pg.rtk.data

data class Coordinate(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val height: Double = 0.0
)

enum class RtkStatus(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING_NTRIP("Connecting NTRIP..."),
    RECEIVING_RTCM("Receiving RTCM Data"),
    SINGLE("Single (No Correction)"),
    FLOAT("RTK Float (Decimeter)"),
    FIX("RTK Fix (Centimeter)") // The goal
}

data class RtkState(
    val uncorrected: Coordinate = Coordinate(),
    val corrected: Coordinate = Coordinate(),
    val status: RtkStatus = RtkStatus.DISCONNECTED,
    val ntripLog: String = "",
    val rtcmMessageCount: Int = 0
)

data class NtripConfig(
    val host: String = "",
    val port: Int = 2101,
    val mountPoint: String = "",
    val user: String = "",
    val password: String = ""
)

data class RawDataState(
    val isReceivingData: Boolean = false,
    val totalEventsReceived: Int = 0,
    val satelliteCount: Int = 0,
    val lastUpdateTime: String = "Never",
    val clockTimeNanos: Long = 0,
    val gpsCount: Int = 0,
    val glonassCount: Int = 0,
    val galileoCount: Int = 0,
    val beidouCount: Int = 0,
    val otherCount: Int = 0,
    val lastMeasurementDetails: String = ""
)

data class NtripStatusState(
    val bytesReceived: Long = 0,
    val connectionDuration: String = "00:00:00",
    val dataRate: Double = 0.0,
    val rtcmMessageTypes: Map<Int, Int> = emptyMap(),
    val lastRtcmData: String = ""
)
