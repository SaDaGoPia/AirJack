package com.ace4.airplayreceiver.raop

import android.util.Base64
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "RaopRtspServer"

/**
 * Milestone 2: accepts the RTSP handshake (OPTIONS/ANNOUNCE/SETUP/RECORD/...)
 * on RaopConstants.RTSP_PORT and derives the per-session AES key/IV, proving
 * an encrypted RAOP session can be established. It does NOT yet decrypt or
 * decode RTP audio packets, or respond to NTP timing/retransmit requests -
 * that's milestone 3, once ALAC decode + AudioTrack playback are wired up.
 * The audio UDP socket set up in SETUP just counts/logs incoming packets for
 * now as proof the client is actually streaming to us.
 */
class RaopRtspServer(private val deviceIdHex: String) {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        val server = ServerSocket(RaopConstants.RTSP_PORT)
        serverSocket = server
        val thread = Thread({
            while (running) {
                try {
                    val socket = server.accept()
                    val handler = RaopConnectionHandler(socket, deviceIdHex)
                    Thread(handler, "RaopConn-${socket.inetAddress.hostAddress}").apply {
                        isDaemon = true
                        start()
                    }
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "accept() failed: ${e.message}")
                }
            }
        }, "RaopRtspAcceptThread")
        thread.isDaemon = true
        thread.start()
        acceptThread = thread
        Log.i(TAG, "Listening for RTSP connections on port ${RaopConstants.RTSP_PORT}")
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // best-effort cleanup
        }
        serverSocket = null
        acceptThread = null
    }
}

