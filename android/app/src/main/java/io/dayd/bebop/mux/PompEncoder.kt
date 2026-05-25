package io.dayd.bebop.mux

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodeur POMP minimal — couvre ce qu'on utilise pour parler à libmux côté SC2.
 * Encodages : header magic + msgid + size LE, args = tag + valeur typée.
 * Référence : `libpomp/src/pomp_encoder.c`.
 */
class PompEncoder {

    private val args = ByteArrayOutputStream()

    fun i8(v: Int): PompEncoder { args.write(0x01); args.write(v and 0xff); return this }
    fun u8(v: Int): PompEncoder { args.write(0x02); args.write(v and 0xff); return this }

    fun i16(v: Int): PompEncoder {
        args.write(0x03)
        args.write(v and 0xff); args.write((v ushr 8) and 0xff)
        return this
    }
    fun u16(v: Int): PompEncoder {
        args.write(0x04)
        args.write(v and 0xff); args.write((v ushr 8) and 0xff)
        return this
    }

    fun i32(v: Int): PompEncoder {
        args.write(0x05)
        val zz = ((v.toLong() shl 1) xor (v.toLong() shr 31)) and 0xffffffffL
        writeVarint(zz)
        return this
    }
    fun u32(v: Long): PompEncoder {
        args.write(0x06)
        writeVarint(v)
        return this
    }

    fun str(s: String): PompEncoder {
        args.write(0x09)
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeVarint((bytes.size + 1).toLong())
        args.write(bytes, 0, bytes.size)
        args.write(0)
        return this
    }

    fun build(msgid: Int): ByteArray {
        val argsBytes = args.toByteArray()
        val total = HEADER + argsBytes.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.put(MAGIC)
        out.putInt(msgid)
        out.putInt(total)
        out.put(argsBytes)
        return out.array()
    }

    private fun writeVarint(v: Long) {
        var x = v
        while (true) {
            val b = (x and 0x7fL).toInt()
            x = x ushr 7
            if (x == 0L) { args.write(b); return }
            args.write(b or 0x80)
        }
    }

    companion object {
        private const val HEADER = 12
        private val MAGIC = byteArrayOf(0x50, 0x4f, 0x4d, 0x50)
    }
}
