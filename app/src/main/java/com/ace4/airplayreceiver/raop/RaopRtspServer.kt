package com.ace4.airplayreceiver.raop

import android.graphics.BitmapFactory
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
 * requests retransmission of lost packets over the control channel, and
 * applies iOS-sent volume changes locally (both the actual playback gain and
 * the system "media volume" indicator, kept in sync for a coherent UI).
 * Does NOT implement NTP-style clock sync (the timing channel) - that's
 * mainly for long-session clock drift correction between sender and
 * receiver, which basic local playback here doesn't depend on; skipped as
 * disproportionate effort for this single-device setup (see decisions doc).
 * Also does NOT push local volume changes back to iOS via DACP - tried and
 * reverted: iOS consistently rejected the request with 400 Bad Request. DACP
 * is an undocumented, reverse-engineered protocol, and there's independent
 * evidence Apple has been tightening programmatic volume control on iOS in
 * general, so this looks like a real platform restriction rather than a bug
 * in our request (see decisions doc).
 */
class RaopRtspServer(
    private val deviceIdHex: String,
    private val audioManager: AudioManager,
    private val onNowPlayingChanged: (NowPlayingInfo) -> Unit = {}
) {

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
                    val handler = RaopConnectionHandler(socket, deviceIdHex, audioManager, onNowPlayingChanged)
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
    private val deviceIdHex: String,
    private val audioManager: AudioManager,
    private val onNowPlayingChanged: (NowPlayingInfo) -> Unit
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
    private var nowPlaying = NowPlayingInfo()
    private var playbackStarted = false
    private var primeThresholdBytes = 0
    private var bytesWrittenSincePrime = 0
    private val pendingResends = LinkedHashMap<Int, Long>() // seqno -> requested-at (ms), awaiting arrival or retry
    private val retriedResends = HashSet<Int>() // seqnos already retried once - don't chase forever

    companion object {
        /** Bigger gaps than this are treated as a stream restart/reorder, not loss - don't chase them. */
        private const val MAX_RESEND_GAP = 32
        /** How long to wait for a resend before assuming the request or its reply was also lost. */
        private const val RESEND_RETRY_MS = 200L
        /** Extra linear gain applied to decoded PCM, on top of iOS's own volume - see applyBoost(). ~+3dB. */
        private const val BOOST_GAIN = 1.41f
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
                "SET_PARAMETER" -> handleSetParameter(req, cseq)
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
            // flush() discards any buffered-but-unplayed data - which,
            // before playbackStarted, includes whatever primePlayback() had
            // already counted toward its threshold. Without this, priming
            // could fire play() on a buffer that's actually empty again.
            if (!playbackStarted) bytesWrittenSincePrime = 0
        } catch (_: Exception) {
            // no-op if the track isn't in a state that allows flushing
        }
        return RtspProtocol.buildResponse(200, "OK", cseq)
    }

    /**
     * iOS pushes track metadata, artwork, and volume/progress as separate
     * SET_PARAMETER requests during playback, distinguished by Content-Type:
     * application/x-dmap-tagged (title/artist/album), an image type like
     * image/jpeg (cover art), or text/parameters (volume/progress key-value
     * text, e.g. "volume: -12.5").
     */
    private fun handleSetParameter(req: RtspRequest, cseq: String?): ByteArray {
        val contentType = req.header("Content-Type")
        Log.d(TAG, "SET_PARAMETER Content-Type=$contentType, body=${req.body.size} bytes")
        when {
            contentType == null -> {}
            contentType.startsWith("application/x-dmap-tagged") -> {
                val fields = DmapParser.parse(req.body)
                // Text metadata and artwork arrive as separate SET_PARAMETER
                // requests with no guaranteed order - different source apps
                // send them differently (observed: YT Music sends artwork
                // *before* the text). Keep whatever artwork we already have
                // rather than assuming this text update always comes first;
                // worst case a new track briefly shows the previous cover
                // until its own artwork arrives, which is far less bad than
                // discarding artwork we just successfully decoded.
                nowPlaying = nowPlaying.copy(title = fields.title, artist = fields.artist, album = fields.album)
                Log.i(TAG, "Now playing: ${fields.title} - ${fields.artist} (${fields.album})")
                onNowPlayingChanged(nowPlaying)
            }
            contentType.startsWith("image") -> {
                val bitmap = try {
                    BitmapFactory.decodeByteArray(req.body, 0, req.body.size)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode artwork", e)
                    null
                }
                if (bitmap != null) {
                    Log.i(TAG, "Artwork decoded: ${bitmap.width}x${bitmap.height}")
                    nowPlaying = nowPlaying.copy(artwork = bitmap)
                    onNowPlayingChanged(nowPlaying)
                } else {
                    Log.w(TAG, "Artwork Content-Type but decodeByteArray returned null (${req.body.size} bytes)")
                }
            }
            contentType.startsWith("text/parameters") -> handleSetParameterText(req)
            else -> Log.d(TAG, "Unhandled SET_PARAMETER Content-Type: $contentType")
        }
        return RtspProtocol.buildResponse(200, "OK", cseq)
    }

    /**
     * "volume: X" carries iOS's desired output level as dB attenuation -
     * 0.0 is unity gain (max), negative values attenuate down to iOS's own
     * floor around -30.0 dB, and -144.0 (or anything very negative) means
     * mute.
     *
     * Applied via AudioTrack.setStereoVolume using the standard (exponential)
     * 10^(dB/20) dB-to-linear conversion - the perceptually-correct curve for
     * loudness, and the API19-era volume control (the single-argument
     * setVolume(float) needs API 21+).
     *
     * This used to *also* mirror the same value into
     * AudioManager.setStreamVolume(), to keep Android's on-screen "media
     * volume" indicator in sync. Removed: AudioFlinger applies track volume
     * and stream volume multiplicatively, so doing both at once compounded -
     * at anything below iOS's max volume, real output was quieter than
     * either value alone implied. STREAM_MUSIC is now pinned to max once at
     * session start (see createAudioTrack()) and left alone, so this is the
     * only thing controlling real attenuation. The on-screen slider no
     * longer tracks iOS's position; matching what's actually audible was
     * judged more important than that cosmetic sync.
     */
    private fun handleSetParameterText(req: RtspRequest) {
        val text = String(req.body, Charsets.US_ASCII)
        for (line in text.split("\r\n", "\n")) {
            if (!line.startsWith("volume:")) continue
            val db = line.removePrefix("volume:").trim().toFloatOrNull() ?: continue
            val gain = if (db <= -144f) 0f else Math.pow(10.0, (db / 20.0)).toFloat().coerceIn(0f, 1f)
            try {
                @Suppress("DEPRECATION") // setVolume(float) needs API 21+
                audioTrack?.setStereoVolume(gain, gain)
                Log.i(TAG, "SET_PARAMETER: volume $db dB -> gain $gain")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply volume", e)
            }
        }
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
        // Don't play() yet - this device's low-power "deep-buffer-playback"
        // HAL path underruns almost immediately if told to start consuming
        // from an empty buffer (confirmed on real hardware: a BUFFER TIMEOUT
        // ~390ms after every fresh RECORD, before decode/network had queued
        // enough real audio to keep up). play() is deferred to primePlayback()
        // below, once half a buffer's worth of real decoded audio is already
        // queued, so the HAL never starts short.
        playbackStarted = false
        bytesWrittenSincePrime = 0
        primeThresholdBytes = bufferSize / 2

        // Pinned once at session start, not modulated per volume change
        // anymore - see the comment on handleSetParameterText() for why.
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0
        )
        return track
    }

    /** Starts playback once enough real decoded audio has been written to
     *  the track to survive the HAL's own startup latency without running
     *  dry - see the comment in createAudioTrack(). No-op after the first
     *  time it fires for a given AudioTrack. */
    private fun primePlayback(writtenBytes: Int) {
        if (playbackStarted) return
        bytesWrittenSincePrime += writtenBytes
        if (bytesWrittenSincePrime >= primeThresholdBytes) {
            audioTrack?.play()
            playbackStarted = true
        }
    }

    private fun startAudioReceiver(socket: DatagramSocket) {
        val thread = Thread({
            // Reduce the odds of another app's background work (this is a
            // 1GB RAM device) preempting the receive/decode/write path for
            // long enough to starve AudioTrack - a CPU-side stutter cause
            // independent of anything happening on the network.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
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
        pendingResends.remove(seqno)
        if (payloadType == 0x60) trackSequence(seqno)
        retryStaleResends()

        val decoder = alacDecoder ?: return
        val payload = data.copyOfRange(headerOffset + 12, length)
        val key = aesKey
        val iv = aesIv
        val alacFrame = if (key != null && iv != null) decryptAudio(payload, key, iv) else payload

        try {
            val pcm = decoder.decode(alacFrame)
            applyBoost(pcm)
            audioTrack?.write(pcm, 0, pcm.size)
            primePlayback(pcm.size * 2) // decode() returns samples (2 bytes each); primeThresholdBytes counts bytes
        } catch (e: Exception) {
            Log.e(TAG, "ALAC decode/playback failed", e)
        }
    }

    /**
     * Extra headroom beyond what iOS's own volume alone provides, since a
     * lot of real-world usage sits well under max iPhone volume for
     * comfortable listening and this receiver has no other way to push
     * output louder than unity gain (AudioTrack.setStereoVolume is
     * platform-clamped to 1.0, so any boost has to happen here, in the PCM
     * domain, before the data ever reaches AudioTrack).
     *
     * +3dB (~1.41x) was picked deliberately conservative: loud hard-clipping
     * a whole waveform sounds much worse than the quiet it's fixing, and
     * hip-hop/reggaeton-style hot masters (common in real usage here) have
     * little headroom left to boost into before clipping. A plain multiply +
     * hard clamp, not a soft-knee limiter - a real limiter needs a
     * per-sample transcendental function, real CPU cost on a Snapdragon 410
     * this project just finished protecting from CPU contention. At a
     * conservative gain like this, hard-clamping should be rare enough in
     * practice not to be worth that cost.
     */
    private fun applyBoost(pcm: ShortArray) {
        for (i in pcm.indices) {
            val boosted = (pcm[i] * BOOST_GAIN).toInt()
            pcm[i] = boosted.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
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
            val now = System.currentTimeMillis()
            for (i in 0 until count) {
                pendingResends[(firstMissingSeqno + i) and 0xFFFF] = now
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send resend request: ${e.message}")
        }
    }

    /**
     * A resend request (or its reply) can itself be lost, especially on a
     * lossier/mobile link - without this, that packet is just silently gone
     * for good even though the whole point of resending was to recover it.
     * Retried at most once per seqno; if the retry doesn't arrive either,
     * it's given up on rather than chased indefinitely.
     */
    private fun retryStaleResends() {
        if (pendingResends.isEmpty()) return
        val now = System.currentTimeMillis()
        val stale = pendingResends.entries.filter { now - it.value >= RESEND_RETRY_MS }.map { it.key }
        for (seqno in stale) {
            pendingResends.remove(seqno)
            if (retriedResends.add(seqno)) {
                sendResendRequest(seqno, 1)
            }
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
        if (!nowPlaying.isEmpty) {
            nowPlaying = NowPlayingInfo()
            onNowPlayingChanged(nowPlaying)
        }
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
