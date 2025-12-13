package com.pg.rtk.ui

import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pg.rtk.data.NtripConfig
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

class RtkViewModel(
    locationManager: LocationManager
) : ViewModel() {

    companion object {
        private const val TAG = "RtkViewModel"
    }

    private val _rtkState = MutableStateFlow(RtkState())
    val rtkState: StateFlow<RtkState> = _rtkState

    private val _ntripConfig = MutableStateFlow(NtripConfig())
    val ntripConfig: StateFlow<NtripConfig> = _ntripConfig

    @Volatile
    private var ntripClient: NtripClient? = null
    @Volatile
    private var ntripJob: Job? = null
    private var isGnssListening = false

    // Initialize the GNSS Engine
    private val rtkEngine = RtkEngine { newState ->
        _rtkState.update { it.copy(
            uncorrected = newState.uncorrected,
            corrected = newState.corrected,
            status = newState.status,
            rtcmMessageCount = newState.rtcmMessageCount
        ) }
    }

    // Initialize the Location Listener
    private val gnssListener = GnssLocationListener(locationManager) { event ->
        rtkEngine.processRawGnssData(event)
    }

    /**
     * Start GNSS location listening.
     * Should be called only after location permissions are verified.
     * Safe to call multiple times - will only register once.
     */
    fun startGnssListening(permissionGranted: Boolean) {
        if (permissionGranted) {
            val success = gnssListener.start(permissionGranted)
            isGnssListening = success
        }
    }

    /**
     * Stop GNSS location listening.
     */
    fun stopGnssListening() {
        if (isGnssListening) {
            gnssListener.stop()
            isGnssListening = false
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
        _ntripConfig.value = config
    }

    /**
     * Connect to the NTRIP caster to receive RTK correction data.
     *
     * This method establishes a connection to the NTRIP caster specified in the current
     * configuration and starts streaming RTCM correction data to the RTK engine.
     *
     * **Threading:** This method is safe to call from any thread. The actual network
     * connection is established on the IO dispatcher (background thread).
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
     * will cancel the existing connection and start a new one.
     *
     * @see disconnectNtrip
     * @see updateConfig
     */
    fun connectNtrip() {
        ntripJob?.cancel()
        _rtkState.update { it.copy(status = RtkStatus.CONNECTING_NTRIP, ntripLog = "") }
        rtkEngine.reset() // Reset engine state upon new connection attempt

        val client = NtripClient(
            config = _ntripConfig.value,
            onDataReceived = rtkEngine::processRtcmData, // Feed RTCM data to the engine
            onLog = { log ->
                _rtkState.update { it.copy(ntripLog = log) }
                if (log.startsWith("Received Header: ICY 200 OK")) {
                    _rtkState.update { it.copy(status = RtkStatus.RECEIVING_RTCM) }
                }
            }
        )
        ntripClient = client

        ntripJob = viewModelScope.launch(Dispatchers.IO) {
            client.connect()
        }
    }

    /**
     * Disconnect from the NTRIP caster and stop receiving correction data.
     *
     * This method cleanly terminates the connection to the NTRIP caster and stops
     * all RTCM data streaming.
     *
     * **Threading:** This method is safe to call from any thread. The cancellation
     * is handled by the coroutine system and the socket is closed on whatever thread
     * it was running on.
     *
     * **Behavior:**
     * - Cancels the background connection coroutine (if running)
     * - Closes the NTRIP client socket connection
     * - Updates status to [RtkStatus.DISCONNECTED]
     * - Updates log to indicate disconnection
     *
     * **Safe to call multiple times:** Calling this method when not connected
     * is safe and has no side effects (beyond updating the status).
     *
     * **Note:** This does NOT stop GNSS listening or reset the RTK engine.
     * It only disconnects from the NTRIP correction data source.
     *
     * @see connectNtrip
     * @see stopGnssListening
     */
    fun disconnectNtrip() {
        ntripJob?.cancel()
        ntripClient?.disconnect()
        _rtkState.update { it.copy(status = RtkStatus.DISCONNECTED, ntripLog = "NTRIP Disconnected.") }
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