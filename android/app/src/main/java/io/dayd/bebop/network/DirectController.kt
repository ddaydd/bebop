package io.dayd.bebop.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.dayd.bebop.FileLogger as Log
import io.dayd.bebop.arsdk.ArCommand
import io.dayd.bebop.aoa.AoaController
import io.dayd.bebop.arsdk.ArStreamAck
import io.dayd.bebop.arsdk.ArStreamReader
import io.dayd.bebop.arsdk.ArsdkIds
import io.dayd.bebop.arsdk.ArsdkTransport
import io.dayd.bebop.video.RtpDepayloader
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

    /** Paquets UDP reçus du drone — prouve que le lien est vivant même sans ARCommand. */
    private val _packetsIn = MutableStateFlow(0L)
    val packetsIn: StateFlow<Long> = _packetsIn.asStateFlow()

    /** Instant (uptimeMillis) du dernier paquet reçu, pour calculer l'âge côté UI. */
    private val _lastPacketAt = MutableStateFlow(0L)
    val lastPacketAt: StateFlow<Long> = _lastPacketAt.asStateFlow()

    /** Dernier tuple ARCommand reçu, format "(prj,cls,cmd)". */
    private val _lastTuple = MutableStateFlow<String?>(null)
    val lastTuple: StateFlow<String?> = _lastTuple.asStateFlow()

    /**
     * Réglage de performance annoncé par le drone : valeur courante et bornes.
     * Les *SettingsState renvoient 3 floats (current, min, max).
     */
    data class Setting(val current: Float, val min: Float, val max: Float) {
        /** Vrai si le drone tourne bien en dessous de ce qu'il sait faire. */
        val throttled: Boolean get() = max > min && current < max * 0.95f
    }

    private val _maxRotationSpeed = MutableStateFlow<Setting?>(null)
    val maxRotationSpeed: StateFlow<Setting?> = _maxRotationSpeed.asStateFlow()

    private val _maxVerticalSpeed = MutableStateFlow<Setting?>(null)
    val maxVerticalSpeed: StateFlow<Setting?> = _maxVerticalSpeed.asStateFlow()

    private val _maxTilt = MutableStateFlow<Setting?>(null)
    val maxTilt: StateFlow<Setting?> = _maxTilt.asStateFlow()

    private fun readFloatLe(buf: ByteArray, off: Int): Float =
        java.lang.Float.intBitsToFloat(readU32Le(buf, off))

    private fun readSetting(args: ByteArray): Setting? =
        if (args.size < 12) null
        else Setting(readFloatLe(args, 0), readFloatLe(args, 4), readFloatLe(args, 8))

    /**
     * Règle les performances à une fraction de la plage annoncée par le drone
     * (0 = minimum, 1 = maximum). Interpoler plutôt que de sauter au max :
     * d'usine ce Bebop tourne à 13 °/s pour un maximum de 200, et passer
     * directement à 200 rend l'appareil difficile à tenir.
     */
    fun applyPerformance(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        fun target(s: Setting) = s.min + (s.max - s.min) * f
        _maxRotationSpeed.value?.let {
            val v = target(it)
            sendFlightCommand(ArCommand.maxRotationSpeed(v), "MaxRotationSpeed($v °/s)")
        }
        _maxVerticalSpeed.value?.let {
            val v = target(it)
            sendFlightCommand(ArCommand.maxVerticalSpeed(v), "MaxVerticalSpeed($v m/s)")
        }
        _maxTilt.value?.let {
            val v = target(it)
            sendFlightCommand(ArCommand.maxTilt(v), "MaxTilt($v °)")
        }
    }

    // --- Retour au point de départ (RTH) -------------------------------------
    // Le RTH dépend du GPS : sans fix au décollage, le drone n'a pas de position
    // « maison » et la commande ne fera rien. On expose donc l'état réel plutôt
    // que d'offrir un bouton qui échouerait en silence.

    private val _gpsFix = MutableStateFlow(false)
    val gpsFix: StateFlow<Boolean> = _gpsFix.asStateFlow()

    /** Position maison enregistrée par le drone (lat, lon). */
    private val _homeSet = MutableStateFlow(false)
    val homeSet: StateFlow<Boolean> = _homeSet.asStateFlow()

    /** NavigateHomeStateChanged : 0=available 1=inProgress 2=unavailable 3=pending. */
    private val _navigateHomeState = MutableStateFlow<Int?>(null)
    val navigateHomeState: StateFlow<Int?> = _navigateHomeState.asStateFlow()

    /** Vrai quand un retour maison peut réellement être lancé. */
    val homeAvailable: StateFlow<Boolean> = MutableStateFlow(false).also { flow ->
        scope.launch {
            kotlinx.coroutines.flow.combine(_gpsFix, _homeSet, _navigateHomeState) { fix, home, nav ->
                // L'état 2 (unavailable) fait foi quand le drone l'annonce ;
                // sinon on se rabat sur fix + maison connue.
                if (nav == 2) false else (fix && home) || nav == 0 || nav == 1
            }.collect { flow.value = it }
        }
    }

    fun sendNavigateHome(start: Boolean) =
        sendFlightCommand(ArCommand.navigateHome(start), "NavigateHome($start)")

    /** ardrone3 FlyingStateChanged : 0=landed 1=takingoff 2=hovering 3=flying 4=landing 5=emergency. */
    private val _flyingState = MutableStateFlow<Int?>(null)
    val flyingState: StateFlow<Int?> = _flyingState.asStateFlow()

    /** VideoStateChangedV2 : 0=stopped 1=started 2=failed 3=autostopped. */
    private val _videoRecordState = MutableStateFlow<Int?>(null)
    val videoRecordState: StateFlow<Int?> = _videoRecordState.asStateFlow()

    private fun flyingStateLabel(s: Int): String = when (s) {
        0 -> "posé"; 1 -> "décollage"; 2 -> "vol stationnaire"; 3 -> "en vol"
        4 -> "atterrissage"; 5 -> "urgence"; 6 -> "décollage utilisateur"
        7 -> "montée moteurs"; 8 -> "atterrissage d'urgence"
        else -> "état $s"
    }

    private fun navHomeLabel(s: Int): String = when (s) {
        0 -> "disponible"; 1 -> "en cours"; 2 -> "indisponible"; 3 -> "en attente"
        else -> "état $s"
    }

    private fun readU64Le(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (buf[off + i].toLong() and 0xff)
        return v
    }

    private fun readU32Le(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xff) or ((buf[off + 1].toInt() and 0xff) shl 8) or
            ((buf[off + 2].toInt() and 0xff) shl 16) or ((buf[off + 3].toInt() and 0xff) shl 24)

    // --- Vidéo ---------------------------------------------------------------
    // Le drone peut streamer de deux façons selon ce qu'on déclare dans le
    // CONN_REQ : ARStream2 (RTP sur un port UDP dédié) ou ARStream v1
    // (fragments sur le buffer 125 du canal d2c). On gère les deux : le format
    // réellement utilisé dépend du firmware et n'est pas connu à l'avance.

    /** Sink des access units H.264 assemblées, branché par le ViewModel. */
    @Volatile var videoFrameSink: ((ByteArray, Boolean) -> Unit)? = null

    private val _videoFrames = MutableStateFlow(0L)
    val videoFrames: StateFlow<Long> = _videoFrames.asStateFlow()

    /** (packetsIn, packetsDropped, nalsOut) du dépéqueteur RTP. */
    private val _rtpStats = MutableStateFlow(Triple(0L, 0L, 0L))
    val rtpStats: StateFlow<Triple<Long, Long, Long>> = _rtpStats.asStateFlow()

    /** "rtp" ou "arstream-v1" — quelle voie a effectivement livré des frames. */
    private val _videoPath = MutableStateFlow<String?>(null)
    val videoPath: StateFlow<String?> = _videoPath.asStateFlow()

    private var videoSocket: DatagramSocket? = null
    private var videoJob: Job? = null

    private val rtpAccessUnit = java.io.ByteArrayOutputStream(128 * 1024)

    private val rtpDepayloader = RtpDepayloader { nalAnnexB, marker ->
        rtpAccessUnit.write(nalAnnexB)
        if (marker) {
            val frame = rtpAccessUnit.toByteArray()
            rtpAccessUnit.reset()
            emitVideoFrame(frame, "rtp")
        }
    }

    private val arStreamReader = ArStreamReader(
        onFrame = { _, _, data -> emitVideoFrame(data, "arstream-v1") },
        onAckUpdate = { frameNum, low, high ->
            // Sans ACK, le drone retransmet en boucle et finit par couper.
            sendDirect(
                ArsdkTransport.encode(
                    ArsdkTransport.DATA_TYPE_ACK,
                    ARSTREAM_V1_ACK_BUFFER,
                    nextSeq(ARSTREAM_V1_ACK_BUFFER),
                    ArStreamAck.encode(frameNum, low, high),
                )
            )
        },
    )

    private fun emitVideoFrame(frame: ByteArray, path: String) {
        val count = _videoFrames.value + 1
        _videoFrames.value = count
        if (_videoPath.value != path) {
            _videoPath.value = path
            Log.i(TAG, "vidéo directe : premières frames via $path (${frame.size}B)")
        }
        if (count % 300 == 0L) Log.d(TAG, "vidéo directe : $count frames, ${frame.size}B dernière")
        _rtpStats.value = Triple(rtpDepayloader.packetsIn, rtpDepayloader.packetsDropped, rtpDepayloader.nalsOut)
        videoFrameSink?.invoke(frame, true)
    }

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
            // Déclarer les ports arstream2_* fait basculer le drone en RTP UDP
            // direct vers le client. C'est inutilisable via le SC2 (le RTP ne
            // traverse pas le MUX) mais c'est exactement ce qu'on veut ici,
            // puisqu'on parle au drone sans intermédiaire.
            val request = """{"controller_type":"Android","controller_name":"BebopApp","d2c_port":$d2cPort,""" +
                """"arstream2_client_stream_port":$VIDEO_STREAM_PORT,""" +
                """"arstream2_client_control_port":$VIDEO_CONTROL_PORT}"""
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
            // JSON complet : il annonce les ports stream du drone et les
            // paramètres ARStream (fragment size, etc.) — nécessaire pour
            // diagnostiquer quelle voie vidéo le firmware a retenue.
            Log.i(TAG, "Discovery OK: c2d=$c2dPort — ${config.second}")

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

            // Socket RTP : ouvert avant VideoEnable pour ne pas rater les
            // premiers paquets (dont les SPS/PPS, sans lesquels le décodeur
            // reste en attente).
            runCatching {
                val vsock = DatagramSocket(null as java.net.SocketAddress?)
                net.bindSocket(vsock)
                vsock.bind(java.net.InetSocketAddress(VIDEO_STREAM_PORT))
                videoSocket = vsock
                videoJob = scope.launch { videoLoop(vsock) }
                Log.i(TAG, "socket vidéo RTP ouvert sur $VIDEO_STREAM_PORT")
            }.onFailure { Log.w(TAG, "socket vidéo non ouvert: ${it.message}") }

            val now = java.util.Date()
            val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val timeFmt = java.text.SimpleDateFormat("'T'HHmmssZ", java.util.Locale.US)
            val dateStr = dateFmt.format(now)
            val timeStr = timeFmt.format(now)
            val (dPrj, dCls, dCmd) = ArsdkIds.COMMON_CURRENT_DATE
            sendArCommand(arCmdWithString(dPrj, dCls, dCmd, dateStr), ArsdkTransport.DATA_TYPE_WITHACK, ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK)
            Log.i(TAG, "CurrentDate envoyé: $dateStr")
            val (tPrj, tCls, tCmd) = ArsdkIds.COMMON_CURRENT_TIME
            sendArCommand(arCmdWithString(tPrj, tCls, tCmd, timeStr), ArsdkTransport.DATA_TYPE_WITHACK, ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK)
            Log.i(TAG, "CurrentTime envoyé: $timeStr")

            sendArCommand(ArCommand.noArgs(0, 2, 0), ArsdkTransport.DATA_TYPE_WITHACK, ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK)
            Log.i(TAG, "AllSettings (0,2,0) envoyé")
            kotlinx.coroutines.delay(500)
            requestAllStates()
            startVideo()
        } catch (e: Exception) {
            Log.w(TAG, "Connexion échouée: ${e.message}")
            _droneStatus.value = "Erreur : ${e.message}"
            _connected.value = false
        }
    }

    /** Re-demande au drone de repousser tous ses states (dont la batterie). */
    fun requestAllStates() {
        scope.launch {
            val (prj, cls, cmd) = ArsdkIds.COMMON_ALL_STATES
            sendArCommand(ArCommand.noArgs(prj, cls, cmd), ArsdkTransport.DATA_TYPE_WITHACK, ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK)
            Log.i(TAG, "AllStates ($prj,$cls,$cmd) envoyé")
        }
    }

    /** VideoStreamMode(low_latency) puis VideoEnable(1) — le drone streame au sol. */
    fun startVideo() {
        scope.launch {
            sendArCommand(ArCommand.videoStreamMode(0), ArsdkTransport.DATA_TYPE_WITHACK, ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK)
            kotlinx.coroutines.delay(100)
            sendArCommand(ArCommand.videoEnable(true), ArsdkTransport.DATA_TYPE_WITHACK, ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK)
            Log.i(TAG, "VideoStreamMode(0) + VideoEnable(1) envoyés")
        }
    }

    fun stopVideo() {
        scope.launch {
            sendArCommand(ArCommand.videoEnable(false), ArsdkTransport.DATA_TYPE_WITHACK, ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK)
            Log.i(TAG, "VideoEnable(0) envoyé")
        }
    }

    private suspend fun videoLoop(sock: DatagramSocket) = withContext(Dispatchers.IO) {
        Log.i(TAG, "videoLoop: démarré sur port ${sock.localPort}")
        val buf = ByteArray(65536)
        var n = 0L
        try {
            while (isActive && !sock.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                n++
                if (n == 1L) Log.i(TAG, "videoLoop: 1er paquet ${pkt.length}B de ${pkt.address}:${pkt.port}")
                rtpDepayloader.feed(buf.copyOfRange(0, pkt.length))
                if (n % 100 == 0L) {
                    _rtpStats.value = Triple(rtpDepayloader.packetsIn, rtpDepayloader.packetsDropped, rtpDepayloader.nalsOut)
                }
            }
        } catch (e: Exception) {
            if (isActive) Log.w(TAG, "videoLoop: ${e.message} (après $n paquets)")
        }
    }

    fun disconnect() {
        videoJob?.cancel()
        videoJob = null
        videoSocket?.close()
        videoSocket = null
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
        _packetsIn.value = 0L
        _lastPacketAt.value = 0L
        _lastTuple.value = null
        _flyingState.value = null
        _videoRecordState.value = null
        _gpsFix.value = false
        _homeSet.value = false
        _navigateHomeState.value = null
        _videoFrames.value = 0L
        _videoPath.value = null
        centerSticks()
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

    /**
     * PCMD à 25 Hz en continu, y compris sticks au neutre : sans flux le drone
     * considère le pilote absent après ~200 ms et coupe la liaison. La boucle
     * tourne donc dès la connexion, indépendamment du fait qu'on soit en vol.
     */
    private suspend fun pcmdLoop() = withContext(Dispatchers.IO) {
        var seq = 0
        val started = System.currentTimeMillis()
        try {
            while (isActive && socket != null) {
                val i = _pilotingInput.value
                // flag=1 signale au drone que le pilote agit sur roll/pitch.
                val flag = if (i.roll != 0 || i.pitch != 0) 1 else 0
                val ts = (System.currentTimeMillis() - started).toInt()
                val pcmd = ArCommand.pcmd(flag, i.roll, i.pitch, i.yaw, i.gaz, ts)
                val frame = ArsdkTransport.encode(ArsdkTransport.DATA_TYPE_NOACK, ArsdkTransport.BUFFER_ID_C2D_CMD_NOACK, seq and 0xff, pcmd)
                sendDirect(frame)
                seq++
                kotlinx.coroutines.delay(40)
            }
        } catch (_: Exception) {}
    }

    // --- Commandes de vol ----------------------------------------------------

    private val _pilotingInput = MutableStateFlow(AoaController.PilotingInput())
    val pilotingInput: StateFlow<AoaController.PilotingInput> = _pilotingInput.asStateFlow()

    fun setPilotingInput(input: AoaController.PilotingInput) {
        _pilotingInput.value = AoaController.PilotingInput(
            roll = input.roll.coerceIn(-100, 100),
            pitch = input.pitch.coerceIn(-100, 100),
            yaw = input.yaw.coerceIn(-100, 100),
            gaz = input.gaz.coerceIn(-100, 100),
        )
    }

    /** Remet les sticks au neutre — utilisé à l'atterrissage et à la déconnexion. */
    fun centerSticks() {
        _pilotingInput.value = AoaController.PilotingInput()
    }

    fun sendTakeoff() = sendFlightCommand(ArCommand.takeoff(), "Takeoff")
    fun sendLanding() = sendFlightCommand(ArCommand.landing(), "Landing")
    fun sendFlatTrim() = sendFlightCommand(ArCommand.flatTrim(), "FlatTrim")
    fun sendVideoRecord(start: Boolean) =
        sendFlightCommand(ArCommand.videoRecord(start), "VideoRecord($start)")

    /**
     * Emergency coupe les moteurs immédiatement. Passe par le buffer HIGHPRIO
     * pour shortcut la queue WITHACK, qui peut être saturée au pire moment,
     * et remet les sticks au neutre pour qu'aucun PCMD résiduel ne parte.
     */
    fun sendEmergency() {
        centerSticks()
        sendFlightCommand(ArCommand.emergency(), "EMERGENCY", ArsdkTransport.BUFFER_ID_C2D_CMD_HIGHPRIO)
    }

    private fun sendFlightCommand(
        payload: ByteArray,
        label: String,
        bufferId: Int = ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK,
    ) {
        if (socket == null) {
            Log.w(TAG, "$label ignoré : pas connecté")
            return
        }
        scope.launch {
            sendArCommand(payload, ArsdkTransport.DATA_TYPE_WITHACK, bufferId)
            Log.i(TAG, "$label envoyé (direct)")
        }
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
        var lastStatsAt = android.os.SystemClock.uptimeMillis()
        try {
            while (isActive && !sock.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                pktCount++
                val now = android.os.SystemClock.uptimeMillis()
                _packetsIn.value = pktCount
                _lastPacketAt.value = now
                if (pktCount <= 3) Log.i(TAG, "readLoop: pkt #$pktCount ${pkt.length}B from ${pkt.address}:${pkt.port}")
                // Stats périodiques : sans ça, un lien vivant qui n'envoie que des
                // PONG/ACK (filtrés plus bas) est indiscernable d'un lien mort.
                if (now - lastStatsAt >= 2000) {
                    Log.i(TAG, "readLoop: $pktCount paquets reçus au total")
                    lastStatsAt = now
                }
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
            // Voie ARStream v1 : fragments vidéo sur le buffer 125 du canal d2c.
            // Utilisée si le firmware ignore les ports arstream2_* déclarés.
            if (frame.bufferId == ARSTREAM_V1_DATA_BUFFER) {
                arStreamReader.feed(frame.body)
                continue
            }
            if (frame.dataType == ArsdkTransport.DATA_TYPE_ACK) continue
            if (frame.bufferId == ArsdkTransport.BUFFER_ID_PONG) continue
            if (frame.body.size < 4) continue

            val prj = frame.body[0].toInt() and 0xff
            val cls = frame.body[1].toInt() and 0xff
            val cmd = (frame.body[2].toInt() and 0xff) or ((frame.body[3].toInt() and 0xff) shl 8)
            val args = frame.body.copyOfRange(4, frame.body.size)
            _lastTuple.value = "($prj,$cls,$cmd)"
            Log.d(TAG, "ARCmd: ($prj,$cls,$cmd) args=${args.size}B  [type=${frame.dataType} buf=${frame.bufferId}]")

            if (Triple(prj, cls, cmd) == ArsdkIds.COMMON_BATTERY && args.isNotEmpty()) {
                val pct = args[0].toInt() and 0xff
                Log.i(TAG, "Batterie drone (direct): $pct%")
                _droneBattery.value = pct
                _droneStatus.value = "Connecté — batterie $pct%"
            }
            // ardrone3.PilotingState.FlyingStateChanged — savoir si le drone vole
            // conditionne l'UI (on n'affiche pas DÉCOLLER en vol).
            if (prj == 1 && cls == 4 && cmd == 1 && args.size >= 4) {
                val st = readU32Le(args, 0)
                if (_flyingState.value != st) {
                    Log.i(TAG, "FlyingState: $st (${flyingStateLabel(st)})")
                    _flyingState.value = st
                }
            }
            // (1,4,3) NavigateHomeStateChanged : state u32 + reason u32
            if (prj == 1 && cls == 4 && cmd == 3 && args.size >= 4) {
                val st = readU32Le(args, 0)
                if (_navigateHomeState.value != st) {
                    Log.i(TAG, "NavigateHomeState: $st (${navHomeLabel(st)})")
                    _navigateHomeState.value = st
                }
            }
            // (1,24,0) HomeChanged : 3 doubles (lat, lon, alt). Parrot signale
            // « pas de position » par la valeur sentinelle 500.
            if (prj == 1 && cls == 24 && cmd == 0 && args.size >= 16) {
                val lat = java.lang.Double.longBitsToDouble(readU64Le(args, 0))
                val lon = java.lang.Double.longBitsToDouble(readU64Le(args, 8))
                val ok = lat != 500.0 && lon != 500.0
                if (_homeSet.value != ok) {
                    Log.i(TAG, "Home ${if (ok) "enregistrée ($lat, $lon)" else "absente"}")
                    _homeSet.value = ok
                }
            }
            // (1,24,2) GPSFixStateChanged : u8 fixed
            if (prj == 1 && cls == 24 && cmd == 2 && args.isNotEmpty()) {
                val fixed = args[0].toInt() != 0
                if (_gpsFix.value != fixed) {
                    Log.i(TAG, "GPS fix: $fixed")
                    _gpsFix.value = fixed
                }
            }
            // Réglages de perf annoncés par le drone (current, min, max).
            // (1,12,x) = SpeedSettingsState, (1,6,1) = PilotingSettingsState.MaxTilt
            if (prj == 1 && cls == 12 && cmd == 0) readSetting(args)?.let {
                _maxVerticalSpeed.value = it
                Log.i(TAG, "MaxVerticalSpeed: ${it.current} m/s (min ${it.min}, max ${it.max})")
            }
            if (prj == 1 && cls == 12 && cmd == 1) readSetting(args)?.let {
                _maxRotationSpeed.value = it
                Log.i(TAG, "MaxRotationSpeed: ${it.current} °/s (min ${it.min}, max ${it.max})")
            }
            if (prj == 1 && cls == 6 && cmd == 1) readSetting(args)?.let {
                _maxTilt.value = it
                Log.i(TAG, "MaxTilt: ${it.current}° (min ${it.min}, max ${it.max})")
            }
            if (Triple(prj, cls, cmd) == ArsdkIds.ARDRONE3_VIDEO_RECORD_STATE && args.size >= 4) {
                _videoRecordState.value = readU32Le(args, 0)
            }
            if (Triple(prj, cls, cmd) == ArsdkIds.COMMON_PRODUCT_VERSION) {
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
        /** Ports UDP locaux annoncés au drone pour le flux RTP ARStream2. */
        private const val VIDEO_STREAM_PORT = 55004
        private const val VIDEO_CONTROL_PORT = 55005
        /** ARStream v1 : buffers ARNetwork (cf. ARDISCOVERY_DEVICE_Usb.c). */
        private const val ARSTREAM_V1_DATA_BUFFER = 125
        private const val ARSTREAM_V1_ACK_BUFFER = 13
    }
}
