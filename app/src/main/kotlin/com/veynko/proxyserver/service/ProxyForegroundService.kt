package com.veynko.proxyserver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.veynko.proxyserver.MainActivity
import com.veynko.proxyserver.R
import com.veynko.proxyserver.network.Socks5Server
import com.veynko.proxyserver.util.ConnectionLogBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that hosts the SOCKS5 proxy server.
 *
 * Start via [start] and stop via [stop] companion functions.
 * Observe [isRunning] to track current state.
 */
class ProxyForegroundService : Service() {

    companion object {
        private const val TAG = "ProxyForegroundService"
        private const val CHANNEL_ID = "proxy_server_channel"
        private const val NOTIFICATION_ID = 1

        // Intent actions
        const val ACTION_START = "com.veynko.proxyserver.action.START"
        const val ACTION_STOP = "com.veynko.proxyserver.action.STOP"

        // Intent extras
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_AUTH_ENABLED = "extra_auth_enabled"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"

        /** Publicly observable running state. */
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        /** Start the proxy service with the given configuration. */
        fun start(
            context: Context,
            port: Int,
            authEnabled: Boolean,
            username: String,
            password: String
        ) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_AUTH_ENABLED, authEnabled)
                putExtra(EXTRA_USERNAME, username)
                putExtra(EXTRA_PASSWORD, password)
            }
            context.startForegroundService(intent)
        }

        /** Stop the proxy service. */
        fun stop(context: Context) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var proxyServer: Socks5Server? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 1080)
                val authEnabled = intent.getBooleanExtra(EXTRA_AUTH_ENABLED, false)
                val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
                val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
                startProxy(port, authEnabled, username, password)
            }
            ACTION_STOP -> {
                stopProxy()
            }
        }
        return START_NOT_STICKY
    }

    private fun startProxy(port: Int, authEnabled: Boolean, username: String, password: String) {
        Log.i(TAG, "Starting proxy on port $port, auth=$authEnabled")

        // Start as a foreground service immediately
        startForeground(NOTIFICATION_ID, buildNotification(port))

        // Stop any existing server instance
        proxyServer?.stop()

        val server = Socks5Server(
            port = port,
            authEnabled = authEnabled,
            username = username,
            password = password,
            onConnectAttempt = { host, remotePort ->
                ConnectionLogBus.log(host, remotePort)
            }
        )
        val started = server.start()
        if (started) {
            proxyServer = server
            _isRunning.value = true
            Log.i(TAG, "Proxy started successfully on port $port")
        } else {
            Log.e(TAG, "Failed to start proxy on port $port")
            _isRunning.value = false
            stopSelf()
        }
    }

    private fun stopProxy() {
        Log.i(TAG, "Stopping proxy")
        proxyServer?.stop()
        proxyServer = null
        _isRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        proxyServer?.stop()
        proxyServer = null
        _isRunning.value = false
        super.onDestroy()
    }

    // ----- Notification helpers -----

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Proxy Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "SOCKS5 Proxy Server running notification"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(port: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ProxyForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SOCKS5 Proxy Running")
            .setContentText("Listening on port $port")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}
