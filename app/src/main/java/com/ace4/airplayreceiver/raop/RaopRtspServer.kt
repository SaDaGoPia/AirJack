package com.ace4.airplayreceiver.raop

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "RaopRtspServer"

/**
 * Accepts the RTSP handshake (OPTIONS/ANNOUNCE/SETUP/RECORD/...) on
 * RaopConstants.RTSP_PORT, derives the per-session AES key/IV, decrypts,
 * ALAC-decodes, and plays each incoming RTP audio packet through AudioTrack,
 * and requests retransmission of lost packets over the control channel.
 * Does NOT implement NTP-style clock sync (the timing channel) - that's
 * mainly for long-session clock drift correction between sender and
 * receiver, which basic local playback here doesn't depend on; skipped as
 * disproportionate effort for this single-device setup (see decisions doc).
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
    private var alacDecoder: AlacDecoder? = null
    private var audioTrack: AudioTrack? = null
    private var clientAddress: InetAddress? = null
    private var clientControlPort: Int = 0
    private var expectedSeqno: Int = -1 // -1 = not yet initialized (no packet seen yet)

    companion object {
        /** Bigger gaps than this are treated as a stream restart/reorder, not loss - don't chase them. */
        private const val MAX_RESEND_GAP = 32
    }

    override fun run() {
        Log.i(TAG, "Connection from ${socket.inetAddress.hostAddress}")
        try {
            socket.tcpNoDelay = true
            // Detect a silently dead connection (iPhone drops off WiFi, app
            // killed, etc. - no TCP FIN, no TEARDOWN) instead of blocking here
            // forever. Real sessions send RTSP traffic (OPTIONS/SET_PARAMETER
            // keepalives) every few seconds during playback, so 30s of total
            // silence reliably means the client is gone.
            socket.soTimeout = 30_000
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
        } catch (e: SocketTimeoutException) {
            Log.i(TAG, "Connection timed out after 30s of silence, assuming client is gone")
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
                "FLUSH" -> handleFlush(cseq)
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
        fmtpLine?.let {
            try {
                alacDecoder = AlacDecoder(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse fmtp \"$it\" - won't be able to decode audio", e)
            }
        }

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
        clientAddress = socket.inetAddress
        this.clientControlPort = clientControlPort

        val audio = DatagramSocket(0)
        val control = DatagramSocket(0)
        val timing = DatagramSocket(0)
        audioSocket = audio
        controlSocket = control
        timingSocket = timing
        audioTrack = createAudioTrack(alacDecoder)
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

    private fun handleFlush(cseq: String?): ByteArray {
        try {
            audioTrack?.flush()
        } catch (_: Exception) {
            // no-op if the track isn't in a state that allows flushing
        }
        return RtspProtocol.buildResponse(200, "OK", cseq)
    }

    private fun createAudioTrack(decoder: AlacDecoder?): AudioTrack {
        val sampleRate = decoder?.sampleRate ?: 44100
        val channelConfig =
            if ((decoder?.numChannels ?: 2) >= 2) AudioFormat.CHANNEL_OUT_STEREO
            else AudioFormat.CHANNEL_OUT_MONO
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )
        // Generous headroom over the minimum: we don't implement NTP clock
        // sync, so a bigger buffer absorbs WiFi jitter rather than
        // underrunning (audible dropouts) on every small delay.
        val bufferSize = maxOf(minBufferSize, 4096) * 4
        @Suppress("DEPRECATION") // AudioTrack.Builder needs API 23+; this app's minSdk is 19
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
            AudioTrack.MODE_STREAM
        )
        track.play()
        return track
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
                    handleAudioPacket(packet.data, packet.length)
                    if (count == 1L || count % 500 == 0L) {
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

    /**
     * RTP header is 12 bytes: version/flags, marker+payload type, seqno(2),
     * timestamp(4), SSRC(4). Resent packets (type 0x56, arriving on this same
     * audio socket in response to a resend request) carry an extra 4-byte
     * wrapper before that same 12-byte header.
     */
    private fun handleAudioPacket(data: ByteArray, length: Int) {
        if (length <= 12) return
        val payloadType = data[1].toInt() and 0x7F
        val headerOffset = when (payloadType) {
            0x60 -> 0 // regular audio data
            0x56 -> 4 // resent audio data - extra 4-byte prefix before the RTP header
            else -> return
        }
        if (length <= 12 + headerOffset) return

        val seqno = ((data[headerOffset + 2].toInt() and 0xFF) shl 8) or
            (data[headerOffset + 3].toInt() and 0xFF)
        if (payloadType == 0x60) trackSequence(seqno)

        val decoder = alacDecoder ?: return
        val payload = data.copyOfRange(headerOffset + 12, length)
        val key = aesKey
        val iv = aesIv
        val alacFrame = if (key != null && iv != null) decryptAudio(payload, key, iv) else payload

        try {
            val pcm = decoder.decode(alacFrame)
            audioTrack?.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.e(TAG, "ALAC decode/playback failed", e)
        }
    }

    /**
     * Detects gaps in the regular-audio sequence number stream and requests
     * retransmission of anything missing. Resent packets are played as soon
     * as they arrive rather than being reinserted in order - there's no
     * jitter/reorder buffer here, so a very late resend can play slightly
     * out of order. That's a minor, rare artifact compared to the permanent
     * silent gap that not requesting a resend at all would leave.
     */
    private fun trackSequence(seqno: Int) {
        if (expectedSeqno == -1) {
            expectedSeqno = (seqno + 1) and 0xFFFF
            return
        }
        val gap = (seqno - expectedSeqno) and 0xFFFF
        when {
            gap == 0 -> expectedSeqno = (seqno + 1) and 0xFFFF
            gap in 1..MAX_RESEND_GAP -> {
                sendResendRequest(expectedSeqno, gap)
                expectedSeqno = (seqno + 1) and 0xFFFF
            }
            // else: seqno is behind expected (duplicate, reordered, or a huge
            // gap suggesting a stream restart) - leave expectedSeqno alone.
        }
    }

    private fun sendResendRequest(firstMissingSeqno: Int, count: Int) {
        val address = clientAddress ?: return
        val control = controlSocket ?: return
        if (clientControlPort == 0) return
        try {
            val req = ByteArray(8)
            req[0] = 0x80.toByte()
            req[1] = 0xD5.toByte() // classic RAOP 'resend' (marker bit set on type 0x55)
            req[2] = 0; req[3] = 1 // our own sequence number for this request - always 1
            req[4] = (firstMissingSeqno shr 8).toByte()
            req[5] = firstMissingSeqno.toByte()
            req[6] = (count shr 8).toByte()
            req[7] = count.toByte()
            control.send(DatagramPacket(req, req.size, address, clientControlPort))
            Log.i(TAG, "Requested resend of $count packet(s) from seqno $firstMissingSeqno")
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send resend request: ${e.message}")
        }
    }

    /**
     * Classic RAOP encrypts the payload with AES-128-CBC, but only in whole
     * 16-byte blocks - any trailing partial block is left as plaintext - and
     * every packet's CBC chain restarts from the same session IV rather than
     * chaining across packets. Both quirks come straight from shairport-sync's
     * player.c and must be replicated exactly to match what iOS encrypted.
     */
    private fun decryptAudio(payload: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val aesLen = payload.size and 0xF.inv()
        if (aesLen == 0) return payload
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(payload, 0, aesLen)
        return if (aesLen == payload.size) decrypted else decrypted + payload.copyOfRange(aesLen, payload.size)
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
        alacDecoder = null
        audioTrack?.let {
            try {
                it.stop()
            } catch (_: Exception) {
                // already stopped/released
            }
            it.release()
        }
        audioTrack = null
    }
}
