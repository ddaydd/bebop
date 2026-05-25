package io.dayd.bebop.mux

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Message de contrôle libmux émis sur le canal 0. Format binaire fixe (32 bytes) :
 *   id (u32 LE) + chanid (u32 LE) + args[6] (6 × u32 LE)
 *
 * Source : `libmux/src/mux_priv.h` (struct mux_ctrl_msg).
 */
data class MuxCtrlMessage(val id: Int, val chanid: Int, val args: IntArray) {

    init { require(args.size == ARG_COUNT) { "args must be $ARG_COUNT u32" } }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(id)
        buf.putInt(chanid)
        for (a in args) buf.putInt(a)
        return buf.array()
    }

    fun name(): String = when (id) {
        ID_CHANNEL_OPEN -> "CHANNEL_OPEN"
        ID_CHANNEL_CLOSE -> "CHANNEL_CLOSE"
        ID_RESET -> "RESET"
        ID_HANDSHAKE -> "HANDSHAKE"
        else -> "id=$id"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MuxCtrlMessage) return false
        return id == other.id && chanid == other.chanid && args.contentEquals(other.args)
    }

    override fun hashCode(): Int = 31 * (31 * id + chanid) + args.contentHashCode()

    fun nameForDisplay(): String = name()

    companion object {
        const val SIZE = 32
        const val ARG_COUNT = 6

        // Enum complet : Parrot-Developers/libmux/libmux/src/mux_priv.h
        // (mux_ctrl_msg_id). Voir [[reference-libmux-ip-proxy]].
        const val ID_CHANNEL_OPEN = 0
        const val ID_CHANNEL_CLOSE = 1
        const val ID_RESET = 6
        const val ID_PROXY_RESOLVE_REQ = 9
        const val ID_PROXY_RESOLVE_REQ_ACK = 10
        const val ID_PROXY_REMOTE_UPDATE_REQ = 11
        const val ID_PROXY_REMOTE_UPDATE_REQ_ACK = 12
        const val ID_CHANNEL_IP_CONNECT = 13
        const val ID_CHANNEL_IP_DISCONNECT = 14
        const val ID_CHANNEL_IP_CONNECTED = 15
        const val ID_CHANNEL_IP_DISCONNECTED = 16
        const val ID_CHANNEL_IP_REQ_ACK = 17
        const val ID_CHANNEL_IP_ACK = 18
        const val ID_HANDSHAKE = 127

        const val PROTOCOL_VERSION = 2

        // Transport / application des IP proxies (mux_ip_proxy_transport,
        // mux_ip_proxy_application dans include/libmux.h).
        const val IP_TRANSPORT_TCP = 0
        const val IP_TRANSPORT_UDP = 1
        const val IP_APP_NONE = 0
        const val IP_APP_FTP = 1

        // mux_channel_type (mux_channel.h). Passé en args[0] d'un CHANNEL_OPEN
        // pour indiquer au peer s'il doit allouer un slave IP correspondant.
        const val CHANNEL_TYPE_NORMAL = 0
        const val CHANNEL_TYPE_IP_MASTER = 1
        const val CHANNEL_TYPE_IP_SLAVE = 2

        // Master/slave chanid : mux_channel.h
        const val MIN_MASTER_ID = 1024
        const val SLAVE_BIT: Int = 0x80000000.toInt()

        fun isSlaveChanid(chanid: Int): Boolean = (chanid and SLAVE_BIT) != 0
        fun masterIdToSlave(masterId: Int): Int = masterId or SLAVE_BIT
        fun slaveIdToMaster(slaveId: Int): Int = slaveId and 0x7fffffff

        fun decode(payload: ByteArray): MuxCtrlMessage? {
            if (payload.size < SIZE) return null
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val id = bb.int
            val chanid = bb.int
            val args = IntArray(ARG_COUNT) { bb.int }
            return MuxCtrlMessage(id, chanid, args)
        }

        fun handshake(isAck: Boolean): MuxCtrlMessage = MuxCtrlMessage(
            id = ID_HANDSHAKE,
            chanid = 0,
            args = intArrayOf(if (isAck) 1 else 0, PROTOCOL_VERSION, 0, 0, 0, 0),
        )

        fun channelOpen(targetChanid: Int, type: Int = CHANNEL_TYPE_NORMAL): MuxCtrlMessage = MuxCtrlMessage(
            id = ID_CHANNEL_OPEN,
            chanid = targetChanid,
            args = intArrayOf(type, 0, 0, 0, 0, 0),
        )

        /**
         * Demande au peer (SC2) d'ouvrir un proxy IP : il ouvrira un socket UDP/TCP
         * local vers `remoteIpv4Be:remotePort` et forwardera le trafic sur le canal
         * MUX `masterId` (et son slave `masterId | 0x80000000`).
         *
         * `remoteIpv4Be` doit être en network byte order. Pour 192.168.42.1 :
         *   ((192 shl 24) or (168 shl 16) or (42 shl 8) or 1) — pas de htonl runtime.
         * Mais struct mux_ctrl_msg est sérialisée en LE word-par-word, donc on passe
         * la valeur en host order (LE) qui correspond à la représentation native du
         * `uint32_t remoteaddr` côté C (qui contient déjà du network-byte-order si
         * la résolution s'est faite via inet_pton ; comme on hardcode l'IP on doit
         * mettre l'octet 192 en byte 0 du mot LE → 0x012a a8c0).
         */
        fun channelIpConnect(
            masterId: Int,
            transport: Int = IP_TRANSPORT_UDP,
            application: Int = IP_APP_NONE,
            remoteIpv4Be: Int,
            remotePort: Int,
        ): MuxCtrlMessage = MuxCtrlMessage(
            id = ID_CHANNEL_IP_CONNECT,
            chanid = masterId,
            args = intArrayOf(transport, application, remoteIpv4Be, remotePort, 0, 0),
        )

        /** Construit l'IPv4 en network byte order pour `channelIpConnect`. */
        fun ipv4Be(a: Int, b: Int, c: Int, d: Int): Int =
            (d shl 24) or (c shl 16) or (b shl 8) or a
    }
}
