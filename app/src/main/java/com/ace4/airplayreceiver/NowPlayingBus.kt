package com.ace4.airplayreceiver

import android.os.Handler
import android.os.Looper
import com.ace4.airplayreceiver.raop.NowPlayingInfo

/**
 * In-process pipe from AirplayAdvertiseService (which already receives
 * NowPlayingInfo updates via RaopRtspServer's callback, to drive the
 * notification) to MainActivity, so the app's own Now Playing view can show
 * live title/artist/artwork too. Mirrors StatusBus's pattern exactly - same
 * single-app/single-process reasoning applies, no broadcast machinery needed.
 */
object NowPlayingBus {
    var listener: ((NowPlayingInfo) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun post(info: NowPlayingInfo) {
        mainHandler.post { listener?.invoke(info) }
    }
}
