package io.dayd.bebop.video

/**
 * Utilitaire de parsing H.264 Annex-B.
 *
 * Scanne les start codes (`00 00 01` ou `00 00 00 01`) et localise chaque NAL unit.
 * Pour chaque NAL, le premier octet contient le `nal_unit_type` dans les 5 bits faibles
 * (forbidden_zero_bit + nal_ref_idc + nal_unit_type). Types utiles :
 *   1 = slice non-IDR
 *   5 = slice IDR (keyframe)
 *   7 = SPS
 *   8 = PPS
 */
object H264Nal {
    const val TYPE_IDR = 5
    const val TYPE_SPS = 7
    const val TYPE_PPS = 8

    /** Décrit un NAL unit dans le buffer : type + bornes (start code inclus dans `start`). */
    data class Unit(val type: Int, val start: Int, val end: Int)

    /** Scanne `data` et renvoie les NAL units trouvés, dans l'ordre. */
    fun scan(data: ByteArray): List<Unit> {
        val starts = findStartCodes(data)
        if (starts.isEmpty()) return emptyList()
        val out = ArrayList<Unit>(starts.size)
        for (i in starts.indices) {
            val (scStart, scLen) = starts[i]
            val payloadStart = scStart + scLen
            if (payloadStart >= data.size) continue
            val nalType = data[payloadStart].toInt() and 0x1f
            val end = if (i + 1 < starts.size) starts[i + 1].first else data.size
            out += Unit(nalType, scStart, end)
        }
        return out
    }

    /** Renvoie true si une frame Annex-B contient un slice IDR (NAL type 5). */
    fun containsIdr(data: ByteArray): Boolean = scan(data).any { it.type == TYPE_IDR }

    /**
     * Extrait SPS et PPS d'une frame Annex-B. Le payload renvoyé inclut le start code
     * `00 00 00 01` — c'est le format attendu par MediaCodec pour csd-0/csd-1 sur
     * un format `video/avc` Annex-B.
     */
    fun extractSpsPps(data: ByteArray): Pair<ByteArray, ByteArray>? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (u in scan(data)) {
            val payloadStart = u.start + leadingZeroes(data, u.start)
            val payloadLen = u.end - payloadStart
            if (payloadLen <= 0) continue
            val buf = ByteArray(4 + payloadLen)
            buf[0] = 0; buf[1] = 0; buf[2] = 0; buf[3] = 1
            System.arraycopy(data, payloadStart, buf, 4, payloadLen)
            when (u.type) {
                TYPE_SPS -> if (sps == null) sps = buf
                TYPE_PPS -> if (pps == null) pps = buf
            }
            if (sps != null && pps != null) break
        }
        return if (sps != null && pps != null) sps!! to pps!! else null
    }

    /** Longueur du start code à `pos` : 3 (`00 00 01`) ou 4 (`00 00 00 01`). */
    private fun leadingZeroes(data: ByteArray, pos: Int): Int {
        if (pos + 3 < data.size &&
            data[pos].toInt() == 0 && data[pos + 1].toInt() == 0 &&
            data[pos + 2].toInt() == 0 && data[pos + 3].toInt() == 1
        ) return 4
        return 3
    }

    /** Liste des (offset, length) des start codes dans `data`. */
    private fun findStartCodes(data: ByteArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    out += i to 3
                    i += 3
                    continue
                }
                if (i + 3 < data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    out += i to 4
                    i += 4
                    continue
                }
            }
            i++
        }
        return out
    }
}
