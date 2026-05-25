package io.dayd.bebop.video

/**
 * Dépéqueteur RTP → H.264 Annex-B (RFC 6184).
 *
 * Format RTP minimal (RFC 3550 §5.1) — header 12 B fixe :
 *   byte 0 : V(2) P(1) X(1) CC(4)
 *   byte 1 : M(1) PT(7)
 *   2-3    : seq (u16 BE)
 *   4-7    : timestamp (u32 BE)
 *   8-11   : SSRC (u32 BE)
 *   suivi de CSRC[CC] (4 B chacun) + extension optionnelle (X) + payload.
 *
 * Le payload H.264 (RFC 6184) commence par un byte NAL "type" sur 5 bits :
 *   - 1..23 : NAL unit single (le payload entier est une NALU sans start code)
 *   - 24 STAP-A : agrégation de plusieurs NALU préfixées par u16 BE size
 *   - 28 FU-A  : NALU fragmentée en plusieurs paquets RTP, header FU 1B
 *                (S, E, R, type[5b]) + payload partiel
 *
 * Sortie : NALU(s) en Annex-B (préfixées par `00 00 00 01`). Le H264Decoder
 * sait accepter ce format (extractSpsPps cherche les start codes).
 */
class RtpDepayloader(private val onNal: (data: ByteArray, marker: Boolean) -> Unit) {

    private val fuBuffer = ArrayList<Byte>(64 * 1024)
    private var fuNalHeader: Int = 0
    private var assembling = false
    @Volatile var packetsIn: Long = 0
        private set
    @Volatile var packetsDropped: Long = 0
        private set
    @Volatile var nalsOut: Long = 0
        private set
    @Volatile var lastSeq: Int = -1
        private set

    fun feed(packet: ByteArray) {
        packetsIn++
        if (packet.size < 12) { packetsDropped++; return }

        val b0 = packet[0].toInt() and 0xff
        val b1 = packet[1].toInt() and 0xff
        val version = b0 ushr 6
        if (version != 2) { packetsDropped++; return }
        val padding = (b0 and 0x20) != 0
        val extension = (b0 and 0x10) != 0
        val cc = b0 and 0x0f
        val marker = (b1 and 0x80) != 0
        val seq = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
        lastSeq = seq

        var off = 12 + 4 * cc
        if (extension) {
            if (off + 4 > packet.size) { packetsDropped++; return }
            val extLen = ((packet[off + 2].toInt() and 0xff) shl 8) or (packet[off + 3].toInt() and 0xff)
            off += 4 + 4 * extLen
        }
        var endExclusive = packet.size
        if (padding && endExclusive > off) {
            val padLen = packet[endExclusive - 1].toInt() and 0xff
            endExclusive -= padLen
        }
        if (off >= endExclusive) { packetsDropped++; return }

        val nalHeader = packet[off].toInt() and 0xff
        val nalType = nalHeader and 0x1f

        when (nalType) {
            in 1..23 -> emitNal(packet, off, endExclusive - off, marker)
            24 -> emitStapA(packet, off + 1, endExclusive - off - 1, marker)
            28 -> handleFuA(packet, off, endExclusive - off, marker, nalHeader)
            else -> packetsDropped++
        }
    }

    private fun emitNal(src: ByteArray, off: Int, len: Int, marker: Boolean) {
        val out = ByteArray(4 + len)
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1
        System.arraycopy(src, off, out, 4, len)
        onNal(out, marker)
        nalsOut++
    }

    private fun emitStapA(src: ByteArray, off: Int, totalLen: Int, marker: Boolean) {
        var p = off
        val end = off + totalLen
        while (p + 2 <= end) {
            val nalLen = ((src[p].toInt() and 0xff) shl 8) or (src[p + 1].toInt() and 0xff)
            p += 2
            if (nalLen <= 0 || p + nalLen > end) { packetsDropped++; return }
            emitNal(src, p, nalLen, marker && p + nalLen == end)
            p += nalLen
        }
    }

    private fun handleFuA(src: ByteArray, off: Int, len: Int, marker: Boolean, fuIndicator: Int) {
        if (len < 2) { packetsDropped++; return }
        val fuHeader = src[off + 1].toInt() and 0xff
        val start = (fuHeader and 0x80) != 0
        val end = (fuHeader and 0x40) != 0
        val fragType = fuHeader and 0x1f

        if (start) {
            fuBuffer.clear()
            // NAL header reconstitué : (FUindicator & 0xE0) | fragType
            fuNalHeader = (fuIndicator and 0xe0) or fragType
            fuBuffer.add(fuNalHeader.toByte())
            assembling = true
        }
        if (!assembling) { packetsDropped++; return }
        for (i in (off + 2) until (off + len)) fuBuffer.add(src[i])
        if (end) {
            val out = ByteArray(4 + fuBuffer.size)
            out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1
            for (i in fuBuffer.indices) out[4 + i] = fuBuffer[i]
            onNal(out, marker)
            nalsOut++
            fuBuffer.clear()
            assembling = false
        }
    }
}
