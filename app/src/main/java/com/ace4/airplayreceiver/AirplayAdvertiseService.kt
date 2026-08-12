package com.ace4.airplayreceiver

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log

class AirplayAdvertiseService : Service() {

    companion object {
        const val ACTION_STOP = "com.ace4.airplayreceiver.action.STOP"
        const val EXTRA_DEVICE_NAME = "device_name"

        const val STATUS_STOPPED = 0
        const val STATUS_STARTING = 1
        const val STATUS_ADVERTISING = 2
        const val STATUS_ERROR = 3

        private const val TAG = "AirplayAdvertiseService"
        private const val NOTIFICATION_ID = 1
    }

    private lateinit var advertiser: RaopAdvertiser
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

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
            val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME)
                ?: DeviceIdentity.getDeviceName(this)
            startAdvertising(deviceName)
        }
        return START_NOT_STICKY
    }

    private fun startAdvertising(deviceName: String) {
        startForeground(NOTIFICATION_ID, buildNotification())
        StatusBus.post(STATUS_STARTING)

        workerHandler?.post {
            try {
                val deviceId = DeviceIdentity.getOrCreateDeviceId(this)
                advertiser.start(deviceName, deviceId)
                StatusBus.post(STATUS_ADVERTISING)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mDNS advertisement", e)
                StatusBus.post(STATUS_ERROR)
            }
        }
    }

    private fun stopAdvertising() {
        workerHandler?.post {
            advertiser.stop()
            StatusBus.post(STATUS_STOPPED)
        }
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_advertising))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        advertiser.stop()
        workerThread?.quitSafely()
        StatusBus.post(STATUS_STOPPED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
