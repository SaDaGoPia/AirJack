package com.ace4.airplayreceiver

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.ace4.airplayreceiver.raop.NowPlayingInfo
import com.ace4.airplayreceiver.raop.RaopRtspServer

class AirplayAdvertiseService : Service() {

    companion object {
        const val ACTION_STOP = "com.ace4.airplayreceiver.action.STOP"
        const val EXTRA_DEVICE_NAME = "device_name"

        const val STATUS_STOPPED = 0
        const val STATUS_STARTING = 1
        const val STATUS_ADVERTISING = 2
        const val STATUS_ERROR = 3
        const val STATUS_RECONNECTING = 4

        private const val TAG = "AirplayAdvertiseService"
        private const val NOTIFICATION_ID = 1
        private const val CONNECTIVITY_ACTION = "android.net.conn.CONNECTIVITY_CHANGE"
    }

    private lateinit var advertiser: RaopAdvertiser
    private var rtspServer: RaopRtspServer? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    /** True from startAdvertising() until stopAdvertising(); gates whether WiFi
     *  changes should trigger a re-advertise, so events after a user-initiated
     *  stop are ignored. */
    private var isRunning = false
    private var deviceName: String = ""
    private var deviceId: String = ""
    private var lastAdvertisedIp: Int = 0
    private var receiverRegistered = false

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isRunning) workerHandler?.post { handleConnectivityChange() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        advertiser = RaopAdvertiser(this)
        val thread = HandlerThread("AirplayAdvertiserWorker")
        thread.start()
        workerThread = thread
        workerHandler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAdvertising()
            stopSelf()
        } else {
            val name = intent?.getStringExtra(EXTRA_DEVICE_NAME)
                ?: DeviceIdentity.getDeviceName(this)
            startAdvertising(name)
        }
        // Restart if the system kills this service under memory pressure (a real
        // risk on a 1GB RAM device) - but not after an explicit user stop, which
        // Android tracks separately and won't redeliver a restart Intent for.
        return START_STICKY
    }

    private fun startAdvertising(name: String) {
        if (isRunning) return // already running - avoid a second bind on RaopConstants.RTSP_PORT
        deviceName = name
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        StatusBus.post(STATUS_STARTING)
        registerConnectivityReceiver()

        workerHandler?.post {
            try {
                deviceId = DeviceIdentity.getOrCreateDeviceId(this)

                // Start the RTSP listener before advertising over mDNS, so the
                // port is already accepting connections by the time iOS can see us.
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val server = RaopRtspServer(deviceId, audioManager) { info -> updateNotification(info) }
                server.start()
                rtspServer = server

                advertiser.start(deviceName, deviceId)
                lastAdvertisedIp = currentWifiIp()
                StatusBus.post(STATUS_ADVERTISING)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mDNS advertisement", e)
                StatusBus.post(STATUS_ERROR)
            }
        }
    }

    /**
     * Runs on the worker thread whenever WiFi connectivity changes. A JmDNS
     * instance is bound to a specific local address at creation time, so if
     * the phone's IP changes (reconnects to the same network, roams to a
     * different one) or WiFi drops entirely, the existing advertisement is
     * stale and won't recover on its own - it has to be torn down and
     * recreated against the current address.
     */
    private fun handleConnectivityChange() {
        val ip = currentWifiIp()
        if (ip == 0) {
            if (lastAdvertisedIp != 0) {
                Log.i(TAG, "WiFi disconnected, pausing advertisement")
                advertiser.stop()
                lastAdvertisedIp = 0
                StatusBus.post(STATUS_RECONNECTING)
            }
            return
        }
        if (ip == lastAdvertisedIp) return // same address, nothing to do

        Log.i(TAG, "WiFi address changed, re-advertising")
        try {
            advertiser.stop()
            advertiser.start(deviceName, deviceId)
            lastAdvertisedIp = ip
            StatusBus.post(STATUS_ADVERTISING)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-advertise after WiFi change", e)
            lastAdvertisedIp = 0
            StatusBus.post(STATUS_RECONNECTING)
        }
    }

    private fun currentWifiIp(): Int {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.connectionInfo?.ipAddress ?: 0
    }

    private fun registerConnectivityReceiver() {
        if (receiverRegistered) return
        registerReceiver(connectivityReceiver, IntentFilter(CONNECTIVITY_ACTION))
        receiverRegistered = true
    }

    private fun unregisterConnectivityReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(connectivityReceiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
        receiverRegistered = false
    }

    private fun stopAdvertising() {
        isRunning = false
        unregisterConnectivityReceiver()
        workerHandler?.post {
            advertiser.stop()
            rtspServer?.stop()
            rtspServer = null
            lastAdvertisedIp = 0
            StatusBus.post(STATUS_STOPPED)
        }
    }

    private fun buildNotification(info: NowPlayingInfo = NowPlayingInfo()): Notification {
        val builder = Notification.Builder(this)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
        if (info.isEmpty) {
            builder.setContentTitle(getString(R.string.app_name))
            builder.setContentText(getString(R.string.status_advertising))
        } else {
            builder.setContentTitle(info.title ?: getString(R.string.app_name))
            val subtitle = listOfNotNull(info.artist, info.album).joinToString(" — ")
            builder.setContentText(subtitle.ifBlank { getString(R.string.status_advertising) })
            info.artwork?.let { builder.setLargeIcon(it) }
        }
        return builder.build()
    }

    /** Called from the RTSP server's connection-handling thread whenever iOS
     *  pushes new track metadata/artwork; updates the ongoing notification
     *  in place, and forwards the same info to MainActivity's Now Playing
     *  view via NowPlayingBus if it's open. NotificationManager is safe to
     *  call off the main thread; NowPlayingBus hops to the main thread itself. */
    private fun updateNotification(info: NowPlayingInfo) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(info))
        NowPlayingBus.post(info)
    }

    override fun onDestroy() {
        isRunning = false
        unregisterConnectivityReceiver()
        advertiser.stop()
        rtspServer?.stop()
        rtspServer = null
        workerThread?.quitSafely()
        StatusBus.post(STATUS_STOPPED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
