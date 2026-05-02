package com.veynko.proxyserver.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Handles a single SOCKS5 client connection.
 *
 * Implements the SOCKS5 protocol per RFC 1928 and optional
 * username/password authentication per RFC 1929.
 */
class Socks5Handler(
    private val clientSocket: Socket,
    private val authEnabled: Boolean,
    private val username: String,
    private val password: String,
    private val onConnectAttempt: ((host: String, port: Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "Socks5Handler"

        // SOCKS5 constants
        private const val SOCKS_VERSION: Byte = 0x05
        private const val AUTH_VERSION: Byte = 0x01

        // Authentication methods
        private const val METHOD_NO_AUTH: Byte = 0x00
        private const val METHOD_USERNAME_PASSWORD: Byte = 0x02
        private const val METHOD_NO_ACCEPTABLE: Byte = 0xFF.toByte()

        // Commands
        private const val CMD_CONNECT: Byte = 0x01

        // Address types
        private const val ATYP_IPV4: Byte = 0x01
        private const val ATYP_DOMAIN: Byte = 0x03
        private const val ATYP_IPV6: Byte = 0x04

        // Reply codes
        private const val REP_SUCCESS: Byte = 0x00
        private const val REP_GENERAL_FAILURE: Byte = 0x01
        private const val REP_NOT_ALLOWED: Byte = 0x02
        private const val REP_HOST_UNREACHABLE: Byte = 0x04
        private const val REP_CONN_REFUSED: Byte = 0x05
        private const val REP_CMD_NOT_SUPPORTED: Byte = 0x07
        private const val REP_ATYP_NOT_SUPPORTED: Byte = 0x08

        // Buffer size for data forwarding
        private const val BUFFER_SIZE = 8192
    }

    /**
     * Handles the full SOCKS5 handshake and then proxies data
     * between client and remote host.
     */
    suspend fun handle() = withContext(Dispatchers.IO) {
        val clientAddr = clientSocket.remoteSocketAddress
        Log.d(TAG, "New connection from $clientAddr")
        try {
            clientSocket.use { socket ->
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                // Step 1: Method negotiation
                if (!negotiateMethod(input, output)) {
                    Log.w(TAG, "Method negotiation failed for $clientAddr")
                    return@withContext
                }

                // Step 2: Authentication (if required)
                if (authEnabled && !authenticate(input, output)) {
                    Log.w(TAG, "Authentication failed for $clientAddr")
                    return@withContext
                }

                // Step 3: Handle the CONNECT request
                val remoteSocket = processRequest(input, output) ?: return@withContext

                // Step 4: Forward data between client and remote
                Log.i(TAG, "Tunnel established: $clientAddr <-> ${remoteSocket.remoteSocketAddress}")
                remoteSocket.use {
                    forwardData(input, output, it.getInputStream(), it.getOutputStream())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling connection from $clientAddr: ${e.message}")
        }
    }

    /**
     * SOCKS5 greeting/method negotiation (RFC 1928 §3).
     *
     * Client sends: [VER=0x05, NMETHODS, METHODS...]
     * Server responds: [VER=0x05, METHOD]
     */
    private fun negotiateMethod(input: InputStream, output: OutputStream): Boolean {
        val version = input.read()
        if (version != SOCKS_VERSION.toInt()) {
            Log.w(TAG, "Unsupported SOCKS version: $version")
            return false
        }

        val nMethods = input.read()
        if (nMethods <= 0) return false

        val methods = ByteArray(nMethods)
        readFully(input, methods)

        val selectedMethod = if (authEnabled) {
            if (methods.contains(METHOD_USERNAME_PASSWORD)) METHOD_USERNAME_PASSWORD
            else METHOD_NO_ACCEPTABLE
        } else {
            if (methods.contains(METHOD_NO_AUTH)) METHOD_NO_AUTH
            else METHOD_NO_ACCEPTABLE
        }

        output.write(byteArrayOf(SOCKS_VERSION, selectedMethod))
        output.flush()

        return selectedMethod != METHOD_NO_ACCEPTABLE
    }

    /**
     * Username/password authentication (RFC 1929).
     *
     * Client: [VER=0x01, ULEN, UNAME, PLEN, PASSWD]
     * Server: [VER=0x01, STATUS] where STATUS=0x00 means success
     */
    private fun authenticate(input: InputStream, output: OutputStream): Boolean {
        val authVersion = input.read()
        if (authVersion != AUTH_VERSION.toInt()) {
            Log.w(TAG, "Unsupported auth sub-version: $authVersion")
            output.write(byteArrayOf(AUTH_VERSION, 0x01))
            output.flush()
            return false
        }

        val uLen = input.read()
        val uBytes = ByteArray(uLen)
        readFully(input, uBytes)

        val pLen = input.read()
        val pBytes = ByteArray(pLen)
        readFully(input, pBytes)

        val providedUser = String(uBytes, Charsets.UTF_8)
        val providedPass = String(pBytes, Charsets.UTF_8)

        val success = providedUser == username && providedPass == password
        val status: Byte = if (success) 0x00 else 0x01
        output.write(byteArrayOf(AUTH_VERSION, status))
        output.flush()

        if (!success) {
            Log.w(TAG, "Auth failed: user='$providedUser'")
        }
        return success
    }

    /**
     * Parses the SOCKS5 CONNECT request (RFC 1928 §4).
     *
     * Client: [VER, CMD, RSV=0x00, ATYP, DST.ADDR, DST.PORT]
     * Server: [VER, REP, RSV=0x00, ATYP, BND.ADDR, BND.PORT]
     *
     * @return the connected remote [Socket], or null on failure
     */
    private fun processRequest(input: InputStream, output: OutputStream): Socket? {
        val version = input.read()
        if (version != SOCKS_VERSION.toInt()) {
            sendReply(output, REP_GENERAL_FAILURE)
            return null
        }

        val cmd = input.read().toByte()
        input.read() // reserved byte

        if (cmd != CMD_CONNECT) {
            Log.w(TAG, "Unsupported command: $cmd")
            sendReply(output, REP_CMD_NOT_SUPPORTED)
            return null
        }

        val atyp = input.read().toByte()
        val host: String = when (atyp) {
            ATYP_IPV4 -> {
                val addrBytes = ByteArray(4)
                readFully(input, addrBytes)
                InetAddress.getByAddress(addrBytes).hostAddress ?: ""
            }
            ATYP_DOMAIN -> {
                val len = input.read()
                val domainBytes = ByteArray(len)
                readFully(input, domainBytes)
                String(domainBytes, Charsets.UTF_8)
            }
            ATYP_IPV6 -> {
                val addrBytes = ByteArray(16)
                readFully(input, addrBytes)
                InetAddress.getByAddress(addrBytes).hostAddress ?: ""
            }
            else -> {
                Log.w(TAG, "Unsupported address type: $atyp")
                sendReply(output, REP_ATYP_NOT_SUPPORTED)
                return null
            }
        }

        val portHigh = input.read()
        val portLow = input.read()
        val port = (portHigh shl 8) or portLow

        Log.i(TAG, "CONNECT $host:$port")
        onConnectAttempt?.invoke(host, port)

        return try {
            val remoteSocket = Socket()
            remoteSocket.connect(InetSocketAddress(host, port), 10_000)
            sendReply(output, REP_SUCCESS)
            remoteSocket
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $host:$port - ${e.message}")
            val rep = when (e) {
                is java.net.ConnectException -> REP_CONN_REFUSED
                is java.net.UnknownHostException -> REP_HOST_UNREACHABLE
                else -> REP_GENERAL_FAILURE
            }
            sendReply(output, rep)
            null
        }
    }

    /**
     * Sends a SOCKS5 reply to the client.
     * Uses a fixed IPv4 bound address of 0.0.0.0:0.
     */
    private fun sendReply(output: OutputStream, rep: Byte) {
        // [VER, REP, RSV, ATYP=IPv4, 0.0.0.0, port=0]
        val reply = byteArrayOf(
            SOCKS_VERSION, rep, 0x00, ATYP_IPV4,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00
        )
        try {
            output.write(reply)
            output.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send reply: ${e.message}")
        }
    }

    /**
     * Full-duplex data forwarding between client and remote host.
     * Two coroutines run concurrently, each copying in one direction.
     * As soon as either direction closes, the other is cancelled so
     * both sides tear down symmetrically.
     */
    private suspend fun forwardData(
        clientIn: InputStream,
        clientOut: OutputStream,
        remoteIn: InputStream,
        remoteOut: OutputStream
    ) = coroutineScope {
        val clientToRemote = launch(Dispatchers.IO) {
            try {
                val buf = ByteArray(BUFFER_SIZE)
                var n: Int
                while (clientIn.read(buf).also { n = it } != -1) {
                    remoteOut.write(buf, 0, n)
                    remoteOut.flush()
                }
            } catch (_: Exception) {
                // Connection closed
            }
        }

        val remoteToClient = launch(Dispatchers.IO) {
            try {
                val buf = ByteArray(BUFFER_SIZE)
                var n: Int
                while (remoteIn.read(buf).also { n = it } != -1) {
                    clientOut.write(buf, 0, n)
                    clientOut.flush()
                }
            } catch (_: Exception) {
                // Connection closed
            }
        }

        // Cancel both jobs as soon as either direction finishes
        launch {
            clientToRemote.join()
            remoteToClient.cancel()
        }
        launch {
            remoteToClient.join()
            clientToRemote.cancel()
        }

        clientToRemote.join()
        remoteToClient.join()
    }

    /**
     * Reads exactly [buf.size] bytes from [input] into [buf].
     * Throws [java.io.EOFException] if the stream ends prematurely.
     */
    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val read = input.read(buf, offset, buf.size - offset)
            if (read == -1) throw java.io.EOFException("Stream ended prematurely")
            offset += read
        }
    }
}