private class RaopConnectionHandler(
    private val socket: Socket,
    private val deviceIdHex: String
) : Runnable {

    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null
    private var fmtp: String? = null
    private var audioSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null

    override fun run() {
        Log.i(TAG, "Connection from ${socket.inetAddress.hostAddress}")
        try {
            socket.tcpNoDelay = true
            val output = socket.getOutputStream()
            val reader = RtspLineReader(socket.getInputStream())
            while (!socket.isClosed) {
                val req = RtspProtocol.parseRequest(reader) ?: break
                Log.d(TAG, "${req.method} ${req.path} (CSeq ${req.header("CSeq")})")
                val response = handle(req)
                output.write(response)
                output.flush()
                if (req.method == "TEARDOWN") break
            }
        } catch (e: IOException) {
            Log.d(TAG, "Connection ended: ${e.message}")
        } finally {
            cleanup()
            try {
                socket.close()
            } catch (_: IOException) {
                // already closing
            }
        }
    }

    private fun handle(req: RtspRequest): ByteArray {
        val cseq = req.header("CSeq")
        return try {
            when (req.method) {
                "OPTIONS" -> handleOptions(req, cseq)
                "ANNOUNCE" -> handleAnnounce(req, cseq)
                "SETUP" -> handleSetup(req, cseq)
                "RECORD" -> handleRecord(cseq)
                "FLUSH" -> RtspProtocol.buildResponse(200, "OK", cseq)
                "TEARDOWN" -> RtspProtocol.buildResponse(200, "OK", cseq, mapOf("Connection" to "close"))
                "GET_PARAMETER" -> RtspProtocol.buildResponse(200, "OK", cseq)
                "SET_PARAMETER" -> RtspProtocol.buildResponse(200, "OK", cseq)
                else -> RtspProtocol.buildResponse(501, "Not Implemented", cseq)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ${req.method}", e)
            RtspProtocol.buildResponse(500, "Internal Server Error", cseq)
        }
    }

    private fun handleOptions(req: RtspRequest, cseq: String?): ByteArray {
        val headers = LinkedHashMap<String, String>()
        headers["Public"] =
            "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
        req.header("Apple-Challenge")?.let { challengeB64 ->
            computeAppleResponse(challengeB64)?.let { headers["Apple-Response"] = it }
        }
        return RtspProtocol.buildResponse(200, "OK", cseq, headers)
    }

    private fun computeAppleResponse(challengeB64: String): String? {
        val challenge = Base64.decode(challengeB64, Base64.DEFAULT)
        if (challenge.size > 16) {
            Log.w(TAG, "Oversized Apple-Challenge (${challenge.size} bytes), ignoring")
            return null
        }
        val serverIp = (socket.localAddress).address // IPv4, 4 bytes
        val deviceId = hexToBytes(deviceIdHex)

        val buf = ByteArray(48)
        var offset = 0
        System.arraycopy(challenge, 0, buf, offset, challenge.size)
        offset += challenge.size
        System.arraycopy(serverIp, 0, buf, offset, serverIp.size)
        offset += serverIp.size
        System.arraycopy(deviceId, 0, buf, offset, deviceId.size)
        offset += deviceId.size
        val buflen = maxOf(offset, 0x20)

        val signed = RaopCrypto.signAppleChallenge(buf.copyOf(buflen))
        return Base64.encodeToString(signed, Base64.NO_WRAP).trimEnd('=')
    }

    private fun handleAnnounce(req: RtspRequest, cseq: String?): ByteArray {
        val sdp = String(req.body, Charsets.US_ASCII)
        var aesIvB64: String? = null
        var rsaAesKeyB64: String? = null
        var fmtpLine: String? = null
        for (line in sdp.split("\r\n", "\n")) {
            when {
                line.startsWith("a=aesiv:") -> aesIvB64 = line.removePrefix("a=aesiv:")
                line.startsWith("a=rsaaeskey:") -> rsaAesKeyB64 = line.removePrefix("a=rsaaeskey:")
                line.startsWith("a=fmtp:") -> fmtpLine = line.removePrefix("a=fmtp:")
            }
        }
        fmtp = fmtpLine

        when {
            aesIvB64 != null && rsaAesKeyB64 != null -> {
                val iv = Base64.decode(aesIvB64, Base64.DEFAULT)
                val key = RaopCrypto.decryptAesKey(Base64.decode(rsaAesKeyB64, Base64.DEFAULT))
                if (iv.size != 16 || key.size != 16) {
                    Log.w(TAG, "ANNOUNCE: bad key/iv length (iv=${iv.size}, key=${key.size})")
                    return RtspProtocol.buildResponse(456, "Header Field Not Valid for Resource", cseq)
                }
                aesIv = iv
                aesKey = key
                Log.i(TAG, "ANNOUNCE: encrypted session established, AES key derived. fmtp=$fmtpLine")
            }
            aesIvB64 == null && rsaAesKeyB64 == null -> {
                Log.i(TAG, "ANNOUNCE: unencrypted session requested. fmtp=$fmtpLine")
            }
            else -> {
                Log.w(TAG, "ANNOUNCE: missing aesiv or rsaaeskey")
                return RtspProtocol.buildResponse(456, "Header Field Not Valid for Resource", cseq)
            }
        }
        return RtspProtocol.buildResponse(200, "OK", cseq)
    }

    private fun handleSetup(req: RtspRequest, cseq: String?): ByteArray {
        val transport = req.header("Transport")
            ?: return RtspProtocol.buildResponse(451, "Invalid Parameters", cseq)
        val clientControlPort = extractPort(transport, "control_port=")
        val clientTimingPort = extractPort(transport, "timing_port=")
        if (clientControlPort == null || clientTimingPort == null) {
            return RtspProtocol.buildResponse(451, "Invalid Parameters", cseq)
        }

        val audio = DatagramSocket(0)
        val control = DatagramSocket(0)
        val timing = DatagramSocket(0)
        audioSocket = audio
        controlSocket = control
        timingSocket = timing
        startAudioReceiver(audio)

        Log.i(
            TAG,
            "SETUP: client control=$clientControlPort timing=$clientTimingPort -> " +
                "local audio=${audio.localPort} control=${control.localPort} timing=${timing.localPort}"
        )

        val respTransport = "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
            "control_port=${control.localPort};timing_port=${timing.localPort};" +
            "server_port=${audio.localPort}"
        return RtspProtocol.buildResponse(
            200, "OK", cseq,
            mapOf("Transport" to respTransport, "Session" to "1")
        )
    }

    private fun handleRecord(cseq: String?): ByteArray {
        if (audioSocket == null) {
            return RtspProtocol.buildResponse(455, "Method Not Valid In This State", cseq)
        }
        Log.i(TAG, "RECORD: session established, awaiting RTP audio packets")
        return RtspProtocol.buildResponse(200, "OK", cseq, mapOf("Audio-Latency" to "11025"))
    }

    private fun startAudioReceiver(socket: DatagramSocket) {
        val thread = Thread({
            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            var count = 0L
            try {
                while (!socket.isClosed) {
                    socket.receive(packet)
                    count++
                    if (count == 1L || count % 100 == 0L) {
                        Log.i(TAG, "Audio RTP packet #$count, ${packet.length} bytes from ${packet.address}")
                    }
                }
            } catch (_: IOException) {
                // socket closed during teardown/cleanup - expected
            }
        }, "RaopAudioReceiver")
        thread.isDaemon = true
        thread.start()
    }

    private fun extractPort(transport: String, key: String): Int? {
        val idx = transport.indexOf(key)
        if (idx == -1) return null
        val start = idx + key.length
        var end = start
        while (end < transport.length && transport[end].isDigit()) end++
        return transport.substring(start, end).toIntOrNull()
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun cleanup() {
        audioSocket?.close()
        controlSocket?.close()
        timingSocket?.close()
        audioSocket = null
        controlSocket = null
        timingSocket = null
        aesKey = null
        aesIv = null
    }
}
