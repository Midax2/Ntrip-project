package com.pg.rtk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun RawDataScreen(viewModel: RtkViewModel) {
    val state by viewModel.rtkState.collectAsState()
    val rawDataState by viewModel.rawDataState.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("📡 Raw GNSS Data Monitor", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (rawDataState.isReceivingData) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (rawDataState.isReceivingData) {
                        Icons.Default.CheckCircle
                    } else {
                        Icons.Default.Warning
                    },
                    contentDescription = "Status",
                    tint = if (rawDataState.isReceivingData) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (rawDataState.isReceivingData) {
                            "✓ Raw GNSS Data Active"
                        } else {
                            "⚠ No Raw GNSS Data"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (rawDataState.isReceivingData) {
                            "Receiving measurements from GNSS receiver"
                        } else {
                            "Waiting for GNSS signals..."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Measurements Summary
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("📊 Measurement Statistics", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))

                InfoRow("Total Events Received:", rawDataState.totalEventsReceived.toString())
                InfoRow("Satellite Count:", rawDataState.satelliteCount.toString())
                InfoRow("Last Update:", rawDataState.lastUpdateTime)
                InfoRow("Clock Time (ns):", String.format(Locale.US, "%,d", rawDataState.clockTimeNanos))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Raw Position Data
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("🌍 Raw Position (Uncorrected)", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))

                InfoRow("Latitude:", String.format(Locale.US, "%.6f°", state.uncorrected.latitude))
                InfoRow("Longitude:", String.format(Locale.US, "%.6f°", state.uncorrected.longitude))
                InfoRow("Height:", String.format(Locale.US, "%.2f m", state.uncorrected.height))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Constellation breakdown
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("🛰️ Satellite Constellations", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))

                InfoRow("GPS:", rawDataState.gpsCount.toString())
                InfoRow("GLONASS:", rawDataState.glonassCount.toString())
                InfoRow("Galileo:", rawDataState.galileoCount.toString())
                InfoRow("BeiDou:", rawDataState.beidouCount.toString())
                InfoRow("Other:", rawDataState.otherCount.toString())
            }
        }

        Spacer(Modifier.height(16.dp))

        // Last Measurement Details
        if (rawDataState.lastMeasurementDetails.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("🔬 Recent Measurements Sample", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = rawDataState.lastMeasurementDetails,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

