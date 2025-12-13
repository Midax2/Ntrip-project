package com.pg.rtk.service

import android.annotation.SuppressLint
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import java.util.concurrent.Executor

class GnssLocationListener(
    private val locationManager: LocationManager,
    private val onMeasurements: (GnssMeasurementsEvent) -> Unit
) {
    private val gnssCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            onMeasurements(event)
        }
    }

    // Executor for GNSS callback - uses calling thread for immediate processing
    private val executor = Executor { it.run() }

    @Volatile
    private var isRegistered = false

    /**
     * Start GNSS measurements listener.
     * @param permissionGranted Whether location permission has been granted
     * @return true if listener was successfully registered, false otherwise
     */
    @SuppressLint("MissingPermission")
    fun start(permissionGranted: Boolean): Boolean {
        if (!permissionGranted) {
            return false
        }

        // Already registered, return true
        if (isRegistered) {
            return true
        }

        return try {
            // Registering the listener. Requires ACCESS_FINE_LOCATION permission.
            // Permission is validated by the caller before calling this method.
            // Using newer API with executor (API 30+) to avoid deprecation warning
            locationManager.registerGnssMeasurementsCallback(executor, gnssCallback)
            isRegistered = true
            true
        } catch (e: SecurityException) {
            // Handle missing permission gracefully
            isRegistered = false
            false
        }
    }

    fun stop() {
        if (isRegistered) {
            try {
                locationManager.unregisterGnssMeasurementsCallback(gnssCallback)
                isRegistered = false
            } catch (e: SecurityException) {
                // Handle case where permission was revoked or callback wasn't registered
                isRegistered = false
            } catch (e: IllegalArgumentException) {
                // Handle case where callback was already unregistered
                isRegistered = false
            }
        }
    }
}