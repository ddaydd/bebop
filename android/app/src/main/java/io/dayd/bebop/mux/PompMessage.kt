package io.dayd.bebop.mux

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface PompArg {
    data class I8(val value: Byte) : PompArg
    data class U8(val value: Short) : PompArg
    data class I16(val value: Short) : PompArg
    data class U16(val value: Int) : PompArg
    data class I32(val value: Int) : PompArg
    data class U32(val value: Long) : PompArg
    data class I64(val value: Long) : PompArg
    data class U64(val value: Long) : PompArg
    data class Str(val value: String) : PompArg
    data class Buf(val value: ByteArray) : PompArg {
        override fun equals(other: Any?) = other is Buf && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode()
    }
    data class F32(val value: Float) : PompArg
    data class F64(val value: Double) : PompArg
    data class Fd(val value: Int) : PompArg
}

data class PompMessage(val msgid: Int, val args: List<PompArg>) {

    companion object {
        const val HEADER_SIZE = 12
        val MAGIC = byteArrayOf(0x50, 0x4f, 0x4d, 0x50)

        private const val T_I8 = 0x01
        private const val T_U8 = 0x02
        private const val T_I16 = 0x03
        private const val T_U16 = 0x04
        private const val T_I32 = 0x05
        private const val T_U32 = 0x06
        private const val T_I64 = 0x07
        private const val T_U64 = 0x08
        private const val T_STR = 0x09
        private const val T_BUF = 0x0a
        private const val T_F32 = 0x0b
        private const val T_F64 = 0x0c
        private const val T_FD = 0x0d

        fun decode(payload: ByteArray): PompMessage? {
            if (payload.size < HEADER_SIZE) return null
            if (payload[0] != MAGIC[0] || payload[1] != MAGIC[1] ||
                payload[2] != MAGIC[2] || payload[3] != MAGIC[3]) return null

            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(4)
            val msgid = bb.int
            val size = bb.int
            if (size > payload.size || size < HEADER_SIZE) return null

            val r = Reader(payload, HEADER_SIZE, size)
            val args = mutableListOf<PompArg>()
            while (r.remaining() > 0) {
                val tag = r.readU8Raw() ?: return null
                val arg = readArg(r, tag) ?: return null
                args += arg
            }
            return PompMessage(msgid, args)
        }

        private fun readArg(r: Reader, tag: Int): PompArg? = when (tag) {
            T_I8 -> r.readByte()?.let { PompArg.I8(it) }
            T_U8 -> r.readU8Raw()?.let { PompArg.U8(it.toShort()) }
            T_I16 -> r.readI16LE()?.let { PompArg.I16(it) }
            T_U16 -> r.readI16LE()?.let { PompArg.U16(it.toInt() and 0xffff) }
            T_I32 -> r.readVarint()?.let { PompArg.I32(zigzagDecode32(it).toInt()) }
            T_U32 -> r.readVarint()?.let { PompArg.U32(it and 0xffffffffL) }
            T_I64 -> r.readVarint()?.let { PompArg.I64(zigzagDecode64(it)) }
            T_U64 -> r.readVarint()?.let { PompArg.U64(it) }
            T_STR -> {
                val len = r.readVarint()?.toInt() ?: return null
                val bytes = r.readBytes(len) ?: return null
                val str = if (bytes.isNotEmpty() && bytes.last() == 0.toByte())
                    String(bytes, 0, bytes.size - 1, Charsets.UTF_8)
                else String(bytes, Charsets.UTF_8)
                PompArg.Str(str)
            }
            T_BUF -> {
                val len = r.readVarint()?.toInt() ?: return null
                r.readBytes(len)?.let { PompArg.Buf(it) }
            }
            T_F32 -> r.readI32LE()?.let { PompArg.F32(Float.fromBits(it)) }
            T_F64 -> r.readI64LE()?.let { PompArg.F64(Double.fromBits(it)) }
            T_FD -> r.readVarint()?.let { PompArg.Fd(it.toInt()) }
            else -> null
        }

        private fun zigzagDecode32(v: Long): Long {
            val u = v.toInt()
            return ((u ushr 1) xor -(u and 1)).toLong()
        }

        private fun zigzagDecode64(v: Long): Long = (v ushr 1) xor -(v and 1L)
    }

    private class Reader(val buf: ByteArray, start: Int, val end: Int) {
        var pos: Int = start
        fun remaining(): Int = end - pos
        fun readU8Raw(): Int? = if (pos >= end) null else (buf[pos++].toInt() and 0xff)
        fun readByte(): Byte? = if (pos >= end) null else buf[pos++]
        fun readI16LE(): Short? {
            if (pos + 2 > end) return null
            val v = ((buf[pos].toInt() and 0xff) or ((buf[pos + 1].toInt() and 0xff) shl 8)).toShort()
            pos += 2
            return v
        }
        fun readI32LE(): Int? {
            if (pos + 4 > end) return null
            val v = (buf[pos].toInt() and 0xff) or
                    ((buf[pos + 1].toInt() and 0xff) shl 8) or
                    ((buf[pos + 2].toInt() and 0xff) shl 16) or
                    ((buf[pos + 3].toInt() and 0xff) shl 24)
            pos += 4
            return v
        }
        fun readI64LE(): Long? {
            if (pos + 8 > end) return null
            var v = 0L
            for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xff) shl (i * 8))
            pos += 8
            return v
        }
        fun readVarint(): Long? {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end) return null
                val b = buf[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
                if (shift > 63) return null
            }
        }
        fun readBytes(n: Int): ByteArray? {
            if (n < 0 || pos + n > end) return null
            val out = ByteArray(n)
            System.arraycopy(buf, pos, out, 0, n)
            pos += n
            return out
        }
    }
}
