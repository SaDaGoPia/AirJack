package com.ace4.airplayreceiver.raop

import android.graphics.Bitmap

/** Track metadata iOS pushes to us via SET_PARAMETER during an active session. */
data class NowPlayingInfo(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: Bitmap? = null
) {
    val isEmpty: Boolean get() = title == null && artist == null && album == null && artwork == null
}
