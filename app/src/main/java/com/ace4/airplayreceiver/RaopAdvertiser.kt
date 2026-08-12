package com.ace4.airplayreceiver

import android.content.Context
import android.net.wifi.WifiManager
import com.ace4.airplayreceiver.raop.RaopConstants
import java.io.IOException
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Advertises this device as a legacy (RAOP) AirPlay speaker via mDNS/DNS-SD so
 * it shows up in iOS's native AirPlay picker.
 *
 * Only `_raop._tcp` is registered - deliberately no `_airplay._tcp` service.
 * shairport-sync only ever registers a second `_airplay._tcp` record when built
 * with AirPlay 2 support (its `secondary_txt_records`, gated behind
 * `CONFIG_AIRPLAY_2` in bonjour_strings.c). Registering one from a legacy-only
 * receiver was tried first and broke the handshake: iOS took its presence as a
 * signal that this speaker supports AirPlay 2's HomeKit-style pairing, and got
 * stuck retrying `GET /info` / `POST /pair-setup` / `/pair-verify` against us
 * instead of falling back to classic RTSP OPTIONS/ANNOUNCE - it never once sent
 * ANNOUNCE. Removing the second service fixed it. TXT record keys/values below
 * mirror shairport-sync's classic (non-AirPlay2) defaults exactly.
 */
class RaopAdvertiser(private val context: Context) {

    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var raopServiceInfo: ServiceInfo? = null

    @Throws(IOException::class)
    fun start(deviceName: String, deviceId: String) {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        val lock = wifiManager.createMulticastLock("airplayReceiverMulticastLock")
        lock.setReferenceCounted(true)
        lock.acquire()
        multicastLock = lock

        val address = wifiInetAddress(wifiManager)
        val dns = JmDNS.create(address, deviceName)
        jmdns = dns

        val raopTxt = linkedMapOf(
            "txtvers" to "1",
            "ch" to "2",
            "cn" to "0,1",
            "ek" to "1",
            "et" to "0,1",
            "md" to "0,1,2",
            "pw" to "false",
            "sr" to "44100",
            "ss" to "16",
            "sv" to "false",
            "da" to "true",
            "tp" to "TCP,UDP",
            "vn" to "65537",
            "vs" to "105.1",
            "fv" to "1",
            "am" to "Ace4AirplayReceiver",
            "sf" to "0x4"
        )
        val raop = ServiceInfo.create(
            "_raop._tcp.local.", "$deviceId@$deviceName", RaopConstants.RTSP_PORT, 0, 0, raopTxt
        )
        dns.registerService(raop)
        raopServiceInfo = raop
    }

    fun stop() {
        jmdns?.let { dns ->
            raopServiceInfo?.let { dns.unregisterService(it) }
            try {
                dns.close()
            } catch (_: IOException) {
                // best-effort cleanup
            }
        }
        jmdns = null
        raopServiceInfo = null

        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun wifiInetAddress(wifiManager: WifiManager): InetAddress {
        val ipInt = wifiManager.connectionInfo.ipAddress
        if (ipInt == 0) {
            throw IOException("Not connected to WiFi")
        }
        val bytes = byteArrayOf(
            (ipInt and 0xff).toByte(),
            (ipInt shr 8 and 0xff).toByte(),
            (ipInt shr 16 and 0xff).toByte(),
            (ipInt shr 24 and 0xff).toByte()
        )
        return InetAddress.getByAddress(bytes)
    }
}
