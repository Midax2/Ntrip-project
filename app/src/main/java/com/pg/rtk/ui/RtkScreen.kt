package com.pg.rtk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pg.rtk.data.NtripConfig
import com.pg.rtk.data.RtkState
import com.pg.rtk.data.RtkStatus
import java.util.Locale

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
    var portInput by remember { mutableStateOf(config.port.toString()) }
    var portError by remember { mutableStateOf(false) }

    // Update portInput when config.port changes externally
    LaunchedEffect(config.port) {
        if (portInput != config.port.toString()) {
            portInput = config.port.toString()
            portError = false
        }
    }

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
                    value = portInput,
                    onValueChange = { newValue ->
                        portInput = newValue
                        val newPort = newValue.toIntOrNull()
                        if (newPort != null && newPort > 0 && newPort <= 65535) {
                            onConfigUpdate(config.copy(port = newPort))
                            portError = false
                        } else {
                            portError = newValue.isNotEmpty()
                        }
                    },
                    label = { Text("Port") },
                    supportingText = {
                        if (portError) {
                            Text(
                                "Must be 1-65535",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    isError = portError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isConnected,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                OutlinedTextField(
                    value = config.mountPoint,
                    onValueChange = { onConfigUpdate(config.copy(mountPoint = it)) },
                    label = { Text("Mount Point") },
                    enabled = !isConnected,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = config.user,
                    onValueChange = { onConfigUpdate(config.copy(user = it)) },
                    label = { Text("Username") },
                    enabled = !isConnected,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                OutlinedTextField(
                    value = config.password,
                    onValueChange = { onConfigUpdate(config.copy(password = it)) },
                    label = { Text("Password") },
                    enabled = !isConnected,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = if (isConnected) onDisconnect else onConnect,
                enabled = config.host.isNotBlank() && config.mountPoint.isNotBlank() && !portError,
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
                Text("Lat: ${String.format(Locale.US, "%.6f", state.uncorrected.latitude)}")
                Text("Lon: ${String.format(Locale.US, "%.6f", state.uncorrected.longitude)}")
                Text("Height: ${String.format(Locale.US, "%.2f", state.uncorrected.height)} m")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Corrected Position Card
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text("🟢 Corrected (RTK Engine Output)", fontWeight = FontWeight.SemiBold)
                // Use higher precision for corrected coordinates
                Text("Lat: ${String.format(Locale.US, "%.9f", state.corrected.latitude)}")
                Text("Lon: ${String.format(Locale.US, "%.9f", state.corrected.longitude)}")
                Text("Height: ${String.format(Locale.US, "%.3f", state.corrected.height)} m")
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