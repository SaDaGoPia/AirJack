package com.ace4.airplayreceiver

import android.content.Context
import java.security.SecureRandom

/**
 * Persists a stable per-install device identity: a random 6-byte id used as
 * the AirPlay/RAOP "deviceid" (advertised MAC-like in TXT records and as the
 * _raop._tcp instance name prefix), and the user-chosen speaker name.
 */
object DeviceIdentity {
    private const val PREFS = "airplay_receiver_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }

        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        val id = bytes.joinToString("") { String.format("%02X", it) }
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun getDeviceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_NAME, null) ?: context.getString(R.string.default_device_name)
    }

    fun setDeviceName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEVICE_NAME, name).apply()
    }
}
