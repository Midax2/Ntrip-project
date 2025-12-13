package com.pg.rtk.service

import android.os.Build
import androidx.annotation.RequiresApi
import com.pg.rtk.data.NtripConfig
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class NtripClient(
    private val config: NtripConfig,
    private val onDataReceived: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @RequiresApi(Build.VERSION_CODES.O)
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

            // 1. Send NTRIP Request
            // Encode credentials immediately before use to minimize exposure window
            val encodedAuth = Base64.getEncoder().encodeToString(
                "${config.user}:${config.password}".toByteArray()
            )

            val request = buildString {
                append("GET /${config.mountPoint} HTTP/1.1\r\n")
                append("Host: ${config.host}\r\n")
                append("User-Agent: NTRIP-Client-Android/1.0\r\n")
                append("Authorization: Basic $encodedAuth\r\n")
                append("Ntrip-Version: Ntrip/2.0\r\n")
                append("\r\n")
            }
            outputStream.write(request.toByteArray())

            // 2. Read HTTP Response Header
            val header = readHeader(inputStream)
            onLog("Received Header: $header")

            if (!header.startsWith("ICY 200 OK")) {
                throw Exception("NTRIP connection failed: $header")
            }

            // 3. Start reading RTCM stream (continuous loop)
            val buffer = ByteArray(2048)
            while (isActive) {
                // NOTE: This simple read is NOT a proper RTCM parser.
                // A real parser would look for the 0xD3 sync byte and message length.
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

    fun disconnect() {
        try {
            // Close resources in proper order: output stream, input stream, then socket
            output?.close()
        } catch (e: Exception) { /* Ignore */ }
        try {
            input?.close()
        } catch (e: Exception) { /* Ignore */ }
        try {
            socket?.close()
        } catch (e: Exception) { /* Ignore */ }

        output = null
        input = null
        socket = null
        onLog("NTRIP Disconnected.")
    }
}