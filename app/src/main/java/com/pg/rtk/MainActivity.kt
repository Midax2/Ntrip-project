package com.example.rtk


import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.example.rtk.ui.RtkScreen


class MainActivity : ComponentActivity() {
    private val viewModel: RtkViewModel by viewModels()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            RtkScreen(viewModel)
        }
    }
}