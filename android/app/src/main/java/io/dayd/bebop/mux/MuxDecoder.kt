package io.dayd.bebop.mux

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Décodeur streaming pour le protocole libmux. Les bytes arrivent en chunks arbitraires
 * via `feed()`. Resync sur le magic en cas de désynchronisation.
 *
 * Limites de sécurité : payload borné par [maxPayload] pour éviter d'allouer si le
 * `size` est aberrant (octets parasites ou drift).
 */
class MuxDecoder(private val maxPayload: Int = 1 shl 20) {

    private val buffer = ArrayDeque<Byte>()
    var resyncs: Int = 0
        private set

    fun feed(data: ByteArray, length: Int = data.size): List<MuxFrame> {
        val out = mutableListOf<MuxFrame>()
        for (i in 0 until length) buffer.addLast(data[i])

        while (true) {
            if (!alignToMagic()) return out
            if (buffer.size < MuxFrame.HEADER_SIZE) return out

            val header = ByteArray(MuxFrame.HEADER_SIZE)
            for (i in 0 until MuxFrame.HEADER_SIZE) header[i] = buffer[i]

            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(4)
            val chanid = bb.int
            val size = bb.int

            if (size < MuxFrame.HEADER_SIZE || size - MuxFrame.HEADER_SIZE > maxPayload) {
                buffer.removeFirst()
                resyncs++
                continue
            }
            if (buffer.size < size) return out

            repeat(MuxFrame.HEADER_SIZE) { buffer.removeFirst() }
            val payload = ByteArray(size - MuxFrame.HEADER_SIZE)
            for (i in payload.indices) payload[i] = buffer.removeFirst()
            out += MuxFrame(chanid, payload)
        }
    }

    private fun alignToMagic(): Boolean {
        while (buffer.size >= 4) {
            if (buffer[0] == MuxFrame.MAGIC[0] &&
                buffer[1] == MuxFrame.MAGIC[1] &&
                buffer[2] == MuxFrame.MAGIC[2] &&
                buffer[3] == MuxFrame.MAGIC[3]
            ) return true
            buffer.removeFirst()
            resyncs++
        }
        return false
    }
}
