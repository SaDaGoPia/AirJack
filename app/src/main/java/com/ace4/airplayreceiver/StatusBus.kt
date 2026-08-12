package com.ace4.airplayreceiver

import android.os.Handler
import android.os.Looper

/**
 * In-process status pipe from AirplayAdvertiseService's worker thread to the UI.
 * A plain callback is enough here (single app, single process) so this skips
 * the broadcast/receiver machinery and the androidx LocalBroadcastManager dependency.
 */
object StatusBus {
    var listener: ((Int) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun post(status: Int) {
        mainHandler.post { listener?.invoke(status) }
    }
}
