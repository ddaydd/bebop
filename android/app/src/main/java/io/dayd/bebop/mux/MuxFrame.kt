package io.dayd.bebop.mux

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MuxFrame(val chanid: Int, val payload: ByteArray) {

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.putInt(chanid)
        buf.putInt(HEADER_SIZE + payload.size)
        buf.put(payload)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MuxFrame) return false
        return chanid == other.chanid && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * chanid + payload.contentHashCode()

    companion object {
        const val HEADER_SIZE = 12
        val MAGIC = byteArrayOf(0x4d, 0x55, 0x58, 0x21)
    }
}
