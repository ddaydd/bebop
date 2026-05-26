package io.dayd.bebop.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.dayd.bebop.FileLogger as Log
import io.dayd.bebop.arsdk.ArCommand
import io.dayd.bebop.arsdk.ArsdkTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class DirectController(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var socket: DatagramSocket? = null
    private var droneAddr: InetAddress? = null
    private var c2dPort: Int = 0
    private var readerJob: Job? = null
    private var pingJob: Job? = null
    private var pcmdJob: Job? = null
    private var wifiNetwork: Network? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _droneBattery = MutableStateFlow<Int?>(null)
    val droneBattery: StateFlow<Int?> = _droneBattery.asStateFlow()

    private val _droneStatus = MutableStateFlow("Non connecté")
    val droneStatus: StateFlow<String> = _droneStatus.asStateFlow()

    private val _droneFirmware = MutableStateFlow<String?>(null)
    val droneFirmware: StateFlow<String?> = _droneFirmware.asStateFlow()

    private val seqByBuffer = ConcurrentHashMap<Int, AtomicInteger>()

    private fun nextSeq(bufferId: Int): Int =
        seqByBuffer.getOrPut(bufferId) { AtomicInteger(0) }.getAndIncrement() and 0xff

    private suspend fun findWifiNetwork(): Network? {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { cont ->
                var unregistered = false
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (!unregistered) {
                            unregistered = true
                            runCatching { cm.unregisterNetworkCallback(this) }
                        }
                        cont.resume(network)
                    }
                }
                cont.invokeOnCancellation {
                    if (!unregistered) {
                        unregistered = true
                        runCatching { cm.unregisterNetworkCallback(cb) }
                    }
                }
                cm.requestNetwork(req, cb)
            }
        }
    }

    private suspend fun discovery(host: String, d2cPort: Int): Pair<Int, String> {
        val sock = Socket()
        sock.connect(InetSocketAddress(host, 44444), 3000)
        return sock.use { s ->
            val request = """{"controller_type":"Android","controller_name":"BebopApp","d2c_port":$d2cPort}"""
            s.getOutputStream().apply {
                write(request.toByteArray(Charsets.UTF_8))
                write(0)
                flush()
            }
            val response = buildString {
                val reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
                while (true) {
                    val c = reader.read()
                    if (c == -1 || c == 0) break
                    append(c.toChar())
                }
            }
            if (response.isEmpty()) error("Discovery: réponse vide")
            val json = org.json.JSONObject(response)
            if (json.optInt("status", -1) != 0) error("Discovery: status=${json.optInt("status")}")
            json.getInt("c2d_port") to response
        }
    }

    suspend fun connect(host: String = "192.168.42.1", d2cPort: Int = 43210) {
        disconnect()
        _droneStatus.value = "Recherche réseau Wi-Fi…"
        try {
            val net = findWifiNetwork()
            if (net == null) {
                _droneStatus.value = "Aucun Wi-Fi trouvé — connecter au Bebop2"
                return
            }
            wifiNetwork = net
            Log.i(TAG, "Wi-Fi network trouvé: $net")
            cm.bindProcessToNetwork(net)

            _droneStatus.value = "Connexion TCP…"
            var lastError: Exception? = null
            var config: Pair<Int, String>? = null
            for (attempt in 1..6) {
                try {
                    config = withContext(Dispatchers.IO) { discovery(host, d2cPort) }
                    break
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Discovery tentative $attempt/6: ${e.message}")
                    _droneStatus.value = "Tentative $attempt/6…"
                    kotlinx.coroutines.delay(3000)
                }
            }
            if (config == null) throw lastError ?: Exception("Discovery échoué")

            c2dPort = config.first
            droneAddr = InetAddress.getByName(host)
            Log.i(TAG, "Discovery OK: c2d=$c2dPort — ${config.second.take(80)}")

            _droneStatus.value = "Ouverture UDP…"
            val sock = DatagramSocket(null as java.net.SocketAddress?)
            net.bindSocket(sock)
            sock.bind(java.net.InetSocketAddress(d2cPort))
            Log.i(TAG, "UDP socket bindé au réseau Wi-Fi $net, port local=${sock.localPort}")
            sock.soTimeout = 0
            socket = sock
            _connected.value = true
            _droneStatus.value = "Connecté — demande des états…"

            readerJob = scope.launch { readLoop(sock) }
            pingJob = scope.launch { pingLoop() }
            pcmdJob = scope.launch { pcmdLoop() }

            val now = java.util.Date()
            val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val timeFmt = java.text.SimpleDateFormat("'T'HHmmssZ", java.util.Locale.US)
            val dateStr = dateFmt.format(now)
            val timeStr = timeFmt.format(now)
            sendArCommand(arCmdWithString(0, 0, 1, dateStr), ArsdkTransport.DATA_TYPE_WITHACK, ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK)
            Log.i(TAG, "CurrentDate envoyé: $dateStr")
            sendArCommand(arCmdWithString(0, 0, 2, timeStr), ArsdkTransport.DATA_TYPE_WITHACK, ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK)
            Log.i(TAG, "CurrentTime envoyé: $timeStr")

            sendArCommand(ArCommand.noArgs(0, 2, 0), ArsdkTransport.DATA_TYPE_WITHACK, ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK)
            Log.i(TAG, "AllSettings (0,2,0) envoyé")
            kotlinx.coroutines.delay(500)
            sendArCommand(ArCommand.noArgs(0, 0, 0), ArsdkTransport.DATA_TYPE_WITHACK, ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK)
            Log.i(TAG, "AllStates (0,0,0) envoyé")
        } catch (e: Exception) {
            Log.w(TAG, "Connexion échouée: ${e.message}")
            _droneStatus.value = "Erreur : ${e.message}"
            _connected.value = false
        }
    }

    fun disconnect() {
        pcmdJob?.cancel()
        pcmdJob = null
        pingJob?.cancel()
        pingJob = null
        readerJob?.cancel()
        readerJob = null
        socket?.close()
        socket = null
        if (wifiNetwork != null) cm.bindProcessToNetwork(null)
        wifiNetwork = null
        _connected.value = false
        _droneStatus.value = "Déconnecté"
        _droneBattery.value = null
        _droneFirmware.value = null
        seqByBuffer.clear()
    }

    private fun sendDirect(data: ByteArray) {
        val sock = socket ?: return
        val addr = droneAddr ?: return
        try {
            sock.send(DatagramPacket(data, data.size, addr, c2dPort))
        } catch (e: Exception) {
            Log.w(TAG, "Envoi UDP échoué: ${e.message}")
        }
    }

    private suspend fun sendArCommand(payload: ByteArray, dataType: Int, bufferId: Int) =
        withContext(Dispatchers.IO) {
            val seq = nextSeq(bufferId)
            val frame = ArsdkTransport.encode(dataType, bufferId, seq, payload)
            sendDirect(frame)
        }

    private fun arCmdWithString(prj: Int, cls: Int, cmd: Int, str: String): ByteArray {
        val strBytes = str.toByteArray(Charsets.UTF_8)
        val buf = java.nio.ByteBuffer.allocate(4 + strBytes.size + 1).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(prj.toByte())
        buf.put(cls.toByte())
        buf.putShort(cmd.toShort())
        buf.put(strBytes)
        buf.put(0)
        return buf.array()
    }

    private suspend fun pcmdLoop() = withContext(Dispatchers.IO) {
        var seq = 0
        try {
            while (isActive && socket != null) {
                val ts = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
                val pcmd = ArCommand.pcmd(0, 0, 0, 0, 0, ts)
                val frame = ArsdkTransport.encode(ArsdkTransport.DATA_TYPE_NOACK, ArsdkTransport.BUFFER_ID_C2D_CMD_NOACK, seq and 0xff, pcmd)
                sendDirect(frame)
                seq++
                kotlinx.coroutines.delay(40)
            }
        } catch (_: Exception) {}
    }

    private suspend fun pingLoop() = withContext(Dispatchers.IO) {
        var seq = 0
        try {
            while (isActive && socket != null) {
                val ts = System.currentTimeMillis()
                val body = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(ts).array()
                val ping = ArsdkTransport.encode(ArsdkTransport.DATA_TYPE_NOACK, ArsdkTransport.BUFFER_ID_PING, seq and 0xff, body)
                sendDirect(ping)
                seq++
                kotlinx.coroutines.delay(500)
            }
        } catch (_: Exception) {}
    }

    private suspend fun readLoop(sock: DatagramSocket) = withContext(Dispatchers.IO) {
        Log.i(TAG, "readLoop: démarré sur port ${sock.localPort}")
        val buf = ByteArray(65536)
        var pktCount = 0L
        try {
            while (isActive && !sock.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                pktCount++
                if (pktCount <= 3) Log.i(TAG, "readLoop: pkt #$pktCount ${pkt.length}B from ${pkt.address}:${pkt.port}")
                val data = buf.copyOfRange(0, pkt.length)
                processPacket(data)
            }
        } catch (e: Exception) {
            if (isActive) Log.w(TAG, "readLoop: ${e.message} (après $pktCount paquets)")
        }
    }

    private fun processPacket(data: ByteArray) {
        val frames = ArsdkTransport.decodeAll(data)
        for (frame in frames) {
            if (frame.bufferId == ArsdkTransport.BUFFER_ID_PING) {
                val pong = ArsdkTransport.encode(ArsdkTransport.DATA_TYPE_NOACK, ArsdkTransport.BUFFER_ID_PONG, frame.seq, frame.body)
                sendDirect(pong)
                continue
            }
            if (frame.dataType == ArsdkTransport.DATA_TYPE_WITHACK) {
                val ackBuf = frame.bufferId or ArsdkTransport.BUFFER_ID_ACKOFF
                val ackSeq = nextSeq(ackBuf)
                val ackBody = ByteArray(1).apply { this[0] = frame.seq.toByte() }
                val ackFrame = ArsdkTransport.encode(ArsdkTransport.DATA_TYPE_ACK, ackBuf, ackSeq, ackBody)
                sendDirect(ackFrame)
            }
            if (frame.dataType == ArsdkTransport.DATA_TYPE_ACK) continue
            if (frame.bufferId == ArsdkTransport.BUFFER_ID_PONG) continue
            if (frame.body.size < 4) continue

            val prj = frame.body[0].toInt() and 0xff
            val cls = frame.body[1].toInt() and 0xff
            val cmd = (frame.body[2].toInt() and 0xff) or ((frame.body[3].toInt() and 0xff) shl 8)
            val args = frame.body.copyOfRange(4, frame.body.size)
            Log.d(TAG, "ARCmd: ($prj,$cls,$cmd) args=${args.size}B  [type=${frame.dataType} buf=${frame.bufferId}]")

            if (prj == 0 && cls == 1 && cmd == 1 && args.isNotEmpty()) {
                val pct = args[0].toInt() and 0xff
                Log.i(TAG, "Batterie drone (direct): $pct%")
                _droneBattery.value = pct
                _droneStatus.value = "Connecté — batterie $pct%"
            }
            if (prj == 0 && cls == 5 && cmd == 0) {
                val sw = readCString(args, 0)
                val hw = if (sw != null) readCString(args, sw.length + 1) else null
                if (sw != null) {
                    _droneFirmware.value = if (hw != null) "$sw / hw $hw" else sw
                    Log.i(TAG, "Firmware drone (direct): ${_droneFirmware.value}")
                }
            }
        }
    }

    private fun readCString(buf: ByteArray, offset: Int): String? {
        var end = offset
        while (end < buf.size && buf[end] != 0.toByte()) end++
        if (end >= buf.size) return null
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "Bebop"
    }
}
