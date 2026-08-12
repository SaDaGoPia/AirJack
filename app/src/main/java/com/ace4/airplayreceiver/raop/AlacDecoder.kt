package com.ace4.airplayreceiver.raop

import com.beatofthedrum.alacdecoder.AlacDecodeUtils
import com.beatofthedrum.alacdecoder.AlacFile

/**
 * Wraps the vendored pure-Java ALAC decoder (com.beatofthedrum.alacdecoder,
 * ported from soiaf/Java-Apple-Lossless-decoder) to decode the raw ALAC audio
 * frames RAOP delivers - each RTP packet's (decrypted) payload is exactly one
 * ALAC frame.
 *
 * Decoder parameters come straight from the ANNOUNCE SDP's `a=fmtp:` line,
 * which for classic AirPlay is Apple's ALACSpecificConfig fields as
 * space-separated decimal integers:
 * <rtpPayloadType> frameLength compatibleVersion bitDepth pb mb kb
 * numChannels maxRun maxFrameBytes avgBitRate sampleRate
 */
class AlacDecoder(fmtp: String) {

    val frameLength: Int
    val numChannels: Int
    val sampleRate: Int

    private val alac: AlacFile
    private val outBuffer: IntArray

    init {
        val f = fmtp.trim().split(Regex("\\s+")).map { it.toInt() }
        // f[0] is the RTP payload type (96) - not part of ALACSpecificConfig, skip it.
        frameLength = f[1]
        val compatibleVersion = f[2]
        val bitDepth = f[3]
        val pb = f[4]
        val mb = f[5]
        val kb = f[6]
        numChannels = f[7]
        val maxRun = f[8]
        val maxFrameBytes = f[9]
        val avgBitRate = f[10]
        sampleRate = f[11]

        alac = AlacDecodeUtils.create_alac(bitDepth, numChannels)
        AlacDecodeUtils.alac_set_info(
            alac,
            buildMagicCookie(
                frameLength, compatibleVersion, bitDepth, pb, mb, kb,
                numChannels, maxRun, maxFrameBytes, avgBitRate, sampleRate
            )
        )
        outBuffer = IntArray(frameLength * numChannels)
    }

    /** Decodes one ALAC frame to interleaved 16-bit PCM samples (left, right, left, right, ...). */
    fun decode(alacFrame: ByteArray): ShortArray {
        // readbits_16() always speculatively reads 3 bytes ahead of the current
        // bit position, even when fewer bits are actually needed, then shifts
        // out the unused ones - a standard bit-reader trick. Near the end of
        // the buffer that overreads past the real frame data. The original C
        // decoder this was ported from got away with it because callers always
        // passed an over-allocated buffer; ours is exactly RTP-payload-sized,
        // so pad a few zero bytes on so the speculative read never runs off
        // the end of the array (the extra bits are discarded, never decoded).
        val padded = alacFrame.copyOf(alacFrame.size + 8)
        val outputBytes = AlacDecodeUtils.decode_frame(alac, padded, outBuffer, 0)
        val sampleCount = outputBytes / 2 // 16-bit samples, interleaved across channels
        return ShortArray(sampleCount) { i -> outBuffer[i].toShort() }
    }

    companion object {
        /**
         * alac_set_info() expects the byte layout of an MP4 "alac" atom -
         * 24 bytes of container framing it skips over (size/frma/alac/size/
         * alac/reserved), followed by the ALACSpecificConfig fields it
         * actually reads. We don't have a real MP4 container (RAOP sends the
         * config via SDP fmtp instead), so the leading 24 bytes are just
         * zero-filled padding to match the offsets the parser expects.
         */
        private fun buildMagicCookie(
            frameLength: Int, compatibleVersion: Int, bitDepth: Int,
            pb: Int, mb: Int, kb: Int, numChannels: Int,
            maxRun: Int, maxFrameBytes: Int, avgBitRate: Int, sampleRate: Int
        ): IntArray {
            val bytes = ArrayList<Int>(48)
            repeat(24) { bytes.add(0) }
            bytes.addBE32(frameLength)
            bytes.add(compatibleVersion and 0xFF)
            bytes.add(bitDepth and 0xFF)
            bytes.add(pb and 0xFF)
            bytes.add(mb and 0xFF)
            bytes.add(kb and 0xFF)
            bytes.add(numChannels and 0xFF)
            bytes.addBE16(maxRun)
            bytes.addBE32(maxFrameBytes)
            bytes.addBE32(avgBitRate)
            bytes.addBE32(sampleRate)
            return bytes.toIntArray()
        }

        private fun ArrayList<Int>.addBE16(value: Int) {
            add((value shr 8) and 0xFF)
            add(value and 0xFF)
        }

        private fun ArrayList<Int>.addBE32(value: Int) {
            add((value shr 24) and 0xFF)
            add((value shr 16) and 0xFF)
            add((value shr 8) and 0xFF)
            add(value and 0xFF)
        }
    }
}
