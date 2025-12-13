package com.pg.rtk.ui

import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pg.rtk.data.NtripConfig
import com.pg.rtk.data.NtripStatusState
import com.pg.rtk.data.RawDataState
import com.pg.rtk.data.RtkState
import com.pg.rtk.data.RtkStatus
import com.pg.rtk.service.GnssLocationListener
import com.pg.rtk.service.NtripClient
import com.pg.rtk.service.RtkEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RtkViewModel(
    private val locationManager: LocationManager
) : ViewModel() {

    companion object {
        private const val TAG = "RtkViewModel"
    }

    private val _rtkState = MutableStateFlow(RtkState())
    val rtkState: StateFlow<RtkState> = _rtkState

    private val _ntripConfig = MutableStateFlow(NtripConfig())
    val ntripConfig: StateFlow<NtripConfig> = _ntripConfig

    private val _rawDataState = MutableStateFlow(RawDataState())
    val rawDataState: StateFlow<RawDataState> = _rawDataState

    private val _ntripStatusState = MutableStateFlow(NtripStatusState())
    val ntripStatusState: StateFlow<NtripStatusState> = _ntripStatusState

    // Mutex to protect NTRIP connection/disconnection operations
    private val ntripMutex = Mutex()

    // Note: @Volatile removed - access now protected by mutex
    private var ntripClient: NtripClient? = null
    private var ntripJob: Job? = null
    private var isGnssListening = false

    // Tracking variables for monitoring
    private var ntripConnectionStartTime: Long = 0
    private var ntripBytesReceived: Long = 0
    private var rtcmMessageTypeCount = mutableMapOf<Int, Int>()

    // Initialize the GNSS Engine
    private val rtkEngine = RtkEngine { newState ->
        _rtkState.update { currentState ->
            // Smart status update: distinguish between NTRIP connection states and positioning quality
            val finalStatus = when {
                // Always upgrade to FIX or FLOAT if available (positioning quality)
                newState.status == RtkStatus.FIX || newState.status == RtkStatus.FLOAT -> newState.status
                // Keep RECEIVING_RTCM if we have RTCM messages (NTRIP connected)
                currentState.status == RtkStatus.RECEIVING_RTCM && newState.rtcmMessageCount > 0 -> RtkStatus.RECEIVING_RTCM
                // Allow transition FROM CONNECTING_NTRIP if we got RTCM data
                currentState.status == RtkStatus.CONNECTING_NTRIP && newState.rtcmMessageCount > 0 -> RtkStatus.RECEIVING_RTCM
                // Keep CONNECTING_NTRIP while connecting (don't change to SINGLE)
                currentState.status == RtkStatus.CONNECTING_NTRIP && newState.status == RtkStatus.SINGLE -> RtkStatus.CONNECTING_NTRIP
                // Keep DISCONNECTED if we're not connected and not connecting (don't auto-change to SINGLE)
                currentState.status == RtkStatus.DISCONNECTED && newState.status == RtkStatus.SINGLE -> RtkStatus.DISCONNECTED
                // Otherwise use the new status
                else -> newState.status
            }

            currentState.copy(
                uncorrected = newState.uncorrected,
                // Only update corrected position if it's actually valid
                // Check if coordinates are meaningful (not 0,0,0 and not very small values near origin)
                corrected = if (kotlin.math.abs(newState.corrected.latitude) > 0.1 ||
                               kotlin.math.abs(newState.corrected.longitude) > 0.1) {
                    newState.corrected
                } else {
                    // Keep existing corrected position if new one is invalid
                    currentState.corrected
                },
                status = finalStatus,
                rtcmMessageCount = newState.rtcmMessageCount
            )
        }
    }

    // Initialize the Location Listener
    private val gnssListener = GnssLocationListener(locationManager) { event ->
        updateRawDataState(event)
        rtkEngine.processRawGnssData(event)
    }

    // Android Location listener for fallback position (while C++ model is being improved)
    private val locationListener = android.location.LocationListener { location ->
        _rtkState.update { current ->
            current.copy(
                uncorrected = com.pg.rtk.data.Coordinate(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    height = if (location.hasAltitude()) location.altitude else 0.0
                )
            )
        }
    }


    /**
     * Start GNSS location listening.
     * Should be called only after location permissions are verified.
     * Safe to call multiple times - will only register once.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun startGnssListening(permissionGranted: Boolean) {
        if (permissionGranted) {
            val success = gnssListener.start(permissionGranted)
            isGnssListening = success

            // Also start Android location updates for position display
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L, 0f, locationListener
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start location updates", e)
            }
        }
    }

    /**
     * Stop GNSS location listening.
     */
    fun stopGnssListening() {
        if (isGnssListening) {
            gnssListener.stop()
            isGnssListening = false

            try {
                locationManager.removeUpdates(locationListener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop location updates", e)
            }
        }
    }

    /**
     * Update the NTRIP configuration.
     *
     * This method updates the NTRIP caster settings (host, port, mount point, credentials).
     * Changes take effect on the next connection attempt via [connectNtrip].
     *
     * Note: This method has no effect on an active connection. To apply new configuration,
     * call [disconnectNtrip] first, then update config, then call [connectNtrip].
     *
     * @param config The new NTRIP configuration to use
     */
    fun updateConfig(config: NtripConfig) {
        Log.d(TAG, "updateConfig called: host=${config.host}, port=${config.port}, mount=${config.mountPoint}, user=${config.user}")
        _ntripConfig.value = config
    }

    /**
     * Connect to the NTRIP caster to receive RTK correction data.
     *
     * This method establishes a connection to the NTRIP caster specified in the current
     * configuration and starts streaming RTCM correction data to the RTK engine.
     *
     * **Threading:** This method is safe to call from any thread. The actual network
     * connection is established on the IO dispatcher (background thread). The entire
     * connect operation is protected by a mutex to prevent race conditions with
     * concurrent connect/disconnect calls.
     *
     * **Behavior:**
     * - Cancels any existing connection attempt before starting a new one
     * - Resets the RTK engine state (clears previous positioning data)
     * - Updates status to [RtkStatus.CONNECTING_NTRIP] during connection
     * - Updates status to [RtkStatus.RECEIVING_RTCM] when successfully connected
     * - Streams RTCM data to [rtkEngine] for processing
     *
     * **Error Handling:** Connection errors are logged to [RtkState.ntripLog].
     * Check the log field for connection failure details.
     *
     * **Safe to call multiple times:** Calling this method while a connection is active
     * will cancel the existing connection and start a new one. Concurrent calls are
     * serialized by a mutex.
     *
     * @see disconnectNtrip
     * @see updateConfig
     */
    fun connectNtrip() {
        // Log stack trace to see where this is being called from
        Log.w(TAG, "connectNtrip() called", Exception("Stack trace for debugging"))

        viewModelScope.launch {
            ntripMutex.withLock {
                // Validate configuration before attempting connection
                val config = _ntripConfig.value
                Log.d(TAG, "Validating config: host=${config.host}, port=${config.port}, mount=${config.mountPoint}, user=${config.user}")

                if (config.host.isBlank() || config.mountPoint.isBlank() ||
                    config.user.isBlank() || config.password.isBlank() ||
                    config.port <= 0 || config.port > 65535) {
                    Log.e(TAG, "Cannot connect: Invalid or incomplete NTRIP configuration")
                    _rtkState.update { it.copy(ntripLog = "Error: Please fill in all NTRIP fields") }
                    return@withLock
                }

                Log.i(TAG, "Starting NTRIP connection to ${config.host}:${config.port}/${config.mountPoint}")

                // Cancel any existing connection
                ntripJob?.cancel()
                ntripJob = null
                ntripClient?.disconnect()
                ntripClient = null

                // Update state to connecting
                _rtkState.update { it.copy(status = RtkStatus.CONNECTING_NTRIP, ntripLog = "") }
                rtkEngine.reset() // Reset engine state upon new connection attempt

                // Reset NTRIP monitoring
                ntripConnectionStartTime = 0
                ntripBytesReceived = 0
                rtcmMessageTypeCount.clear()
                _ntripStatusState.value = NtripStatusState()

                // Create new client
                val client = NtripClient(
                    config = _ntripConfig.value,
                    onDataReceived = { data ->
                        updateNtripStatus(data)
                        rtkEngine.processRtcmData(data)
                    },
                    onLog = { log ->
                        _rtkState.update { currentState ->
                            // Accumulate logs with newlines, keep last 500 chars to prevent memory issues
                            val newLog = if (currentState.ntripLog.isEmpty()) {
                                log
                            } else {
                                (currentState.ntripLog + "\n" + log).takeLast(500)
                            }
                            currentState.copy(ntripLog = newLog)
                        }

                        // Update status based on log messages
                        when {
                            log.contains("200 OK") && log.contains("Connection successful") -> {
                                ntripConnectionStartTime = System.currentTimeMillis()
                                _rtkState.update { it.copy(status = RtkStatus.RECEIVING_RTCM) }
                            }
                            log.startsWith("Connection Error:") || log.startsWith("Connection rejected") -> {
                                _rtkState.update { it.copy(status = RtkStatus.DISCONNECTED) }
                            }
                        }
                    }
                )
                ntripClient = client

                // Launch connection job
                ntripJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        client.connect()
                    } catch (e: Exception) {
                        Log.e(TAG, "NTRIP connection failed", e)
                        _rtkState.update { it.copy(
                            status = RtkStatus.DISCONNECTED,
                            ntripLog = it.ntripLog + "\nConnection failed: ${e.message}"
                        ) }
                    }
                }
            }
        }
    }

    /**
     * Disconnect from the NTRIP caster and stop receiving correction data.
     *
     * This method cleanly terminates the connection to the NTRIP caster and stops
     * all RTCM data streaming.
     *
     * **Threading:** This method is safe to call from any thread. The entire
     * disconnect operation is protected by a mutex to prevent race conditions with
     * concurrent connect/disconnect calls.
     *
     * **Behavior:**
     * - Cancels the background connection coroutine (if running)
     * - Closes the NTRIP client socket connection
     * - Updates status to [RtkStatus.DISCONNECTED]
     * - Updates log to indicate disconnection
     *
     * **Safe to call multiple times:** Calling this method when not connected
     * is safe and has no side effects (beyond updating the status). Concurrent
     * calls are serialized by a mutex.
     *
     * **Note:** This does NOT stop GNSS listening or reset the RTK engine.
     * It only disconnects from the NTRIP correction data source.
     *
     * @see connectNtrip
     * @see stopGnssListening
     */
    fun disconnectNtrip() {
        viewModelScope.launch {
            ntripMutex.withLock {
                // Cancel connection job
                ntripJob?.cancel()
                ntripJob = null

                // Disconnect client
                ntripClient?.disconnect()
                ntripClient = null

                // Update state
                _rtkState.update { it.copy(status = RtkStatus.DISCONNECTED, ntripLog = "NTRIP Disconnected.") }
            }
        }
    }

    /**
     * Update raw data state from GNSS measurements event
     */
    private fun updateRawDataState(event: GnssMeasurementsEvent) {
        val measurements = event.measurements
        val satelliteCount = measurements.size

        // Count satellites by constellation
        var gpsCount = 0
        var glonassCount = 0
        var galileoCount = 0
        var beidouCount = 0
        var otherCount = 0

        measurements.forEach { measurement ->
            when (measurement.constellationType) {
                GnssStatus.CONSTELLATION_GPS -> gpsCount++
                GnssStatus.CONSTELLATION_GLONASS -> glonassCount++
                GnssStatus.CONSTELLATION_GALILEO -> galileoCount++
                GnssStatus.CONSTELLATION_BEIDOU -> beidouCount++
                else -> otherCount++
            }
        }

        // Build sample measurement details
        val details = buildString {
            val sample = measurements.take(3)
            sample.forEachIndexed { index, m ->
                append("Sat ${index + 1}: Svid=${m.svid}, ")
                append("Constellation=${getConstellationName(m.constellationType)}, ")
                append("CN0=${String.format(Locale.US, "%.1f", m.cn0DbHz)} dB-Hz\n")
            }
            if (measurements.size > 3) {
                append("... and ${measurements.size - 3} more satellites")
            }
        }

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

        _rawDataState.update { current ->
            current.copy(
                isReceivingData = true,
                totalEventsReceived = current.totalEventsReceived + 1,
                satelliteCount = satelliteCount,
                lastUpdateTime = timeFormat.format(Date()),
                clockTimeNanos = event.clock.timeNanos,
                gpsCount = gpsCount,
                glonassCount = glonassCount,
                galileoCount = galileoCount,
                beidouCount = beidouCount,
                otherCount = otherCount,
                lastMeasurementDetails = details
            )
        }
    }

    /**
     * Get human-readable constellation name
     */
    private fun getConstellationName(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
        GnssStatus.CONSTELLATION_UNKNOWN -> "Unknown"
        else -> "Unknown"
    }

    /**
     * Update NTRIP status when data is received
     */
    private fun updateNtripStatus(data: ByteArray) {
        ntripBytesReceived += data.size

        // Try to extract RTCM message type (simplified - RTCM messages start with D3)
        if (data.size >= 3 && data[0].toInt() and 0xFF == 0xD3) {
            // Extract message type from RTCM3 header (bits 24-35 of the message)
            if (data.size >= 6) {
                val messageType = ((data[3].toInt() and 0xFF) shl 4) or ((data[4].toInt() and 0xF0) shr 4)
                rtcmMessageTypeCount[messageType] = (rtcmMessageTypeCount[messageType] ?: 0) + 1
            }
        }

        // Format duration
        val duration = if (ntripConnectionStartTime > 0) {
            val elapsedMillis = System.currentTimeMillis() - ntripConnectionStartTime
            val hours = TimeUnit.MILLISECONDS.toHours(elapsedMillis)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMillis) % 60
            val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMillis) % 60
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            "00:00:00"
        }

        // Calculate data rate
        val dataRate = if (ntripConnectionStartTime > 0) {
            val elapsedSeconds = (System.currentTimeMillis() - ntripConnectionStartTime) / 1000.0
            if (elapsedSeconds > 0) ntripBytesReceived / elapsedSeconds else 0.0
        } else {
            0.0
        }

        // Format last data as hex string (first 32 bytes)
        val hexData = data.take(32).joinToString(" ") { byte ->
            String.format("%02X", byte.toInt() and 0xFF)
        } + if (data.size > 32) " ..." else ""

        _ntripStatusState.update {
            it.copy(
                bytesReceived = ntripBytesReceived,
                connectionDuration = duration,
                dataRate = dataRate,
                rtcmMessageTypes = rtcmMessageTypeCount.toMap(),
                lastRtcmData = hexData
            )
        }
    }

    override fun onCleared() {
        Log.d(TAG, "ViewModel clearing - starting cleanup")

        // Wrap each cleanup operation to ensure all execute even if one fails
        try {
            disconnectNtrip()
            Log.d(TAG, "NTRIP disconnected successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting NTRIP during cleanup", e)
            // Don't propagate - continue with other cleanup
        }

        try {
            stopGnssListening()
            Log.d(TAG, "GNSS listener stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GNSS listener during cleanup", e)
            // Don't propagate - continue with other cleanup
        }

        try {
            rtkEngine.shutdown()
            Log.d(TAG, "RTK engine shutdown successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down RTK engine during cleanup", e)
            // Don't propagate
        }

        Log.d(TAG, "ViewModel cleanup completed")
        super.onCleared()
    }
}