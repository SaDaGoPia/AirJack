package com.ace4.airplayreceiver

import android.os.Handler
import android.os.Looper

/**
 * In-process status pipe from AirplayAdvertiseService's worker thread to the UI.
 * A plain callback is enough here (single app, single process) so this skips
 * the broadcast/receiver machinery and the androidx LocalBroadcastManager dependency.
 */
object StatusBus {
    var currentStatus: Int = AirplayAdvertiseService.STATUS_STOPPED
        private set
    var listener: ((Int) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun post(status: Int) {
        currentStatus = status
        mainHandler.post { listener?.invoke(status) }
    }
}
