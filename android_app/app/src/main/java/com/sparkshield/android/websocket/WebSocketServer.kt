package com.sparkshield.android.websocket

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Lightweight, zero-dependency RFC 6455 WebSocket Server.
 *
 * Provides:
 * - RFC 6455 handshake with SHA-1/Base64 Sec-WebSocket-Accept calculation
 * - Multi-client concurrent connections
 * - Thread-safe text frame broadcasting (opcode 0x1)
 * - Ping keep-alive frame generation (opcode 0x9)
 * - Safe pruning of dead/closed client connections
 * - Clean shutdown without resource leaks
 */
class WebSocketServer(
    val port: Int = 8765,
    val host: String = "127.0.0.1"
) {
    private val tag = "SparkShieldWS"
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var listenerThread: Thread? = null

    // Thread-safe collection of active clients
    private val activeClients = CopyOnWriteArrayList<ClientConnection>()

    val clientCount: Int
        get() = activeClients.size

    /**
     * Starts the WebSocket server listening on the configured host and port.
     */
    @Synchronized
    fun start(): Boolean {
        if (isRunning.get()) return true

        return try {
            val bindAddr = java.net.InetAddress.getByName(host)
            val socket = ServerSocket(port, 50, bindAddr)
            socket.reuseAddress = true
            serverSocket = socket
            isRunning.set(true)

            listenerThread = thread(name = "SparkShield-WS-Listener", isDaemon = true) {
                acceptLoop(socket)
            }
            Log.i(tag, "WebSocket server started on $host:$port")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start WebSocket server on port $port: ${e.message}", e)
            isRunning.set(false)
            false
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (isRunning.get() && !socket.isClosed) {
            try {
                val clientSocket = socket.accept()
                clientSocket.tcpNoDelay = true
                clientSocket.soTimeout = 15000 // 15 second read timeout

                thread(name = "SparkShield-WS-Client-${clientSocket.port}", isDaemon = true) {
                    handleNewClient(clientSocket)
                }
            } catch (e: SocketException) {
                if (!isRunning.get()) break
                Log.w(tag, "ServerSocket accept exception: ${e.message}")
            } catch (e: Exception) {
                if (!isRunning.get()) break
                Log.e(tag, "Unexpected error in acceptLoop: ${e.message}")
            }
        }
    }

    private fun handleNewClient(clientSocket: Socket) {
        val client = ClientConnection(clientSocket)
        try {
            if (performHandshake(client)) {
                activeClients.add(client)
                Log.i(tag, "WebSocket client connected: ${clientSocket.remoteSocketAddress} (Total clients: ${activeClients.size})")
                client.readLoop()
            }
        } catch (e: Exception) {
            Log.w(tag, "Error handling client ${clientSocket.remoteSocketAddress}: ${e.message}")
        } finally {
            closeClient(client)
        }
    }

    private fun performHandshake(client: ClientConnection): Boolean {
        val reader = BufferedReader(InputStreamReader(client.socket.getInputStream(), StandardCharsets.UTF_8))
        var line: String? = reader.readLine() ?: return false

        if (!line.startsWith("GET ")) {
            return false
        }

        var secKey: String? = null
        while (true) {
            line = reader.readLine() ?: break
            if (line.isEmpty() || line == "\r") break

            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val headerName = line.substring(0, colonIndex).trim()
                val headerVal = line.substring(colonIndex + 1).trim()
                if (headerName.equals("Sec-WebSocket-Key", ignoreCase = true)) {
                    secKey = headerVal
                }
            }
        }

        if (secKey == null) {
            return false
        }

        // RFC 6455 Sec-WebSocket-Accept calculation
        val magicGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash = sha1.digest((secKey + magicGuid).toByteArray(StandardCharsets.ISO_8859_1))
        val acceptKey = Base64.getEncoder().encodeToString(hash)

        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: ").append(acceptKey).append("\r\n")
            append("\r\n")
        }

        val out = client.socket.getOutputStream()
        out.write(response.toByteArray(StandardCharsets.UTF_8))
        out.flush()
        return true
    }

    /**
     * Broadcasts a UTF-8 text message to all active WebSocket clients.
     * Dead clients are safely culled.
     */
    fun broadcast(text: String) {
        if (!isRunning.get() || activeClients.isEmpty()) return

        val frameBytes = encodeTextFrame(text)
        for (client in activeClients) {
            try {
                client.sendRaw(frameBytes)
            } catch (e: Exception) {
                Log.w(tag, "Client write failed, removing: ${client.socket.remoteSocketAddress}")
                closeClient(client)
            }
        }
    }

    /**
     * Sends a ping frame to all active clients for heartbeat keep-alive.
     */
    fun sendHeartbeat() {
        if (!isRunning.get() || activeClients.isEmpty()) return

        val pingFrame = byteArrayOf(
            0x89.toByte(), // FIN + Opcode 0x9 (Ping)
            0x00.toByte()  // Mask=0, Len=0
        )
        for (client in activeClients) {
            try {
                client.sendRaw(pingFrame)
            } catch (e: Exception) {
                closeClient(client)
            }
        }
    }

    private fun closeClient(client: ClientConnection) {
        activeClients.remove(client)
        try {
            client.close()
        } catch (_: Exception) {}
        Log.i(tag, "Client disconnected. Active clients remaining: ${activeClients.size}")
    }

    /**
     * Cleanly stops the WebSocket server and closes all client connections.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return

        Log.i(tag, "Stopping WebSocket server on port $port...")
        for (client in activeClients) {
            try {
                client.close()
            } catch (_: Exception) {}
        }
        activeClients.clear()

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        listenerThread?.interrupt()
        listenerThread = null
        Log.i(tag, "WebSocket server stopped cleanly.")
    }

    private fun encodeTextFrame(text: String): ByteArray {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        val len = payload.size

        return when {
            len <= 125 -> {
                val frame = ByteArray(2 + len)
                frame[0] = 0x81.toByte() // FIN + Opcode 0x1 (Text)
                frame[1] = len.toByte()
                System.arraycopy(payload, 0, frame, 2, len)
                frame
            }
            len <= 65535 -> {
                val frame = ByteArray(4 + len)
                frame[0] = 0x81.toByte()
                frame[1] = 126.toByte()
                frame[2] = ((len ushr 8) and 0xFF).toByte()
                frame[3] = (len and 0xFF).toByte()
                System.arraycopy(payload, 0, frame, 4, len)
                frame
            }
            else -> {
                val frame = ByteArray(10 + len)
                frame[0] = 0x81.toByte()
                frame[1] = 127.toByte()
                val longLen = len.toLong()
                for (i in 0..7) {
                    frame[2 + i] = ((longLen ushr ((7 - i) * 8)) and 0xFF).toByte()
                }
                System.arraycopy(payload, 0, frame, 10, len)
                frame
            }
        }
    }

    private class ClientConnection(val socket: Socket) {
        private val outputStream: OutputStream = BufferedOutputStream(socket.getOutputStream())
        private val writeLock = Any()

        fun sendRaw(data: ByteArray) {
            synchronized(writeLock) {
                outputStream.write(data)
                outputStream.flush()
            }
        }

        fun readLoop() {
            val input = socket.getInputStream()
            val header = ByteArray(2)

            while (!socket.isClosed) {
                var readBytes = 0
                while (readBytes < 2) {
                    val r = input.read(header, readBytes, 2 - readBytes)
                    if (r < 0) return
                    readBytes += r
                }

                val b0 = header[0].toInt() and 0xFF
                val b1 = header[1].toInt() and 0xFF
                val opcode = b0 and 0x0F
                val isMasked = (b1 and 0x80) != 0
                var payloadLen = (b1 and 0x7F).toLong()

                if (payloadLen == 126L) {
                    val ext = ByteArray(2)
                    input.readFully(ext)
                    payloadLen = (((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)).toLong()
                } else if (payloadLen == 127L) {
                    val ext = ByteArray(8)
                    input.readFully(ext)
                    var l = 0L
                    for (i in 0..7) {
                        l = (l shl 8) or (ext[i].toLong() and 0xFFL)
                    }
                    payloadLen = l
                }

                val mask = if (isMasked) {
                    val m = ByteArray(4)
                    input.readFully(m)
                    m
                } else null

                // Skip or consume payload
                if (payloadLen > 0) {
                    var remaining = payloadLen
                    val buffer = ByteArray(minOf(remaining, 4096L).toInt())
                    while (remaining > 0) {
                        val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                        val r = input.read(buffer, 0, toRead)
                        if (r < 0) return
                        remaining -= r
                    }
                }

                when (opcode) {
                    0x8 -> { // Close frame
                        return
                    }
                    0x9 -> { // Ping -> reply Pong
                        val pong = byteArrayOf(0x8A.toByte(), 0x00.toByte())
                        sendRaw(pong)
                    }
                    else -> {}
                }
            }
        }

        private fun java.io.InputStream.readFully(b: ByteArray) {
            var offset = 0
            while (offset < b.size) {
                val r = read(b, offset, b.size - offset)
                if (r < 0) throw IOException("Premature EOF reading WebSocket frame")
                offset += r
            }
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }
}
