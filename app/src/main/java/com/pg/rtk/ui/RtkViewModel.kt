package com.pg.rtk.ui

import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pg.rtk.data.NtripConfig
import com.pg.rtk.data.RtkState
import com.pg.rtk.data.RtkStatus
import com.pg.rtk.service.GnssLocationListener
import com.pg.rtk.service.NtripClient
import com.pg.rtk.service.RtkEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

@RequiresApi(Build.VERSION_CODES.O)
class RtkViewModel(
    locationManager: LocationManager
) : ViewModel() {

    private val _rtkState = MutableStateFlow(RtkState())
    val rtkState: StateFlow<RtkState> = _rtkState

    private val _ntripConfig = MutableStateFlow(NtripConfig())
    val ntripConfig: StateFlow<NtripConfig> = _ntripConfig

    private lateinit var ntripClient: NtripClient
    private var ntripJob: Job? = null

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
    @RequiresApi(Build.VERSION_CODES.O)
    internal val gnssListener = GnssLocationListener(locationManager) { event ->
        rtkEngine.processRawGnssData(event)
    }

    init {
        // Start listening for raw GNSS data immediately (assuming permissions are granted later)
        gnssListener.start(permissionGranted = true)
    }

    fun updateConfig(config: NtripConfig) {
        _ntripConfig.value = config
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun connectNtrip() {
        ntripJob?.cancel()
        _rtkState.update { it.copy(status = RtkStatus.CONNECTING_NTRIP, ntripLog = "") }
        rtkEngine.reset() // Reset engine state upon new connection attempt

        ntripClient = NtripClient(
            config = _ntripConfig.value,
            onDataReceived = rtkEngine::processRtcmData, // Feed RTCM data to the engine
            onLog = { log ->
                _rtkState.update { it.copy(ntripLog = log) }
                if (log.startsWith("Received Header: ICY 200 OK")) {
                    _rtkState.update { it.copy(status = RtkStatus.RECEIVING_RTCM) }
                }
            }
        )

        ntripJob = viewModelScope.launch(Dispatchers.IO) {
            ntripClient.connect()
        }
    }

    fun disconnectNtrip() {
        ntripJob?.cancel()
        ntripClient.disconnect()
        _rtkState.update { it.copy(status = RtkStatus.DISCONNECTED, ntripLog = "NTRIP Disconnected.") }
    }

    override fun onCleared() {
        disconnectNtrip()
        gnssListener.stop()
        rtkEngine.shutdown()
        super.onCleared()
    }
}