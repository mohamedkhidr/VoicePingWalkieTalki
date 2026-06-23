package com.smartwalkie.voicepingdemo

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.smartwalkie.voicepingdemo.kpi.PttKpiLogger
import com.smartwalkie.voicepingdemo.net.NetworkMonitor
import com.smartwalkie.voicepingsdk.ConnectionState
import com.smartwalkie.voicepingsdk.VoicePing
import com.smartwalkie.voicepingsdk.callback.ConnectCallback
import com.smartwalkie.voicepingsdk.exception.VoicePingException
import com.smartwalkie.voicepingsdk.listener.ConnectionStateListener

/**
 * Foreground service that keeps the VoicePing WebSocket connection alive
 * regardless of whether the app is in the foreground or background.
 *
 * Lifecycle:
 *   - Start via [start] (called from LoginActivity / MainActivity)
 *   - Stop via [stop] (called after explicit user sign-out)
 *
 * The service owns the [NetworkMonitor] and triggers reconnects when
 * connectivity returns, so the activity no longer needs to manage that.
 */
class VoicePingConnectionService : Service() {

    private lateinit var networkMonitor: NetworkMonitor

    @Volatile private var sawNetworkLoss = false
    @Volatile private var savedUserId = ""
    @Volatile private var savedCompany = ""
    @Volatile private var savedServerUrl = ""

    // ── Connection state → notification updates ───────────────────────────

    private val connectionStateListener = object : ConnectionStateListener {
        override fun onConnectionStateChanged(state: ConnectionState) {
            updateNotification(state)
        }
        override fun onConnectionError(e: VoicePingException) {
            Log.w(TAG, "Connection error: ${e.message}")
        }
    }

    // ── Network monitor → reconnect on return ─────────────────────────────

    private val networkListener = object : NetworkMonitor.Listener {
        override fun onNetworkAvailable(transport: NetworkMonitor.Transport) {
            if (sawNetworkLoss &&
                VoicePing.getConnectionState() == ConnectionState.DISCONNECTED &&
                savedUserId.isNotBlank()
            ) {
                Log.d(TAG, "Network returned — reconnecting")
                doConnect(savedServerUrl, savedUserId, savedCompany)
            }
            sawNetworkLoss = false
        }
        override fun onNetworkLost() { sawNetworkLoss = true }
        override fun onTransportChanged(transport: NetworkMonitor.Transport) {}
    }

    // ── Service lifecycle ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createNotificationChannel()
        startForegroundCompat(buildNotification(ConnectionState.CONNECTING))
        networkMonitor = NetworkMonitor(this, networkListener)
        networkMonitor.start()
        PttKpiLogger.get()?.addConnectionStateListener(connectionStateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        when (intent?.action) {
            ACTION_CONNECT -> {
                Log.i(TAG, "ACTION_CONNECT "+ VoicePing.getConnectionState())
                val userId    = intent.getStringExtra(EXTRA_USER_ID)    ?: loadOrReturn() ?: return START_STICKY
                val company   = intent.getStringExtra(EXTRA_COMPANY)    ?: loadOrReturn() ?: return START_STICKY
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: loadOrReturn() ?: return START_STICKY
                cacheCredentials(userId, company, serverUrl)
                if (VoicePing.getConnectionState() == ConnectionState.DISCONNECTED) {
                    doConnect(serverUrl, userId, company)
                }
            }
            ACTION_STOP -> stopSelf()
            null -> {
                // System restarted the service (START_STICKY); reload saved credentials.
                val userId    = MyPrefs.userId?.takeIf { it.isNotBlank() }    ?: return START_STICKY
                val company   = MyPrefs.company?.takeIf { it.isNotBlank() }   ?: return START_STICKY
                val serverUrl = MyPrefs.serverUrl?.takeIf { it.isNotBlank() } ?: return START_STICKY
                cacheCredentials(userId, company, serverUrl)
                if (VoicePing.getConnectionState() == ConnectionState.DISCONNECTED) {
                    doConnect(serverUrl, userId, company)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        networkMonitor.stop()
        PttKpiLogger.get()?.removeConnectionStateListener(connectionStateListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun cacheCredentials(userId: String, company: String, serverUrl: String) {
        savedUserId    = userId
        savedCompany   = company
        savedServerUrl = serverUrl
    }

    private fun loadOrReturn(): String? = null  // sentinel: caller falls back to START_STICKY

    private fun doConnect(serverUrl: String, userId: String, company: String) {
        Log.i(TAG, "doConnect")
        updateNotification(ConnectionState.CONNECTING)
        VoicePing.connect(serverUrl, userId, company, object : ConnectCallback {
            override fun onConnected() = Unit
            override fun onFailed(e: VoicePingException) {
                Log.w(TAG, "Connect failed: ${e.message}")
            }
        })
    }

    private fun updateNotification(state: ConnectionState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state))
        Log.i(TAG, "updateNotification "+ state.name)
    }

    private fun buildNotification(state: ConnectionState): Notification {
        Log.i(TAG, "buildNotification")
        val statusText = when (state) {
            ConnectionState.CONNECTED    -> "Connected"
            ConnectionState.CONNECTING   -> "Connecting…"
            ConnectionState.DISCONNECTED -> "Disconnected"
        }
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VoicePing")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VoicePing Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the VoicePing connection alive in the background"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }


    private fun startForegroundCompat(notification: Notification) {
        Log.i(TAG, "startForegroundCompat")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ── Static API ────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VoiceConnectionService"
        private const val CHANNEL_ID     = "voiceping_connection"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_CONNECT = "com.smartwalkie.voicepingdemo.action.CONNECT"
        private const val ACTION_STOP    = "com.smartwalkie.voicepingdemo.action.STOP"

        private const val EXTRA_USER_ID    = "user_id"
        private const val EXTRA_COMPANY    = "company"
        private const val EXTRA_SERVER_URL = "server_url"

        /** Start (or re-connect) the foreground service. Safe to call when already running. */
        fun start(context: Context, userId: String, company: String, serverUrl: String) {
            val intent = Intent(context, VoicePingConnectionService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_USER_ID,    userId)
                putExtra(EXTRA_COMPANY,    company)
                putExtra(EXTRA_SERVER_URL, serverUrl)
            }
            context.startForegroundService(intent)
        }

        /**
         * Stop the service. Call AFTER [VoicePing.disconnect] completes so the
         * connection is already torn down before the service exits.
         */
        fun stop(context: Context) {
            context.startService(
                Intent(context, VoicePingConnectionService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }
    }
}
