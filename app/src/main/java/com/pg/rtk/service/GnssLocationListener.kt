package com.pg.rtk.service

import android.location.GnssMeasurementsEvent
import android.location.LocationManager

class GnssLocationListener(
    private val locationManager: LocationManager,
    private val onMeasurements: (GnssMeasurementsEvent) -> Unit
) {
    private val gnssCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            onMeasurements(event)
        }
    }

    fun start(permissionGranted: Boolean) {
        if (permissionGranted) {
            try {
                // Registering the listener. Requires ACCESS_FINE_LOCATION permission.
                locationManager.registerGnssMeasurementsCallback(gnssCallback)
            } catch (e: SecurityException) {
                // Handle missing permission gracefully
            }
        }
    }

    fun stop() {
        locationManager.unregisterGnssMeasurementsCallback(gnssCallback)
    }
}