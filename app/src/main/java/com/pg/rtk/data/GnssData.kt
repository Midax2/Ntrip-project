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