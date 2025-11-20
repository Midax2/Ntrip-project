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
            input = socket?.getInputStream()
            output = socket?.getOutputStream()

            val authString = "${config.user}:${config.password}"
            val encodedAuth = Base64.getEncoder().encodeToString(authString.toByteArray())

            // 1. Send NTRIP Request
            val request = buildString {
                append("GET /${config.mountPoint} HTTP/1.1\r\n")
                append("Host: ${config.host}\r\n")
                append("User-Agent: NTRIP-Client-Android/1.0\r\n")
                append("Authorization: Basic $encodedAuth\r\n")
                append("Ntrip-Version: Ntrip/2.0\r\n")
                append("\r\n")
            }
            output?.write(request.toByteArray())

            // 2. Read HTTP Response Header
            val header = readHeader(input)
            onLog("Received Header: $header")

            if (!header.startsWith("ICY 200 OK")) {
                throw Exception("NTRIP connection failed: $header")
            }

            // 3. Start reading RTCM stream (continuous loop)
            val buffer = ByteArray(2048)
            while (isActive) {
                // NOTE: This simple read is NOT a proper RTCM parser.
                // A real parser would look for the 0xD3 sync byte and message length.
                val bytesRead = input?.read(buffer) ?: -1
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

    private fun readHeader(input: InputStream?): String {
        val header = StringBuilder()
        var lastChar = ' '
        var currentChar = ' '
        while (true) {
            val byte = input?.read() ?: -1
            if (byte == -1) break
            currentChar = byte.toChar()
            header.append(currentChar)
            if (lastChar == '\n' && currentChar == '\n') break // End of header: \n\r\n\r or \n\n
            if (header.endsWith("\r\n\r\n")) break
            lastChar = currentChar
        }
        return header.toString().trim()
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (e: Exception) { /* Ignore */ }
        socket = null
        onLog("NTRIP Disconnected.")
    }
}