package io.dayd.bebop.arsdk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reader ARStream v1 (canal MUX 4 = arsdk-stream-data).
 * Source : `libARStream/Sources/ARSTREAM_Reader.c:382-595`.
 *
 * Format header 5 bytes :
 *   frameNumber       u16 LE
 *   frameFlags        u8  (bit 0 = FLUSH_FRAME)
 *   fragmentNumber    u8
 *   fragmentsPerFrame u8
 *
 * Fragments d'une même frame : copiés à offset fixe `fragmentNumber * maxFragmentSize`.
 * Frame complète quand tous les bits 0..fragmentsPerFrame-1 du bitfield sont set.
 * Le payload assemblé est du H.264 Annex-B (start codes 00 00 00 01 inline).
 */
class ArStreamReader(
    private val maxFragmentSize: Int = 65000,
    private val maxFragmentsPerFrame: Int = 128,
    private val onFrame: (frameNumber: Int, flags: Int, data: ByteArray) -> Unit,
    private val onAckUpdate: (frameNumber: Int, low: Long, high: Long) -> Unit,
) {
    private val frameBuf = ByteArray(maxFragmentSize * maxFragmentsPerFrame)
    private var currentFrameNumber = -1
    private var currentSize = 0
    private var lowBits = 0L
    private var highBits = 0L
    private var lastFragmentsPerFrame = 0

    fun feed(packet: ByteArray) {
        if (packet.size < HEADER_SIZE) return
        val frameNumber = (packet[0].toInt() and 0xff) or ((packet[1].toInt() and 0xff) shl 8)
        val flags = packet[2].toInt() and 0xff
        val fragNum = packet[3].toInt() and 0xff
        val fragsPerFrame = packet[4].toInt() and 0xff
        if (fragsPerFrame == 0 || fragsPerFrame > maxFragmentsPerFrame) return

        if (frameNumber != currentFrameNumber) {
            currentFrameNumber = frameNumber
            currentSize = 0
            lowBits = 0
            highBits = 0
            lastFragmentsPerFrame = fragsPerFrame
        }

        if (fragNum < 64) lowBits = lowBits or (1L shl fragNum)
        else if (fragNum < 128) highBits = highBits or (1L shl (fragNum - 64))

        val dataLen = packet.size - HEADER_SIZE
        val offset = fragNum * maxFragmentSize
        if (offset + dataLen > frameBuf.size) return
        System.arraycopy(packet, HEADER_SIZE, frameBuf, offset, dataLen)
        val end = offset + dataLen
        if (end > currentSize) currentSize = end

        onAckUpdate(frameNumber, lowBits, highBits)

        if (allBitsSet(fragsPerFrame)) {
            val frame = ByteArray(currentSize)
            System.arraycopy(frameBuf, 0, frame, 0, currentSize)
            onFrame(frameNumber, flags, frame)
            currentSize = 0
            lowBits = 0
            highBits = 0
        }
    }

    private fun allBitsSet(n: Int): Boolean {
        return if (n <= 64) {
            val mask = if (n == 64) -1L else (1L shl n) - 1L
            (lowBits and mask) == mask
        } else {
            val maskHigh = if (n - 64 == 64) -1L else (1L shl (n - 64)) - 1L
            lowBits == -1L && (highBits and maskHigh) == maskHigh
        }
    }

    companion object { const val HEADER_SIZE = 5 }
}

object ArStreamAck {
    const val SIZE = 18

    fun encode(frameNumber: Int, lowBits: Long, highBits: Long): ByteArray {
        val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(frameNumber.toShort())
        buf.putLong(highBits)
        buf.putLong(lowBits)
        return buf.array()
    }
}
