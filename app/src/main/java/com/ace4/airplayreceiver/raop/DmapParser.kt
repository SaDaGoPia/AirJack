package com.ace4.airplayreceiver.raop

/**
 * Minimal parser for the flat DMAP-tagged metadata blocks RAOP sends via
 * SET_PARAMETER (Content-Type: application/x-dmap-tagged). Real DAAP/DMAP
 * supports arbitrarily nested container tags, but shairport-sync's own
 * parser (rtsp.c, handle_set_parameter_metadata) treats the whole thing as
 * one flat (tag, length, value) sequence after an 8-byte header it just
 * skips - that's the only shape RAOP metadata actually uses in practice, so
 * this mirrors that instead of implementing a full recursive DMAP parser.
 */
object DmapParser {

    // dmap.itemname / daap.songartist / daap.songalbum
    private const val TAG_TITLE = "minm"
    private const val TAG_ARTIST = "asar"
    private const val TAG_ALBUM = "asal"

    data class Fields(val title: String?, val artist: String?, val album: String?)

    fun parse(content: ByteArray): Fields {
        var title: String? = null
        var artist: String? = null
        var album: String? = null

        var offset = 8 // skip the outer container tag+length shairport-sync also skips
        while (offset + 8 <= content.size) {
            val tag = String(content, offset, 4, Charsets.US_ASCII)
            offset += 4
            val length = ((content[offset].toInt() and 0xFF) shl 24) or
                ((content[offset + 1].toInt() and 0xFF) shl 16) or
                ((content[offset + 2].toInt() and 0xFF) shl 8) or
                (content[offset + 3].toInt() and 0xFF)
            offset += 4
            if (length < 0 || offset + length > content.size) break

            when (tag) {
                TAG_TITLE -> title = String(content, offset, length, Charsets.UTF_8)
                TAG_ARTIST -> artist = String(content, offset, length, Charsets.UTF_8)
                TAG_ALBUM -> album = String(content, offset, length, Charsets.UTF_8)
            }
            offset += length
        }
        return Fields(title, artist, album)
    }
}
