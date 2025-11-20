package com.pg.rtk.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pg.rtk.data.NtripConfig
import com.pg.rtk.data.RtkState
import com.pg.rtk.data.RtkStatus

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun RtkScreen(viewModel: RtkViewModel) {
    val state by viewModel.rtkState.collectAsState()
    val config by viewModel.ntripConfig.collectAsState()
    val isConnected = state.status != RtkStatus.DISCONNECTED && state.status != RtkStatus.CONNECTING_NTRIP

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        Text("🚀 High-Precision GNSS Receiver", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        NtripConfigSection(config, isConnected, viewModel::updateConfig, viewModel::connectNtrip, viewModel::disconnectNtrip)

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        PositionDisplaySection(state)

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        NtripLogDisplay(state.ntripLog)
    }
}

@Composable
fun NtripConfigSection(
    config: NtripConfig,
    isConnected: Boolean,
    onConfigUpdate: (NtripConfig) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("NTRIP Connection", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = config.host,
                onValueChange = { onConfigUpdate(config.copy(host = it)) },
                label = { Text("Host") },
                enabled = !isConnected,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = config.port.toString(),
                    onValueChange = { onConfigUpdate(config.copy(port = it.toIntOrNull() ?: 2101)) },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isConnected,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                OutlinedTextField(
                    value = config.mountPoint,
                    onValueChange = { onConfigUpdate(config.copy(mountPoint = it)) },
                    label = { Text("Mountpoint") },
                    enabled = !isConnected,
                    modifier = Modifier.weight(1f)
                )
            }

            // User/Password fields omitted for brevity, but follow the same pattern

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = if (isConnected) onDisconnect else onConnect,
                enabled = config.host.isNotBlank() && config.mountPoint.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isConnected) "DISCONNECT" else "CONNECT")
            }
        }
    }
}

@Composable
fun PositionDisplaySection(state: RtkState) {
    Column(Modifier.fillMaxWidth()) {
        Text("🌎 Positioning Results", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        // Status Indicator
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, contentDescription = "Status")
            Spacer(Modifier.width(8.dp))
            Text("Status: ${state.status.label} (${state.rtcmMessageCount} messages)",
                fontWeight = FontWeight.Bold,
                color = when(state.status) {
                    RtkStatus.FIX -> MaterialTheme.colorScheme.primary
                    RtkStatus.FLOAT, RtkStatus.RECEIVING_RTCM -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.error
                })
        }

        Spacer(Modifier.height(8.dp))

        // Uncorrected Position Card
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                Text("🔴 Uncorrected (Standard GNSS)", fontWeight = FontWeight.SemiBold)
                Text("Lat: ${String.format("%.6f", state.uncorrected.latitude)}")
                Text("Lon: ${String.format("%.6f", state.uncorrected.longitude)}")
                Text("Height: ${String.format("%.2f", state.uncorrected.height)} m")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Corrected Position Card
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text("🟢 Corrected (RTK Engine Output)", fontWeight = FontWeight.SemiBold)
                // Use higher precision for corrected coordinates
                Text("Lat: ${String.format("%.9f", state.corrected.latitude)}")
                Text("Lon: ${String.format("%.9f", state.corrected.longitude)}")
                Text("Height: ${String.format("%.3f", state.corrected.height)} m")
            }
        }
    }
}

@Composable
fun NtripLogDisplay(log: String) {
    Column {
        Text("NTRIP Log", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth().height(100.dp)) {
            // Display the latest log message
            Text(log, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}