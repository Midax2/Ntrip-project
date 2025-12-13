package com.pg.rtk.service

import com.pg.rtk.data.NtripConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.Base64

class NtripClient(
    private val config: NtripConfig,
    private val onDataReceived: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile
    private var isDisconnecting = false

    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            onLog("Attempting connection to ${config.host}:${config.port}...")
            socket = Socket(config.host, config.port)

            // Verify socket was created successfully
            val connectedSocket = socket ?: throw Exception("Failed to create socket connection")

            input = connectedSocket.getInputStream()
            output = connectedSocket.getOutputStream()

            // Verify streams were obtained successfully
            val outputStream = output ?: throw Exception("Failed to get output stream")
            val inputStream = input ?: throw Exception("Failed to get input stream")

            // 1. Send NTRIP Request with secure credential handling
            // Build credentials string in minimal scope
            val credentialsBytes = "${config.user}:${config.password}".toByteArray()
            val encodedAuth = Base64.getEncoder().encodeToString(credentialsBytes)
            // Clear credentials from memory immediately after encoding
            credentialsBytes.fill(0)

            val request = buildString {
                append("GET /${config.mountPoint} HTTP/1.1\r\n")
                append("Host: ${config.host}\r\n")
                append("User-Agent: NTRIP-Client-Android/1.0\r\n")
                append("Authorization: Basic $encodedAuth\r\n")
                append("Ntrip-Version: Ntrip/2.0\r\n")
                append("\r\n")
            }
            outputStream.write(request.toByteArray())
            outputStream.flush()

            // 2. Read HTTP Response Header
            val header = readHeader(inputStream)
            // Only log success status, not full header which may contain sensitive info
            if (header.startsWith("ICY 200 OK")) {
                onLog("Received Header: ICY 200 OK")
            } else {
                onLog("Received Header: $header")
            }

            if (!header.startsWith("ICY 200 OK")) {
                throw Exception("NTRIP connection failed: $header")
            }

            // 3. Start reading RTCM stream (continuous loop)
            /**
             * RTCM PARSING IMPLEMENTATION NOTE:
             *
             * This is a simplified implementation that reads raw bytes and passes them
             * directly to the RTK engine. It does NOT implement proper RTCM3 frame parsing.
             *
             * CURRENT BEHAVIOR:
             * - Reads data in 2KB chunks from the NTRIP stream
             * - Passes all received bytes directly to the RTK engine
             * - Relies on the native RTKLIB engine to handle RTCM parsing
             *
             * LIMITATIONS:
             * - No frame synchronization (0xD3 sync byte detection)
             * - No message length validation
             * - No CRC checking at this layer
             * - Buffer boundaries may split RTCM frames
             * - No RTCM message type detection or filtering
             *
             * WHY THIS WORKS:
             * RTKLIB's native processRtcmData() function handles the actual RTCM parsing,
             * frame synchronization, and CRC validation. The byte stream can be fed
             * directly as long as the native layer maintains proper state.
             *
             * FUTURE IMPROVEMENTS:
             * If implementing Java/Kotlin-side RTCM parsing:
             * 1. Detect 0xD3 sync byte (RTCM3 frame start)
             * 2. Parse 10-bit message length from header
             * 3. Validate CRC-24Q checksum
             * 4. Extract and forward complete frames only
             * 5. Add RTCM message statistics (message types, rates)
             *
             * For now, this implementation is sufficient as RTKLIB handles the complexity.
             */
            val buffer = ByteArray(2048)
            while (isActive && !isDisconnecting) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    onDataReceived(buffer.copyOf(bytesRead))
                }
                if (bytesRead == -1) break // Connection closed
            }
        } catch (e: Exception) {
            onLog("Connection Error: ${e.message}")
            disconnect()
        }
    }

    private fun readHeader(input: InputStream): String {
        val header = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) break
            header.append(byte.toChar())
            // HTTP headers are terminated by \r\n\r\n
            if (header.endsWith("\r\n\r\n")) break
        }
        return header.toString().trim()
    }

    /**
     * Disconnect from NTRIP caster and clean up resources.
     *
     * Thread-safe: Can be called from multiple threads simultaneously.
     * Idempotent: Safe to call multiple times.
     */
    fun disconnect() {
        // Set flag to stop read loop (volatile ensures visibility)
        if (isDisconnecting) {
            return // Already disconnecting, avoid duplicate cleanup
        }
        isDisconnecting = true

        // Synchronize resource cleanup to prevent race conditions
        synchronized(this) {
            try {
                // Close resources in proper order: output stream, input stream, then socket
                output?.close()
            } catch (e: Exception) { /* Ignore - best effort cleanup */ }

            try {
                input?.close()
            } catch (e: Exception) { /* Ignore - best effort cleanup */ }

            try {
                socket?.close()
            } catch (e: Exception) { /* Ignore - best effort cleanup */ }

            output = null
            input = null
            socket = null
        }

        onLog("NTRIP Disconnected.")
    }
}