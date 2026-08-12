package com.ace4.airplayreceiver

import android.content.Context
import android.net.wifi.WifiManager
import java.io.IOException
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Advertises this device as a legacy (RAOP) AirPlay speaker via mDNS/DNS-SD so
 * it shows up in iOS's native AirPlay picker. This only handles discovery
 * (milestone 1) - no RTSP/RTP server is listening on the advertised ports yet,
 * so a real AirPlay client can see the entry but can't yet successfully connect.
 *
 * TXT records mirror shairport-sync's classic (non-AirPlay2) defaults, which is
 * what iOS expects to recognize a legacy AirPlay audio receiver.
 */
class RaopAdvertiser(private val context: Context) {

    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var raopServiceInfo: ServiceInfo? = null
    private var airplayServiceInfo: ServiceInfo? = null

    companion object {
        private const val RAOP_PORT = 5000
        private const val AIRPLAY_PORT = 7000
    }

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
            "et" to "0,1",
            "md" to "0,1,2",
            "pw" to "false",
            "sr" to "44100",
            "ss" to "16",
            "sv" to "false",
            "tp" to "UDP",
            "vn" to "65537",
            "vs" to "105.1",
            "am" to "Ace4AirplayReceiver",
            "sf" to "0x4"
        )
        val raop = ServiceInfo.create(
            "_raop._tcp.local.", "$deviceId@$deviceName", RAOP_PORT, 0, 0, raopTxt
        )
        dns.registerService(raop)
        raopServiceInfo = raop

        val airplayTxt = linkedMapOf(
            "deviceid" to formatAsMac(deviceId),
            "features" to "0x445F8A00,0x1C340",
            "flags" to "0x4",
            "model" to "Ace4,1",
            "srcvers" to "366.0",
            "vv" to "2"
        )
        val airplay = ServiceInfo.create(
            "_airplay._tcp.local.", deviceName, AIRPLAY_PORT, 0, 0, airplayTxt
        )
        dns.registerService(airplay)
        airplayServiceInfo = airplay
    }

    fun stop() {
        jmdns?.let { dns ->
            raopServiceInfo?.let { dns.unregisterService(it) }
            airplayServiceInfo?.let { dns.unregisterService(it) }
            try {
                dns.close()
            } catch (_: IOException) {
                // best-effort cleanup
            }
        }
        jmdns = null
        raopServiceInfo = null
        airplayServiceInfo = null

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

    private fun formatAsMac(deviceId: String): String = deviceId.chunked(2).joinToString(":")
}
