package com.pg.rtk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.pg.rtk.ui.RtkScreen
import com.pg.rtk.ui.RtkViewModel

class MainActivity : ComponentActivity() {
    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val rtkViewModel by viewModels<RtkViewModel> {
        object : ViewModelProvider.Factory {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return RtkViewModel(locationManager) as T
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Start GNSS listening now that permission is granted
                rtkViewModel.startGnssListening(true)
            }
        }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if permission is already granted and start listening
        val hasPermission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            rtkViewModel.startGnssListening(true)
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        setContent {
            RtkScreen(viewModel = rtkViewModel)
        }
    }
}