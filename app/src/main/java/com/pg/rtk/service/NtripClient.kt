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

        /**
         * Size of the buffer for reading NTRIP stream data.
         *
         * Set to 4096 bytes (4 KB) to handle:
         * - RTCM3 messages: typically 100-600 bytes, max ~1023 bytes per message
         * - HTTP chunked encoding overhead: chunk size line + \r\n markers
         * - Multiple RTCM messages per read: buffer should accommodate several messages
         * - Network efficiency: larger buffer reduces system calls
         *
         * Increased from 2048 to 4096 to better handle chunked encoding where:
         * - Each chunk has overhead (size line in hex + 2x\r\n = ~10 bytes)
         * - Multiple chunks may arrive in single TCP packet
         * - Larger buffer reduces parsing complexity at chunk boundaries
         */
        private const val READ_BUFFER_SIZE = 4096

        /**
         * Log RTCM extraction progress every N bytes to provide ongoing visibility.
         * Set to 10KB intervals to balance between information and log spam.
         */
        private const val LOG_INTERVAL_BYTES = 10240L  // 10 KB

        /**
         * Maximum number of consecutive chunk size parsing failures before treating as raw data.
         * Helps detect genuine protocol switches vs. corrupted data.
         */
        private const val MAX_CHUNK_PARSE_FAILURES = 3
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
            val buffer = ByteArray(READ_BUFFER_SIZE)
            var totalBytesRead = 0L
            var rtcmBytesExtracted = 0L
            var lastLoggedBytes = 0L
            val chunkBuffer = java.io.ByteArrayOutputStream()
            var readingChunkSize = true
            var remainingChunkSize = 0
            var consecutiveChunkParseFailures = 0
            var usingRawMode = false  // Track if we've switched to raw data mode

            while (isActive && !isDisconnecting) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) {
                    onLog("Connection closed by server")
                    break
                }

                if (bytesRead > 0) {
                    totalBytesRead += bytesRead

                    // If we've switched to raw mode due to repeated failures, just pass through data
                    if (usingRawMode) {
                        onDataReceived(buffer.copyOf(bytesRead))
                        rtcmBytesExtracted += bytesRead
                    } else {
                        // Process chunked transfer encoding
                        var offset = 0
                        while (offset < bytesRead && !usingRawMode) {
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
                                    consecutiveChunkParseFailures = 0  // Reset on success
                                } catch (e: NumberFormatException) {
                                    consecutiveChunkParseFailures++

                                    // Calculate the start position where this "chunk size line" began
                                    // lineEnd points to \r, offset now points past \r\n
                                    // The chunk size line started at: offset - 2 (for \r\n) - chunkSizeLine.length
                                    val lineStartPos = offset - 2 - chunkSizeLine.length

                                    // Check if this looks like raw RTCM data (starts with 0xD3 sync byte)
                                    // Only check if we have valid buffer position
                                    val looksLikeRtcm = if (lineStartPos >= 0 && lineStartPos < bytesRead) {
                                        buffer[lineStartPos].toInt() and 0xFF == 0xD3
                                    } else {
                                        false
                                    }

                                    if (consecutiveChunkParseFailures >= MAX_CHUNK_PARSE_FAILURES) {
                                        // Multiple failures - switch to raw mode permanently for this connection
                                        onLog("WARNING: Chunked encoding parse failed $consecutiveChunkParseFailures times - switching to raw RTCM mode")
                                        usingRawMode = true
                                        // Process remaining data in buffer as raw, starting from valid position
                                        val startPos = maxOf(0, lineStartPos)
                                        val remainingData = buffer.copyOfRange(startPos, bytesRead)
                                        onDataReceived(remainingData)
                                        rtcmBytesExtracted += remainingData.size
                                        break
                                    } else if (looksLikeRtcm) {
                                        // Looks like RTCM data - temporarily treat this buffer as raw
                                        onLog("WARNING: Chunk size parse failed (attempt $consecutiveChunkParseFailures/$MAX_CHUNK_PARSE_FAILURES), but data appears to be RTCM (sync byte 0xD3)")
                                        val startPos = maxOf(0, lineStartPos)
                                        val remainingData = buffer.copyOfRange(startPos, bytesRead)
                                        onDataReceived(remainingData)
                                        rtcmBytesExtracted += remainingData.size
                                        break
                                    } else {
                                        // Doesn't look like RTCM - log error and skip this data
                                        onLog("ERROR: Invalid chunk size '$chunkSizeLine' and no RTCM sync byte detected - possible data corruption (attempt $consecutiveChunkParseFailures/$MAX_CHUNK_PARSE_FAILURES)")
                                        break
                                    }
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
                    }

                    // Periodic logging for ongoing visibility (every LOG_INTERVAL_BYTES)
                    if (rtcmBytesExtracted - lastLoggedBytes >= LOG_INTERVAL_BYTES) {
                        val mode = if (usingRawMode) "raw" else "chunked"
                        onLog("RTCM data extracted: $rtcmBytesExtracted bytes (from $totalBytesRead total, mode: $mode)")
                        lastLoggedBytes = rtcmBytesExtracted
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