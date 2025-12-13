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
import androidx.compose.material.icons.filled.Close
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
import com.pg.rtk.data.RtkStatus
import java.util.Locale

@Composable
fun NtripStatusScreen(viewModel: RtkViewModel) {
    val state by viewModel.rtkState.collectAsState()
    val ntripState by viewModel.ntripStatusState.collectAsState()
    val config by viewModel.ntripConfig.collectAsState()

    val isConnected = state.status == RtkStatus.RECEIVING_RTCM ||
                     state.status == RtkStatus.FLOAT ||
                     state.status == RtkStatus.FIX
    val isConnecting = state.status == RtkStatus.CONNECTING_NTRIP

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("🌐 NTRIP Client Monitor", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        // Connection Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isConnected -> MaterialTheme.colorScheme.primaryContainer
                    isConnecting -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when {
                        isConnected -> Icons.Default.CheckCircle
                        isConnecting -> Icons.Default.Warning
                        else -> Icons.Default.Close
                    },
                    contentDescription = "Status",
                    tint = when {
                        isConnected -> MaterialTheme.colorScheme.primary
                        isConnecting -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = when {
                            isConnected -> "✓ NTRIP Connected"
                            isConnecting -> "⏳ Connecting..."
                            else -> "✗ Disconnected"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = state.status.label,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Connection Details
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("🔧 Connection Details", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))

                InfoRow("Host:", config.host.ifBlank { "Not set" })
                InfoRow("Port:", config.port.toString())
                InfoRow("Mount Point:", config.mountPoint.ifBlank { "Not set" })
                InfoRow("Username:", if (config.user.isNotBlank()) config.user else "Not set")
                InfoRow("Password:", if (config.password.isNotBlank()) "●●●●●●●●" else "Not set")
            }
        }

        Spacer(Modifier.height(16.dp))

        // RTCM Data Statistics
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("📊 RTCM Data Statistics", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))

                InfoRow("Messages Received:", state.rtcmMessageCount.toString())
                InfoRow("Bytes Received:", String.format(Locale.US, "%,d", ntripState.bytesReceived))
                InfoRow("Connection Time:", ntripState.connectionDuration)
                InfoRow("Data Rate:", String.format(Locale.US, "%.1f bytes/sec", ntripState.dataRate))
            }
        }

        Spacer(Modifier.height(16.dp))

        // RTCM Message Types
        if (ntripState.rtcmMessageTypes.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("📡 RTCM Message Types", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))

                    ntripState.rtcmMessageTypes.forEach { (type, count) ->
                        InfoRow("Type $type:", "$count messages")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // Connection Log
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("📝 Connection Log", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))

                Text(
                    text = state.ntripLog.ifBlank { "No log messages yet..." },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Latest RTCM Data Sample
        if (ntripState.lastRtcmData.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("🔬 Latest RTCM Data (Hex)", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = ntripState.lastRtcmData,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth()
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

