package com.ace4.airplayreceiver.raop

import java.io.IOException
import java.io.InputStream

data class RtspRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * Reads RTSP request lines/headers byte-by-byte directly off the socket's raw
 * InputStream (no BufferedReader) so that once headers end, the very next
 * bytes read are the start of the Content-Length body - a BufferedReader
 * would over-read into the body while looking ahead for line breaks.
 */
class RtspLineReader(private val input: InputStream) {

    fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    fun readNBytes(n: Int): ByteArray {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buf, offset, n - offset)
            if (read == -1) break
            offset += read
        }
        return buf
    }
}

object RtspProtocol {

    @Throws(IOException::class)
    fun parseRequest(reader: RtspLineReader): RtspRequest? {
        val requestLine = reader.readLine() ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(" ")
        if (parts.size < 3) throw IOException("Malformed RTSP request line: $requestLine")
        val method = parts[0]
        val path = parts[1]

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }

        val contentLength = headers.entries
            .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) reader.readNBytes(contentLength) else ByteArray(0)

        return RtspRequest(method, path, headers, body)
    }

    fun buildResponse(
        status: Int,
        reason: String,
        cseq: String?,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0)
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 ").append(status).append(' ').append(reason).append("\r\n")
        if (cseq != null) sb.append("CSeq: ").append(cseq).append("\r\n")
        for ((key, value) in headers) sb.append(key).append(": ").append(value).append("\r\n")
        if (body.isNotEmpty()) sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.US_ASCII) + body
    }
}
