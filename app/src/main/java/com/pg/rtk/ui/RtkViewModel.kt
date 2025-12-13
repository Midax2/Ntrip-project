package com.pg.rtk.ui

import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
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

        /**
         * Minimum absolute coordinate value (in degrees) to consider a position valid.
         *
         * Rationale for 0.1 degrees:
         * - 0.1° ≈ 11 km at the equator
         * - Any legitimate position on Earth is farther than 0.1° from the origin (0°N, 0°E)
         * - The origin point is in the Atlantic Ocean (Gulf of Guinea), far from land
         * - Filters out uninitialized positions (0.0, 0.0) and near-zero garbage values
         * - Small enough to not exclude valid positions near the equator
         *
         * Valid positions will have |latitude| > 0.1 OR |longitude| > 0.1
         * Invalid positions near (0, 0) will have both |latitude| ≤ 0.1 AND |longitude| ≤ 0.1
         */
        private const val MIN_VALID_COORDINATE_DEGREES = 0.1

        /**
         * Maximum length of NTRIP connection log in characters.
         *
         * Purpose:
         * - Prevents unbounded memory growth from accumulated log messages
         * - 500 chars ≈ 5-10 log lines with typical message lengths
         * - Keeps recent messages visible while discarding old ones
         * - Balance between information retention and memory efficiency
         *
         * When log exceeds this limit, oldest messages are discarded (takeLast(500))
         */
        private const val MAX_NTRIP_LOG_LENGTH = 500
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

    // Tracking variables for monitoring (thread-safe using atomic operations)
    private val ntripConnectionStartTime = AtomicLong(0)
    private val ntripBytesReceived = AtomicLong(0)
    private val rtcmMessageTypeCount = ConcurrentHashMap<Int, AtomicInteger>()

    /**
     * Determines the appropriate RTK status based on current and new states.
     *
     * State Machine Rules:
     * 1. HIGH_ACCURACY states (FIX, FLOAT) always take precedence - they represent achieved positioning quality
     * 2. NTRIP_CONNECTION states (CONNECTING_NTRIP, RECEIVING_RTCM) are maintained while connection is active
     * 3. POSITIONING states (SINGLE) don't override connection states
     * 4. DISCONNECTED is the base state and doesn't auto-upgrade
     *
     * State Priority (highest to lowest):
     * - FIX (cm-level accuracy achieved)
     * - FLOAT (dm-level accuracy achieved)
     * - RECEIVING_RTCM (NTRIP connected, data flowing)
     * - CONNECTING_NTRIP (NTRIP connection in progress)
     * - SINGLE (GPS only, no corrections)
     * - DISCONNECTED (no NTRIP connection)
     *
     * @param currentStatus The current RTK status
     * @param newStatus The status from RTK engine (based on solution quality)
     * @param hasRtcmData Whether RTCM correction data is available (rtcmMessageCount > 0)
     * @return The resolved status to use
     */
    private fun resolveRtkStatus(
        currentStatus: RtkStatus,
        newStatus: RtkStatus,
        hasRtcmData: Boolean
    ): RtkStatus = when {
        // Rule 1: High-accuracy positioning states always take priority
        newStatus == RtkStatus.FIX || newStatus == RtkStatus.FLOAT -> newStatus

        // Rule 2: Maintain RECEIVING_RTCM while RTCM data is flowing
        currentStatus == RtkStatus.RECEIVING_RTCM && hasRtcmData -> RtkStatus.RECEIVING_RTCM

        // Rule 3: Transition from CONNECTING to RECEIVING when RTCM data arrives
        currentStatus == RtkStatus.CONNECTING_NTRIP && hasRtcmData -> RtkStatus.RECEIVING_RTCM

        // Rule 4: Maintain CONNECTING_NTRIP (don't downgrade to SINGLE while connecting)
        currentStatus == RtkStatus.CONNECTING_NTRIP && newStatus == RtkStatus.SINGLE -> RtkStatus.CONNECTING_NTRIP

        // Rule 5: Maintain DISCONNECTED (don't auto-upgrade to SINGLE from positioning engine)
        currentStatus == RtkStatus.DISCONNECTED && newStatus == RtkStatus.SINGLE -> RtkStatus.DISCONNECTED

        // Rule 6: Default - use the new status from positioning engine
        else -> newStatus
    }

    // Initialize the GNSS Engine
    private val rtkEngine = RtkEngine { newState ->
        _rtkState.update { currentState ->
            val finalStatus = resolveRtkStatus(
                currentStatus = currentState.status,
                newStatus = newState.status,
                hasRtcmData = newState.rtcmMessageCount > 0
            )

            currentState.copy(
                uncorrected = newState.uncorrected,
                // Only update corrected position if it's actually valid
                // Check if coordinates are meaningful (not 0,0,0 and not very small values near origin)
                corrected = if (kotlin.math.abs(newState.corrected.latitude) > MIN_VALID_COORDINATE_DEGREES ||
                               kotlin.math.abs(newState.corrected.longitude) > MIN_VALID_COORDINATE_DEGREES) {
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
        Log.d(TAG, "connectNtrip() called from thread: ${Thread.currentThread().name}")

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

                // Reset NTRIP monitoring (thread-safe)
                ntripConnectionStartTime.set(0)
                ntripBytesReceived.set(0)
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
                            // Accumulate logs with newlines, keep last MAX_NTRIP_LOG_LENGTH chars to prevent memory issues
                            val newLog = if (currentState.ntripLog.isEmpty()) {
                                log
                            } else {
                                (currentState.ntripLog + "\n" + log).takeLast(MAX_NTRIP_LOG_LENGTH)
                            }
                            currentState.copy(ntripLog = newLog)
                        }

                        // Update status based on log messages
                        when {
                            log.contains("200 OK") && log.contains("Connection successful") -> {
                                ntripConnectionStartTime.set(System.currentTimeMillis())
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
     * Update NTRIP status when data is received (thread-safe using atomic operations)
     */
    private fun updateNtripStatus(data: ByteArray) {
        ntripBytesReceived.addAndGet(data.size.toLong())

        // Try to extract RTCM message type (simplified - RTCM messages start with D3)
        if (data.size >= 3 && data[0].toInt() and 0xFF == 0xD3) {
            // Extract message type from RTCM3 header (bits 24-35 of the message)
            if (data.size >= 6) {
                val messageType = ((data[3].toInt() and 0xFF) shl 4) or ((data[4].toInt() and 0xF0) shr 4)
                rtcmMessageTypeCount.computeIfAbsent(messageType) { AtomicInteger(0) }.incrementAndGet()
            }
        }

        // Format duration
        val startTime = ntripConnectionStartTime.get()
        val duration = if (startTime > 0) {
            val elapsedMillis = System.currentTimeMillis() - startTime
            val hours = TimeUnit.MILLISECONDS.toHours(elapsedMillis)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMillis) % 60
            val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMillis) % 60
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            "00:00:00"
        }

        // Calculate data rate
        val dataRate = if (startTime > 0) {
            val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0
            val bytesReceived = ntripBytesReceived.get()
            if (elapsedSeconds > 0) bytesReceived / elapsedSeconds else 0.0
        } else {
            0.0
        }

        // Format last data as hex string (first 32 bytes)
        val hexData = data.take(32).joinToString(" ") { byte ->
            String.format("%02X", byte.toInt() and 0xFF)
        } + if (data.size > 32) " ..." else ""

        _ntripStatusState.update {
            it.copy(
                bytesReceived = ntripBytesReceived.get(),
                connectionDuration = duration,
                dataRate = dataRate,
                rtcmMessageTypes = rtcmMessageTypeCount.mapValues { entry -> entry.value.get() },
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