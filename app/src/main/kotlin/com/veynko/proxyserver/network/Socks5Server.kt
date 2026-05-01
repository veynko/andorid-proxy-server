package com.veynko.proxyserver.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * SOCKS5 proxy server that listens on all interfaces (0.0.0.0)
 * at the given [port].
 *
 * Handles multiple concurrent client connections using coroutines.
 */
class Socks5Server(
    private val port: Int,
    private val authEnabled: Boolean,
    private val username: String = "",
    private val password: String = ""
) {
    companion object {
        private const val TAG = "Socks5Server"
    }

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null

    /**
     * Starts the server. Blocks until the server socket is bound.
     * Returns true on success, false if the port is already in use.
     */
    fun start(): Boolean {
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = ss

            acceptJob = scope.launch {
                Log.i(TAG, "SOCKS5 server started on port $port (auth=$authEnabled)")
                acceptLoop(ss)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}")
            false
        }
    }

    /**
     * Stops the server and closes all resources.
     */
    fun stop() {
        Log.i(TAG, "Stopping SOCKS5 server")
        acceptJob?.cancel()
        serverSocket?.close()
        serverSocket = null
    }

    /**
     * Main accept loop – waits for new client connections and
     * dispatches each one to a new coroutine.
     */
    private suspend fun acceptLoop(ss: ServerSocket) = withContext(Dispatchers.IO) {
        while (isActive && !ss.isClosed) {
            try {
                val clientSocket = ss.accept()
                // Handle each connection in its own coroutine
                scope.launch {
                    Socks5Handler(
                        clientSocket = clientSocket,
                        authEnabled = authEnabled,
                        username = username,
                        password = password
                    ).handle()
                }
            } catch (e: Exception) {
                if (!ss.isClosed) {
                    Log.e(TAG, "Accept error: ${e.message}")
                }
                // If the socket is closed, the loop will exit naturally
            }
        }
        Log.i(TAG, "Accept loop terminated")
    }
}
