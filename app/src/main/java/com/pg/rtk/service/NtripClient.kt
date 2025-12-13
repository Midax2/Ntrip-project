package com.pg.rtk.service

import com.pg.rtk.data.NtripConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.Base64
import java.util.concurrent.TimeoutException

class NtripClient(
    private val config: NtripConfig,
    private val onDataReceived: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit
) {
    companion object {
        // Timeout for reading HTTP response header (10 seconds)
        private const val HEADER_READ_TIMEOUT_MS = 10000L
        // Maximum header size to prevent memory exhaustion (16 KB)
        private const val MAX_HEADER_SIZE = 16384
    }

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile
    private var isDisconnecting = false

    suspend fun connect() = withContext(Dispatchers.IO) {
        // Reset disconnecting flag when starting new connection
        isDisconnecting = false

        try {
            onLog("Attempting connection to ${config.host}:${config.port}...")
            onLog("Mount point: ${config.mountPoint}")
            socket = Socket(config.host, config.port)
            onLog("Socket connected successfully")

            // Verify socket was created successfully
            val connectedSocket = socket ?: throw Exception("Failed to create socket connection")

            input = connectedSocket.getInputStream()
            output = connectedSocket.getOutputStream()

            // Verify streams were obtained successfully
            val outputStream = output ?: throw Exception("Failed to get output stream")
            val inputStream = input ?: throw Exception("Failed to get input stream")

            // 1. Send NTRIP Request with secure credential handling
            /**
             * CREDENTIAL SECURITY NOTE:
             *
             * We attempt to minimize credential exposure by:
             * - Clearing credentialsBytes immediately after encoding
             * - Clearing encodedAuth after building request
             * - Clearing request bytes after sending
             *
             * REMAINING LIMITATIONS:
             * - config.user and config.password (String) remain in memory (by design for reconnection)
             * - JVM string interning may create additional copies
             * - Kotlin/Java String is immutable and cannot be securely wiped
             *
             * BEST PRACTICE FOR PRODUCTION:
             * Consider using CharArray instead of String for passwords in NtripConfig,
             * which allows secure wiping: charArray.fill('\u0000')
             */

            // Build credentials string in minimal scope
            val credentialsBytes = "${config.user}:${config.password}".toByteArray()
            var encodedAuth = Base64.getEncoder().encodeToString(credentialsBytes)
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

            // Convert to bytes for sending
            val requestBytes = request.toByteArray()

            // Clear encodedAuth from memory (best effort - String is immutable)
            @Suppress("UNUSED_VALUE")
            encodedAuth = ""  // Dereference to help GC

            // Send request
            onLog("Sending NTRIP request...")
            outputStream.write(requestBytes)
            outputStream.flush()
            onLog("Request sent, waiting for response...")

            // Clear request bytes from memory
            requestBytes.fill(0)

            // 2. Read HTTP Response Header with timeout protection
            val header = try {
                withTimeout(HEADER_READ_TIMEOUT_MS) {
                    readHeader(inputStream)
                }
            } catch (_: TimeoutException) {
                throw Exception("Timeout reading NTRIP response header after ${HEADER_READ_TIMEOUT_MS}ms")
            }
            // Check for successful response - accept both ICY 200 OK and HTTP/1.1 200 OK
            val isSuccess = header.startsWith("ICY 200 OK") ||
                           header.startsWith("HTTP/1.1 200 OK") ||
                           header.startsWith("HTTP/1.0 200 OK")

            // Only log success status, not full header which may contain sensitive info
            if (isSuccess) {
                onLog("Received Header: 200 OK (Connection successful)")
            } else {
                onLog("Received Header: $header")
            }

            if (!isSuccess) {
                onLog("Connection rejected by server")
                throw Exception("NTRIP connection failed: $header")
            }

            onLog("Starting RTCM data stream...")
            // 3. Start reading RTCM stream with chunked transfer encoding support
            val buffer = ByteArray(4096)
            var totalBytesRead = 0L
            var rtcmBytesExtracted = 0L
            val chunkBuffer = java.io.ByteArrayOutputStream()
            var readingChunkSize = true
            var remainingChunkSize = 0

            while (isActive && !isDisconnecting) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) {
                    onLog("Connection closed by server")
                    break
                }

                if (bytesRead > 0) {
                    totalBytesRead += bytesRead

                    // Process chunked transfer encoding
                    var offset = 0
                    while (offset < bytesRead) {
                        if (readingChunkSize) {
                            // Read chunk size line (hex number followed by \r\n)
                            val lineEnd = findLineEnd(buffer, offset, bytesRead)
                            if (lineEnd == -1) {
                                // Need more data to complete chunk size line
                                break
                            }

                            val chunkSizeLine = String(buffer, offset, lineEnd - offset, Charsets.US_ASCII).trim()
                            offset = lineEnd + 2 // Skip \r\n

                            if (chunkSizeLine.isEmpty()) {
                                // Empty line, skip it
                                continue
                            }

                            try {
                                remainingChunkSize = chunkSizeLine.toInt(16)
                                if (remainingChunkSize == 0) {
                                    // Last chunk, connection ending
                                    onLog("NTRIP stream ended (chunk size 0)")
                                    return@withContext
                                }
                                readingChunkSize = false
                            } catch (e: NumberFormatException) {
                                // Not a valid chunk size, might be raw RTCM data
                                // Fall back to treating as raw data
                                onDataReceived(buffer.copyOfRange(offset - chunkSizeLine.length - 2, bytesRead))
                                rtcmBytesExtracted += (bytesRead - (offset - chunkSizeLine.length - 2))
                                break
                            }
                        } else {
                            // Read chunk data
                            val bytesToRead = minOf(remainingChunkSize, bytesRead - offset)
                            chunkBuffer.write(buffer, offset, bytesToRead)
                            offset += bytesToRead
                            remainingChunkSize -= bytesToRead

                            if (remainingChunkSize == 0) {
                                // Chunk complete, send RTCM data
                                val rtcmData = chunkBuffer.toByteArray()
                                if (rtcmData.isNotEmpty()) {
                                    onDataReceived(rtcmData)
                                    rtcmBytesExtracted += rtcmData.size
                                }
                                chunkBuffer.reset()
                                readingChunkSize = true

                                // Skip trailing \r\n after chunk
                                if (offset + 1 < bytesRead && buffer[offset] == '\r'.code.toByte() && buffer[offset + 1] == '\n'.code.toByte()) {
                                    offset += 2
                                }
                            }
                        }
                    }

                    // Log first data reception
                    if (rtcmBytesExtracted > 0 && rtcmBytesExtracted <= 1024) {
                        onLog("RTCM data extracted: $rtcmBytesExtracted bytes (from $totalBytesRead total)")
                    }
                }
            }

            if (isDisconnecting) {
                onLog("Read loop stopped - disconnecting")
            }
        } catch (_: java.net.UnknownHostException) {
            onLog("Connection Error: Unknown host '${config.host}'")
            disconnect()
        } catch (e: java.net.ConnectException) {
            onLog("Connection Error: Cannot connect to ${config.host}:${config.port} - ${e.message}")
            disconnect()
        } catch (_: java.net.SocketTimeoutException) {
            onLog("Connection Error: Timeout connecting to ${config.host}:${config.port}")
            disconnect()
        } catch (e: Exception) {
            onLog("Connection Error: ${e.javaClass.simpleName} - ${e.message}")
            disconnect()
        }
    }

    /**
     * Read HTTP response header from input stream.
     *
     * Protection mechanisms:
     * - Size limit: Prevents memory exhaustion from malicious/broken servers
     * - Timeout: Handled by caller using withTimeout
     * - Proper termination: Looks for \r\n\r\n sequence
     *
     * @param input Input stream to read from
     * @return Trimmed header string
     * @throws Exception if header exceeds MAX_HEADER_SIZE or stream ends prematurely
     */
    private fun readHeader(input: InputStream): String {
        val header = StringBuilder()
        while (true) {
            // Check size limit to prevent memory exhaustion
            if (header.length >= MAX_HEADER_SIZE) {
                throw Exception("Header exceeds maximum size of $MAX_HEADER_SIZE bytes")
            }

            val byte = input.read()
            if (byte == -1) {
                throw Exception("Connection closed while reading header")
            }

            header.append(byte.toChar())

            // HTTP headers are terminated by \r\n\r\n
            if (header.endsWith("\r\n\r\n")) break
        }
        return header.toString().trim()
    }

    /**
     * Find the position of \r\n in buffer
     * @return position of \r in \r\n sequence, or -1 if not found
     */
    private fun findLineEnd(buffer: ByteArray, start: Int, end: Int): Int {
        for (i in start until end - 1) {
            if (buffer[i] == '\r'.code.toByte() && buffer[i + 1] == '\n'.code.toByte()) {
                return i
            }
        }
        return -1
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
            } catch (_: Exception) { /* Ignore - best effort cleanup */ }

            try {
                input?.close()
            } catch (_: Exception) { /* Ignore - best effort cleanup */ }

            try {
                socket?.close()
            } catch (_: Exception) { /* Ignore - best effort cleanup */ }

            output = null
            input = null
            socket = null
        }

        onLog("NTRIP Disconnected.")
    }
}