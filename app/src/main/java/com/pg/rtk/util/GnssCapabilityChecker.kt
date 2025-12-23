package com.pg.rtk.util

import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.util.Log
import java.util.concurrent.Executor

/**
 * Utility to check if device supports raw GNSS measurements required for RTK positioning.
 */
object GnssCapabilityChecker {
    private const val TAG = "GnssCapabilityChecker"

    /**
     * Check if device supports raw GNSS measurements with fullBiasNanos.
     *
     * @param locationManager The system LocationManager
     * @return DeviceCapability describing the device's GNSS capabilities
     */
    fun checkDeviceCapability(locationManager: LocationManager): DeviceCapability {
        try {
            @Suppress("MissingPermission")
            // Try to register callback - if this succeeds, device supports raw GNSS
            val testCallback = object : GnssMeasurementsEvent.Callback() {
                override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                    // Callback received - device supports raw GNSS
                }
            }

            // The return value tells us if registration succeeded
            val registered = locationManager.registerGnssMeasurementsCallback(
                Executor { it.run() },
                testCallback
            )

            // Unregister immediately
            try {
                locationManager.unregisterGnssMeasurementsCallback(testCallback)
            } catch (e: Exception) {
                // Ignore unregister errors
            }

            return if (registered) {
                // Registration succeeded - device supports raw GNSS
                // We can't check fullBiasNanos without actual measurements,
                // so we'll check that during actual use
                Log.i(TAG, "✓ Device supports raw GNSS measurements (callback registered successfully)")
                DeviceCapability.FullRtkSupport
            } else {
                Log.w(TAG, "✗ Device does not support raw GNSS (callback registration failed)")
                DeviceCapability.NoRawGnss(
                    "Device does not support raw GNSS measurements (callback registration returned false)"
                )
            }

        } catch (e: SecurityException) {
            val message = "Location permission required to check GNSS capability"
            Log.e(TAG, message, e)
            return DeviceCapability.PermissionRequired(message)
        } catch (e: Exception) {
            val message = "Error checking GNSS capability: ${e.message}"
            Log.e(TAG, message, e)
            return DeviceCapability.Unknown(message)
        }
    }

    /**
     * Get recommended devices for RTK positioning
     */
    fun getRecommendedDevices(): List<String> = listOf(
        "Google Pixel 4/5/6/7/8 (all variants)",
        "Samsung Galaxy S20/S21/S22/S23/S24 (especially Ultra)",
        "Xiaomi Mi 8/9/10/11/12 (flagship models)",
        "OnePlus 8/9/10/11 series"
    )

    /**
     * Get alternative solutions if device doesn't support RTK
     */
    fun getAlternativeSolutions(): List<String> = listOf(
        "Use external Bluetooth GNSS receiver (e.g., u-blox ZED-F9P)",
        "Use standard GPS with SBAS corrections (WAAS/EGNOS)",
        "Upgrade to a compatible device"
    )
}

/**
 * Represents the GNSS capability of the device
 */
sealed class DeviceCapability {
    /**
     * Device fully supports RTK positioning with raw GNSS measurements
     */
    object FullRtkSupport : DeviceCapability() {
        override fun toString() = "Full RTK support available"
    }

    /**
     * Device has raw GNSS but missing critical data (fullBiasNanos)
     */
    data class IncompleteRawGnss(val reason: String) : DeviceCapability() {
        override fun toString() = reason
    }

    /**
     * Device does not support raw GNSS measurements at all
     */
    data class NoRawGnss(val reason: String) : DeviceCapability() {
        override fun toString() = reason
    }

    /**
     * Location permission not granted
     */
    data class PermissionRequired(val reason: String) : DeviceCapability() {
        override fun toString() = reason
    }

    /**
     * Unable to determine capability
     */
    data class Unknown(val reason: String) : DeviceCapability() {
        override fun toString() = reason
    }

    /**
     * Check if device can perform RTK positioning
     */
    fun canDoRtk(): Boolean = when (this) {
        is FullRtkSupport -> true
        else -> false
    }
}

