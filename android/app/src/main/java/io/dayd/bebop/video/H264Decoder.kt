package io.dayd.bebop.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Décodeur H.264 minimal pour le flux ARStream v1 du Bebop 2.
 *
 * MediaCodec est configuré paresseusement : on attend la première frame contenant
 * à la fois un SPS (NAL type 7) et un PPS (NAL type 8) pour les passer en
 * `csd-0` / `csd-1`. Les frames précédant cette config sont ignorées.
 *
 * Une fois configuré, le décodeur considère qu'une frame est keyframe quand elle
 * contient un slice IDR (NAL type 5), pas selon le flag FLUSH_FRAME d'ARStream
 * (qui n'a aucun rapport avec la structure H.264).
 */
class H264Decoder(private val surface: Surface) {

    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var firstFrameAt = 0L

    @Volatile var framesQueued: Long = 0
        private set

    @Volatile var configured: Boolean = false
        private set

    @Volatile var outputWidth: Int = 0
        private set

    @Volatile var outputHeight: Int = 0
        private set

    @Volatile var lastError: String? = null
        private set

    fun feed(data: ByteArray, @Suppress("UNUSED_PARAMETER") flushFlag: Boolean) {
        try {
            if (codec == null) {
                val spsPps = H264Nal.extractSpsPps(data) ?: return
                configureCodec(spsPps.first, spsPps.second)
            }
            val c = codec ?: return
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf: ByteBuffer = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                if (firstFrameAt == 0L) firstFrameAt = System.nanoTime() / 1000
                val pts = (System.nanoTime() / 1000) - firstFrameAt
                val isKey = H264Nal.containsIdr(data)
                val flags = if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                c.queueInputBuffer(idx, 0, data.size, pts, flags)
                framesQueued++
            }
            drain()
        } catch (t: Throwable) {
            lastError = t.message ?: t::class.java.simpleName
        }
    }

    private fun configureCodec(sps: ByteArray, pps: ByteArray) {
        val format = MediaFormat.createVideoFormat("video/avc", 1280, 720).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        }
        val c = MediaCodec.createDecoderByType("video/avc")
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        configured = true
    }

    private fun drain() {
        val c = codec ?: return
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val fmt = c.outputFormat
                outputWidth = runCatching { fmt.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
                outputHeight = runCatching { fmt.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
                continue
            }
            if (idx < 0) break
            c.releaseOutputBuffer(idx, true)
        }
    }

    fun release() {
        val c = codec
        codec = null
        configured = false
        runCatching { c?.stop() }
        runCatching { c?.release() }
    }
}
