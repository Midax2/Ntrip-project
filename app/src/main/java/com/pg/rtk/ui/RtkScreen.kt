package com.pg.rtk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pg.rtk.data.NtripConfig
import com.pg.rtk.data.RtkState
import com.pg.rtk.data.RtkStatus
import java.util.Locale

/**
 * Minimum time in milliseconds between button clicks to prevent accidental double-clicks.
 * This debounce delay helps avoid multiple rapid connection/disconnection attempts.
 */
private const val BUTTON_DEBOUNCE_MILLIS = 1000L

@Composable
fun RtkScreen(viewModel: RtkViewModel) {
    val state by viewModel.rtkState.collectAsState()
    val config by viewModel.ntripConfig.collectAsState()
    // Only show DISCONNECT button when actually connecting or connected to NTRIP
    // Don't show it for SINGLE, FIX, or FLOAT (these are positioning states, not connection states)
    val isConnected = state.status == RtkStatus.CONNECTING_NTRIP ||
                      state.status == RtkStatus.RECEIVING_RTCM

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {

        Text("🚀 High-Precision GNSS Receiver", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        NtripConfigSection(config, isConnected, viewModel::updateConfig, viewModel::connectNtrip, viewModel::disconnectNtrip)

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        PositionDisplaySection(state)

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        NtripLogDisplay(state.ntripLog)

        Spacer(Modifier.height(16.dp))
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
    var lastClickTime by remember { mutableLongStateOf(0L) }

    // Update portInput when config.port changes externally
    LaunchedEffect(config.port) {
        if (portInput != config.port.toString()) {
            portInput = config.port.toString()
            portError = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NTRIP Connection", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                // Connection status indicator
                if (isConnected) {
                    Text(
                        "●",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

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

            // Username field
            OutlinedTextField(
                value = config.user,
                onValueChange = { onConfigUpdate(config.copy(user = it)) },
                label = { Text("Username") },
                enabled = !isConnected,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // Password field with visibility toggle
            var passwordVisible by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = config.password,
                onValueChange = { onConfigUpdate(config.copy(password = it)) },
                label = { Text("Password") },
                enabled = !isConnected,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            // Show password checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = passwordVisible,
                    onCheckedChange = { passwordVisible = it },
                    enabled = !isConnected
                )
                Text(
                    text = "Show password",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            val isValidConfig = config.host.isNotBlank() && config.host.length >= 3 &&
                               config.mountPoint.isNotBlank() && config.mountPoint.length >= 2 &&
                               config.user.isNotBlank() &&
                               config.password.isNotBlank() &&
                               !portError

            Button(
                onClick = {
                    android.util.Log.d("NtripButton", "Button clicked! isConnected=$isConnected, isValidConfig=$isValidConfig")

                    // Debounce: prevent rapid-fire clicks
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastClickTime < BUTTON_DEBOUNCE_MILLIS) {
                        android.util.Log.d("NtripButton", "Click ignored - too soon after last click")
                        return@Button
                    }
                    lastClickTime = currentTime

                    // Explicit check inside onClick to prevent accidental triggers
                    if (isConnected) {
                        android.util.Log.d("NtripButton", "Calling onDisconnect")
                        onDisconnect()
                    } else if (isValidConfig) {
                        android.util.Log.d("NtripButton", "Calling onConnect")
                        onConnect()
                    } else {
                        android.util.Log.d("NtripButton", "Click ignored - config not valid")
                    }
                },
                enabled = if (isConnected) {
                    true // Always allow disconnect
                } else {
                    isValidConfig
                },
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
        Text("🌎 Positioning Results", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        // Status Indicator with larger, more visible design
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when(state.status) {
                    RtkStatus.FIX -> MaterialTheme.colorScheme.primaryContainer
                    RtkStatus.FLOAT, RtkStatus.RECEIVING_RTCM -> MaterialTheme.colorScheme.secondaryContainer
                    RtkStatus.CONNECTING_NTRIP -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Status",
                    tint = when(state.status) {
                        RtkStatus.FIX -> MaterialTheme.colorScheme.primary
                        RtkStatus.FLOAT, RtkStatus.RECEIVING_RTCM -> MaterialTheme.colorScheme.secondary
                        RtkStatus.CONNECTING_NTRIP -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "Status: ${state.status.label}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (state.rtcmMessageCount > 0) {
                        Text(
                            "${state.rtcmMessageCount} RTCM messages received",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Uncorrected Position Card
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("🔴 Uncorrected (Standard GNSS)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("Lat: ${String.format(Locale.US, "%.6f", state.uncorrected.latitude)}°", style = MaterialTheme.typography.bodyMedium)
                Text("Lon: ${String.format(Locale.US, "%.6f", state.uncorrected.longitude)}°", style = MaterialTheme.typography.bodyMedium)
                Text("Height: ${String.format(Locale.US, "%.2f", state.uncorrected.height)} m", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Corrected Position Card
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("🟢 Corrected (RTK Engine Output)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("Lat: ${String.format(Locale.US, "%.9f", state.corrected.latitude)}°", style = MaterialTheme.typography.bodyMedium)
                Text("Lon: ${String.format(Locale.US, "%.9f", state.corrected.longitude)}°", style = MaterialTheme.typography.bodyMedium)
                Text("Height: ${String.format(Locale.US, "%.3f", state.corrected.height)} m", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun NtripLogDisplay(log: String) {
    val context = LocalContext.current

    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📡 NTRIP Connection Log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // Copy button
            Button(
                onClick = {
                    if (log.isNotEmpty()) {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("NTRIP Log", log)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "Logs copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = log.isNotEmpty(),
                modifier = Modifier.height(36.dp)
            ) {
                Text("📋 COPY", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(4.dp))
        Card(
            Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (log.isEmpty()) {
                    Text(
                        "Ready to connect. Fill in NTRIP credentials and click CONNECT.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        log,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }
    }
}