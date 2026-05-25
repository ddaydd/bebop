package io.dayd.bebop.aoa

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.dayd.bebop.mux.MuxCtrlMessage
import io.dayd.bebop.mux.MuxDecoder
import io.dayd.bebop.mux.MuxFrame
import io.dayd.bebop.mux.PompArg
import io.dayd.bebop.mux.PompEncoder
import io.dayd.bebop.mux.PompMessage
import io.dayd.bebop.arsdk.ArCommand
import io.dayd.bebop.arsdk.ArCommandHeader
import io.dayd.bebop.arsdk.ArCommandSizes
import io.dayd.bebop.arsdk.ArsdkIds
import io.dayd.bebop.arsdk.ArStreamAck
import io.dayd.bebop.arsdk.ArStreamReader
import io.dayd.bebop.arsdk.ArsdkTransport
import io.dayd.bebop.video.RtpDepayloader
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.io.FileOutputStream

private const val ACTION_USB_PERMISSION = "io.dayd.bebop.USB_PERMISSION"

// Master chanid choisis pour les IP proxies vidéo via SC2. Doivent être ≥ 1024
// (mux_channel.h IS_MASTER_ID_MIN) et avec bit 31 = 0.
const val VIDEO_MASTER_ID = 1024
const val VIDEO_CONTROL_MASTER_ID = 1025

const val SLAVE_LABEL_VIDEO = "rtp_video"
const val SLAVE_LABEL_CONTROL = "rtcp_control"

// Canaux MUX statiques ARSDK (libmux-arsdk.h).
const val MUX_ARSDK_CHANNEL_ID_TRANSPORT = 1
const val MUX_ARSDK_CHANNEL_ID_DISCOVERY = 2
const val MUX_ARSDK_CHANNEL_ID_BACKEND = 3
const val MUX_ARSDK_CHANNEL_ID_STREAM_DATA = 4
const val MUX_ARSDK_CHANNEL_ID_STREAM_CONTROL = 5
const val MUX_ARSDK_CHANNEL_ID_RTP = 6

// Sur les chans STREAM_DATA/STREAM_CONTROL, les messages sont enveloppés POMP avec
// msgid=1 (RTP_DATA) — args = [u16 dest_port, buf rtp_data].
const val MUX_ARSDK_MSG_ID_RTP_DATA = 1

// Buffer IDs ARNetwork pour SC2 USB AOA + Bebop 2 (source : ARDISCOVERY_DEVICE_Usb.c, MPP_*).
// La vidéo arrive sur buffer 125 (D2C, NOACK/LOW_LATENCY) à travers chan 1 MUX.
const val MPP_D2C_VIDEO_DATA_ID = 125
const val MPP_D2C_EVENT_ID = 126
const val MPP_D2C_NAVDATA_ID = 127
const val MPP_C2D_VIDEO_ACK_ID = 13

data class DumpState(val path: String, val bytes: Long)

data class PilotingInfo(val source: Int, val roll: Int, val pitch: Int, val yaw: Int, val gaz: Int)

data class ArsdkDevice(val name: String, val type: Long, val id: String)

data class ConnResp(val status: Int, val json: String)

sealed interface AoaState {
    data object Disconnected : AoaState
    data object AwaitingPermission : AoaState
    data class Open(val accessory: UsbAccessory) : AoaState
    data class Error(val message: String) : AoaState
}

class AoaController(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow<AoaState>(AoaState.Disconnected)
    val state: StateFlow<AoaState> = _state.asStateFlow()

    private val _bytesIn = MutableStateFlow(0L)
    val bytesIn: StateFlow<Long> = _bytesIn.asStateFlow()

    private val _bytesOut = MutableStateFlow(0L)
    val bytesOut: StateFlow<Long> = _bytesOut.asStateFlow()

    /** Derniers chunks reçus, agrégés en hex pour debug (taille bornée). */
    private val _logTail = MutableStateFlow<List<String>>(emptyList())
    val logTail: StateFlow<List<String>> = _logTail.asStateFlow()

    private val _dump = MutableStateFlow<DumpState?>(null)
    val dump: StateFlow<DumpState?> = _dump.asStateFlow()

    @Volatile private var dumpOut: FileOutputStream? = null

    private val _frameStats = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val frameStats: StateFlow<Map<Int, Long>> = _frameStats.asStateFlow()

    private val _resyncs = MutableStateFlow(0)
    val resyncs: StateFlow<Int> = _resyncs.asStateFlow()

    private val _piloting = MutableStateFlow<PilotingInfo?>(null)
    val piloting: StateFlow<PilotingInfo?> = _piloting.asStateFlow()

    private val _pompDecodeFailures = MutableStateFlow(0)
    val pompDecodeFailures: StateFlow<Int> = _pompDecodeFailures.asStateFlow()

    private val _ctrlHistory = MutableStateFlow<List<MuxCtrlMessage>>(emptyList())
    val ctrlHistory: StateFlow<List<MuxCtrlMessage>> = _ctrlHistory.asStateFlow()

    private val _lastPomp = MutableStateFlow<Map<Int, PompMessage>>(emptyMap())
    val lastPomp: StateFlow<Map<Int, PompMessage>> = _lastPomp.asStateFlow()

    private val _devices = MutableStateFlow<List<ArsdkDevice>>(emptyList())
    val devices: StateFlow<List<ArsdkDevice>> = _devices.asStateFlow()

    private val _connResp = MutableStateFlow<ConnResp?>(null)
    val connResp: StateFlow<ConnResp?> = _connResp.asStateFlow()

    private val _videoFrameCount = MutableStateFlow(0L)
    val videoFrameCount: StateFlow<Long> = _videoFrameCount.asStateFlow()

    private val _videoLastFrameSize = MutableStateFlow(0)
    val videoLastFrameSize: StateFlow<Int> = _videoLastFrameSize.asStateFlow()

    private val _videoLastFrameFlags = MutableStateFlow(0)
    val videoLastFrameFlags: StateFlow<Int> = _videoLastFrameFlags.asStateFlow()

    private val _droneBatteryPercent = MutableStateFlow<Int?>(null)
    val droneBatteryPercent: StateFlow<Int?> = _droneBatteryPercent.asStateFlow()

    private val _sc2BatteryPercent = MutableStateFlow<Int?>(null)
    val sc2BatteryPercent: StateFlow<Int?> = _sc2BatteryPercent.asStateFlow()

    // Drone firmware version (Common.SettingsState.ProductVersionChanged).
    // 1ère string = software, 2e string = hardware. On garde les 2 ou juste la 1ère selon dispo.
    private val _droneFirmware = MutableStateFlow<String?>(null)
    val droneFirmware: StateFlow<String?> = _droneFirmware.asStateFlow()

    // ardrone3.MediaRecordState.VideoStateChangedV2 enum (0=stopped, 1=started, 2=failed, 3=autostopped).
    private val _videoRecordState = MutableStateFlow<Int?>(null)
    val videoRecordState: StateFlow<Int?> = _videoRecordState.asStateFlow()

    // ardrone3.MediaStreamingState.VideoEnableChanged enum (0=enabled, 1=disabled, 2=error).
    private val _videoEnableState = MutableStateFlow<Int?>(null)
    val videoEnableState: StateFlow<Int?> = _videoEnableState.asStateFlow()

    // ardrone3.MediaStreamingState.VideoStreamModeChanged enum (0=low_latency, 1=high_reliability…).
    private val _videoStreamMode = MutableStateFlow<Int?>(null)
    val videoStreamMode: StateFlow<Int?> = _videoStreamMode.asStateFlow()

    private val _arCmdStats = MutableStateFlow<Map<Triple<Int, Int, Int>, Long>>(emptyMap())
    val arCmdStats: StateFlow<Map<Triple<Int, Int, Int>, Long>> = _arCmdStats.asStateFlow()

    private val _arCmdLast = MutableStateFlow<ArCommandHeader?>(null)
    val arCmdLast: StateFlow<ArCommandHeader?> = _arCmdLast.asStateFlow()

    private val _transportStats = MutableStateFlow<Map<Pair<Int, Int>, Long>>(emptyMap())
    val transportStats: StateFlow<Map<Pair<Int, Int>, Long>> = _transportStats.asStateFlow()

    // ARStream v1 video data — bufferId 125 sur chan 1, format MPP (cf. ARDISCOVERY_DEVICE_Usb.c).
    // Si on en reçoit, c'est la bonne piste : il restera à dépéquetter le header v1
    // (frameNum/flags/fragNum/fragsPerFrame) et à émettre des ACKs sur buf 13.
    private val _videoV1Frames = MutableStateFlow(0L)
    val videoV1Frames: StateFlow<Long> = _videoV1Frames.asStateFlow()
    private val _videoV1LastSize = MutableStateFlow(0)
    val videoV1LastSize: StateFlow<Int> = _videoV1LastSize.asStateFlow()
    private val _videoV1LastHead = MutableStateFlow<String?>(null)
    val videoV1LastHead: StateFlow<String?> = _videoV1LastHead.asStateFlow()

    /** Hex (premiers ~32 octets) du dernier payload MUX chan 1, avant tout décodage. */
    private val _chan1Raw = MutableStateFlow<String?>(null)
    val chan1Raw: StateFlow<String?> = _chan1Raw.asStateFlow()

    // Map slave_chanid → label sémantique (déduit de l'ordre de réception des
    // CHANNEL_OPEN type=IP_SLAVE — le 1er répond à notre 1024 RTP video, le 2e
    // à 1025 RTCP). Le firmware SC2 utilise `slave = 0x80000000 - master`
    // (pas la formule actuelle `master | 0x80000000` du repo libmux récent),
    // donc on ne peut PAS dériver master mathématiquement de façon portable :
    // on mémorise dynamiquement à la réception.
    private val slaveLabels = java.util.concurrent.ConcurrentHashMap<Int, String>()
    @Volatile private var pendingSlaveLabels: ArrayDeque<String> = ArrayDeque()

    private val _slaveChanids = MutableStateFlow<Map<Int, String>>(emptyMap())
    val slaveChanids: StateFlow<Map<Int, String>> = _slaveChanids.asStateFlow()

    @Volatile var videoFrameSink: ((ByteArray, Boolean) -> Unit)? = null

    private val _chan4RawHead = MutableStateFlow<String?>(null)
    val chan4RawHead: StateFlow<String?> = _chan4RawHead.asStateFlow()

    private val _assembledFrameHead = MutableStateFlow<String?>(null)
    val assembledFrameHead: StateFlow<String?> = _assembledFrameHead.asStateFlow()

    private val arStreamReader = ArStreamReader(
        onFrame = { frameNum, flags, data ->
            _videoFrameCount.value = _videoFrameCount.value + 1
            _videoLastFrameSize.value = data.size
            _videoLastFrameFlags.value = flags
            val take = data.size.coerceAtMost(48)
            _assembledFrameHead.value = "[${data.size}B frm=$frameNum fl=$flags] " +
                data.copyOfRange(0, take).joinToString(" ") { "%02x".format(it) }
            videoFrameSink?.invoke(data, (flags and 1) != 0)
        },
        onAckUpdate = { frameNum, low, high ->
            val payload = ArStreamAck.encode(frameNum, low, high)
            scope.launch { sendPomp(chanid = 5, payload = payload) }
        },
    )

    // Accumule les NALs Annex-B d'une access unit. On flush dans videoFrameSink
    // au RTP marker bit (fin d'access unit). MediaCodec accepte un buffer qui
    // contient plusieurs NALs préfixées par leur start code.
    private val rtpAccessUnit = java.io.ByteArrayOutputStream(128 * 1024)

    private val _rtpStats = MutableStateFlow(Triple(0L, 0L, 0L))
    /** (packetsIn, packetsDropped, nalsOut) */
    val rtpStats: StateFlow<Triple<Long, Long, Long>> = _rtpStats.asStateFlow()

    private val rtpDepayloader = RtpDepayloader { nalAnnexB, marker ->
        rtpAccessUnit.write(nalAnnexB)
        if (marker) {
            val frame = rtpAccessUnit.toByteArray()
            rtpAccessUnit.reset()
            val count = _videoFrameCount.value + 1
            _videoFrameCount.value = count
            _videoLastFrameSize.value = frame.size
            _videoLastFrameFlags.value = if (marker) 1 else 0
            if (count == 1L) Log.i(TAG, "première access unit RTP assemblée (${frame.size}B)")
            if (count % 300 == 0L) Log.d(TAG, "RTP: $count access units, ${frame.size}B dernière")
            videoFrameSink?.invoke(frame, true)
        }
    }

    private val decoder = MuxDecoder()

    private var transport: AoaTransport? = null
    private var readerJob: Job? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && accessory != null) openInternal(accessory)
            else _state.value = AoaState.Error("Permission USB refusée")
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(permissionReceiver, filter)
        }
    }

    /**
     * Connecte le premier accessoire disponible. Demande la permission si nécessaire.
     */
    fun connectFirstAvailable() {
        val accessory = usbManager.accessoryList?.firstOrNull()
        if (accessory == null) {
            _state.value = AoaState.Error("Aucun accessoire USB connecté")
            return
        }
        if (usbManager.hasPermission(accessory)) {
            openInternal(accessory)
        } else {
            _state.value = AoaState.AwaitingPermission
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
            val pi = PendingIntent.getBroadcast(
                appContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbManager.requestPermission(accessory, pi)
        }
    }

    fun disconnect() {
        stopPilotingLoop()
        stopDump()
        readerJob?.cancel()
        readerJob = null
        transport?.close()
        transport = null
        _state.value = AoaState.Disconnected
    }

    fun startDump(file: File): Boolean {
        if (dumpOut != null) return true
        return runCatching {
            file.parentFile?.mkdirs()
            dumpOut = FileOutputStream(file, false)
            _dump.value = DumpState(file.absolutePath, 0L)
            true
        }.getOrElse {
            _state.value = AoaState.Error("dump: ${it.message}")
            false
        }
    }

    fun stopDump() {
        val out = dumpOut ?: return
        dumpOut = null
        runCatching { out.flush(); out.close() }
    }

    private val _ctrlSent = MutableStateFlow<Map<Int, Long>>(emptyMap())
    /** Compteur de ctrl messages envoyés, par id. Pour debug ("a-t-on bien envoyé ?"). */
    val ctrlSent: StateFlow<Map<Int, Long>> = _ctrlSent.asStateFlow()

    suspend fun sendCtrl(msg: MuxCtrlMessage): Boolean {
        val frame = MuxFrame(chanid = 0, payload = msg.encode())
        val ok = write(frame.encode())
        if (ok) _ctrlSent.update { it + (msg.id to ((it[msg.id] ?: 0L) + 1L)) }
        return ok
    }

    suspend fun sendHandshake(isAck: Boolean = false): Boolean =
        sendCtrl(MuxCtrlMessage.handshake(isAck))

    suspend fun sendPomp(chanid: Int, payload: ByteArray): Boolean {
        val frame = MuxFrame(chanid = chanid, payload = payload)
        return write(frame.encode())
    }

    suspend fun sendDiscover(): Boolean =
        sendPomp(chanid = 2, payload = PompEncoder().build(msgid = 1))

    /**
     * Demande au SC2 d'ouvrir un proxy IP UDP vers le drone sur (host, port).
     *
     * Séquence obligatoire (mux_channel.c:1809 — sans CHANNEL_OPEN de type IP_MASTER,
     * le peer ne crée pas de slave et ignore silencieusement le IP_CONNECT) :
     *  1. CHANNEL_OPEN(chanid=masterId, args[0]=IP_MASTER) → le SC2 alloue le slave
     *  2. CHANNEL_IP_CONNECT(chanid=masterId, args=[UDP, NONE, ipv4_be, port])
     *
     * Le SC2 répond CHANNEL_IP_CONNECTED et forward les paquets UDP du drone sur
     * le slave chanid (`masterId | 0x80000000`).
     */
    suspend fun sendOpenIpProxyUdp(masterId: Int, remotePort: Int, label: String,
                                   ipv4Be: Int = MuxCtrlMessage.ipv4Be(192, 168, 42, 1)): Boolean {
        // On enregistre l'attente du slave AVANT d'envoyer le CHANNEL_OPEN —
        // la réponse arrivera dans onFrames() chan 0 sous forme d'un
        // CHANNEL_OPEN type=IP_SLAVE avec un chanid alloué par le SC2.
        pendingSlaveLabels.addLast(label)
        val opened = sendCtrl(MuxCtrlMessage.channelOpen(
            targetChanid = masterId,
            type = MuxCtrlMessage.CHANNEL_TYPE_IP_MASTER,
        ))
        if (!opened) return false
        return sendCtrl(MuxCtrlMessage.channelIpConnect(
            masterId = masterId,
            transport = MuxCtrlMessage.IP_TRANSPORT_UDP,
            application = MuxCtrlMessage.IP_APP_NONE,
            remoteIpv4Be = ipv4Be,
            remotePort = remotePort,
        ))
    }

    /**
     * Ouvre les canaux MUX **statiques** STREAM_DATA (chan 4) et STREAM_CONTROL
     * (chan 5) côté SC2. Le SC2 attend ces CHANNEL_OPEN pour commencer à forward
     * le RTP/RTCP que le drone Bebop 2 lui envoie sur ses ports locaux 5004/5005.
     *
     * Source : `libARStream2/src/arstream2_rtp_receiver.c:91` —
     *   `mux_channel_open(mux, MUX_ARSDK_CHANNEL_ID_STREAM_DATA, NULL, NULL)`.
     *
     * Données reçues sur chan 4/5 : encapsulées en POMP msgid=1 (RTP_DATA) avec
     * args = [u16 dest_port, buf rtp_data]. Voir `libmux-arsdk.h`.
     */
    suspend fun sendOpenStreamChannels(): Boolean {
        // On ouvre 4, 5 ET 6 — selon les firmwares Bebop 2, le SC2 peut router
        // le RTP sur STREAM_DATA (4) [v2] ou sur le chan RTP dédié (6).
        sendCtrl(MuxCtrlMessage.channelOpen(
            targetChanid = MUX_ARSDK_CHANNEL_ID_STREAM_DATA,
            type = MuxCtrlMessage.CHANNEL_TYPE_NORMAL,
        ))
        sendCtrl(MuxCtrlMessage.channelOpen(
            targetChanid = MUX_ARSDK_CHANNEL_ID_STREAM_CONTROL,
            type = MuxCtrlMessage.CHANNEL_TYPE_NORMAL,
        ))
        return sendCtrl(MuxCtrlMessage.channelOpen(
            targetChanid = MUX_ARSDK_CHANNEL_ID_RTP,
            type = MuxCtrlMessage.CHANNEL_TYPE_NORMAL,
        ))
    }

    // arsdk_cmd_itf1 tient un compteur de seq par bufferId — un PCMD à 20 Hz ne doit
    // pas faire avancer la seq du WITHACK et inversement.
    private val seqByBuffer = ConcurrentHashMap<Int, AtomicInteger>()
    private fun nextSeq(bufferId: Int): Int =
        seqByBuffer.getOrPut(bufferId) { AtomicInteger(0) }.incrementAndGet() and 0xff

    private val _c2dSent = MutableStateFlow<Map<String, Long>>(emptyMap())
    val c2dSent: StateFlow<Map<String, Long>> = _c2dSent.asStateFlow()

    suspend fun sendArCommand(payload: ByteArray, dataType: Int, bufferId: Int): Boolean {
        val seq = nextSeq(bufferId)
        val transport = ArsdkTransport.encode(dataType, bufferId, seq, payload)
        val ok = sendPomp(chanid = 1, payload = transport)
        if (ok && payload.size >= 4) {
            val prj = payload[0].toInt() and 0xff
            val cls = payload[1].toInt() and 0xff
            val cmd = (payload[2].toInt() and 0xff) or ((payload[3].toInt() and 0xff) shl 8)
            val label = "($prj,$cls,$cmd)"
            _c2dSent.update { it + (label to ((it[label] ?: 0L) + 1L)) }
        }
        return ok
    }

    suspend fun sendVideoStreamMode(mode: Int = 0): Boolean = sendArCommand(
        payload = ArCommand.videoStreamMode(mode),
        dataType = ArsdkTransport.DATA_TYPE_WITHACK,
        bufferId = ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK,
    )

    suspend fun sendVideoRecord(start: Boolean): Boolean = sendArCommand(
        payload = ArCommand.videoRecord(start),
        dataType = ArsdkTransport.DATA_TYPE_WITHACK,
        bufferId = ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK,
    )

    suspend fun sendVideoEnable(enable: Boolean): Boolean = sendArCommand(
        payload = ArCommand.videoEnable(enable),
        dataType = ArsdkTransport.DATA_TYPE_WITHACK,
        bufferId = ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK,
    )

    /** Demande au SC2 (ou drone) de pousser tous ses *StateChanged. */
    suspend fun sendAllStates(prj: Int = ArsdkIds.PRJ_SKYCTRL): Boolean {
        val tuple = if (prj == ArsdkIds.PRJ_SKYCTRL) ArsdkIds.SKYCTRL_ALL_STATES
            else ArsdkIds.COMMON_ALL_STATES
        return sendArCommand(
            payload = ArCommand.noArgs(tuple.first, tuple.second, tuple.third),
            dataType = ArsdkTransport.DATA_TYPE_WITHACK,
            bufferId = ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK,
        )
    }

    suspend fun sendTakeoff(): Boolean = sendArCommand(
        payload = ArCommand.takeoff(),
        dataType = ArsdkTransport.DATA_TYPE_WITHACK,
        bufferId = ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK,
    )

    suspend fun sendLanding(): Boolean = sendArCommand(
        payload = ArCommand.landing(),
        dataType = ArsdkTransport.DATA_TYPE_WITHACK,
        bufferId = ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK,
    )

    // Emergency passe par le canal HIGHPRIO pour shortcut la queue normale.
    suspend fun sendEmergency(): Boolean = sendArCommand(
        payload = ArCommand.emergency(),
        dataType = ArsdkTransport.DATA_TYPE_WITHACK,
        bufferId = ArsdkTransport.BUFFER_ID_C2D_CMD_HIGHPRIO,
    )

    suspend fun sendFlatTrim(): Boolean = sendArCommand(
        payload = ArCommand.flatTrim(),
        dataType = ArsdkTransport.DATA_TYPE_WITHACK,
        bufferId = ArsdkTransport.BUFFER_ID_C2D_CMD_WITHACK,
    )

    /** Envoie un PCMD instantané (un seul shot — pour la boucle, voir startPilotingLoop). */
    suspend fun sendPcmd(roll: Int, pitch: Int, yaw: Int, gaz: Int, timestamp: Int): Boolean {
        val flag = if (roll != 0 || pitch != 0) 1 else 0
        return sendArCommand(
            payload = ArCommand.pcmd(flag, roll, pitch, yaw, gaz, timestamp),
            dataType = ArsdkTransport.DATA_TYPE_NOACK,
            bufferId = ArsdkTransport.BUFFER_ID_C2D_CMD_NOACK,
        )
    }

    /** Inputs pilotage courants, lus par la boucle PCMD. Tous dans [-100, 100]. */
    data class PilotingInput(val roll: Int = 0, val pitch: Int = 0, val yaw: Int = 0, val gaz: Int = 0)

    private val _pilotingInput = MutableStateFlow(PilotingInput())
    val pilotingInput: StateFlow<PilotingInput> = _pilotingInput.asStateFlow()

    fun setPilotingInput(input: PilotingInput) {
        _pilotingInput.value = PilotingInput(
            roll = input.roll.coerceIn(-100, 100),
            pitch = input.pitch.coerceIn(-100, 100),
            yaw = input.yaw.coerceIn(-100, 100),
            gaz = input.gaz.coerceIn(-100, 100),
        )
    }

    private val _pilotingActive = MutableStateFlow(false)
    val pilotingActive: StateFlow<Boolean> = _pilotingActive.asStateFlow()

    private var pilotingJob: Job? = null

    /**
     * Démarre l'envoi PCMD à 20 Hz (50 ms). Le drone considère qu'il n'y a plus de pilote
     * si aucun PCMD ne passe pendant ~200 ms → on tient cette cadence tant que le toggle est ON.
     */
    fun startPilotingLoop() {
        if (pilotingJob?.isActive == true) return
        _pilotingActive.value = true
        val started = System.currentTimeMillis()
        pilotingJob = scope.launch {
            while (isActive && _pilotingActive.value) {
                val input = _pilotingInput.value
                val ts = (System.currentTimeMillis() - started).toInt()
                sendPcmd(input.roll, input.pitch, input.yaw, input.gaz, ts)
                delay(50)
            }
        }
    }

    fun stopPilotingLoop() {
        _pilotingActive.value = false
        pilotingJob?.cancel()
        pilotingJob = null
    }

    suspend fun sendConnect(deviceId: String, ctrlName: String = "BebopApp"): Boolean {
        // ARStream v1 (Bebop 2 via SC2 USB AOA) — surtout NE PAS déclarer
        // arstream2_client_stream_port : ça basculerait le drone en v2 (RTP UDP direct
        // vers client:55004) qui ne traverse JAMAIS le SC2 MUX.
        // Les paramètres ci-dessous viennent de ARDISCOVERY_DEVICE_Usb.c (MPP_*) :
        // - d2c_port 43210 (MPP_DEVICE_TO_CONTROLLER_PORT)
        // - fragments ~65 KB max 4 (config ARStream v1 par défaut)
        // - arstream_max_ack_interval -1 = pas de re-ack périodique
        // Le drone enverra ses fragments H.264 sur buffer 125 du chan 1 MUX.
        val json = """{"controller_type":"Phone","controller_name":"$ctrlName",""" +
            """"d2c_port":43210,""" +
            """"arstream_fragment_size":65000,""" +
            """"arstream_fragment_maximum_number":4,""" +
            """"arstream_max_ack_interval":-1,""" +
            """"proto_v_min":1,"proto_v_max":1}"""
        val payload = PompEncoder()
            .str(ctrlName)
            .str("Phone")
            .str(deviceId)
            .str(json)
            .build(msgid = 1)
        return sendPomp(chanid = 3, payload = payload)
    }

    /** Envoie un bloc de bytes au SC2. Renvoie true si écrit, false sinon. */
    suspend fun write(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val t = transport ?: return@withContext false
        runCatching {
            t.output.write(bytes)
            t.output.flush()
            _bytesOut.value += bytes.size
            true
        }.getOrElse {
            _state.value = AoaState.Error("écriture: ${it.message}")
            false
        }
    }

    private fun openInternal(accessory: UsbAccessory) {
        disconnect()
        val t = AoaTransport.open(usbManager, accessory)
        if (t == null) {
            _state.value = AoaState.Error("openAccessory() a retourné null")
            return
        }
        transport = t
        _frameStats.value = emptyMap()
        _resyncs.value = 0
        _piloting.value = null
        _pompDecodeFailures.value = 0
        _ctrlHistory.value = emptyList()
        _lastPomp.value = emptyMap()
        _devices.value = emptyList()
        _connResp.value = null
        _videoFrameCount.value = 0L
        _videoLastFrameSize.value = 0
        _videoLastFrameFlags.value = 0
        _droneBatteryPercent.value = null
        _sc2BatteryPercent.value = null
        _droneFirmware.value = null
        _videoRecordState.value = null
        _videoEnableState.value = null
        _videoStreamMode.value = null
        _videoV1Frames.value = 0L
        _videoV1LastSize.value = 0
        _videoV1LastHead.value = null
        _arCmdStats.value = emptyMap()
        _arCmdLast.value = null
        _transportStats.value = emptyMap()
        _chan1Raw.value = null
        _state.value = AoaState.Open(accessory)
        Log.i(TAG, "AOA ouvert: ${accessory.manufacturer}/${accessory.model}")
        readerJob = scope.launch { readLoop(t) }
    }

    private suspend fun readLoop(t: AoaTransport) = withContext(Dispatchers.IO) {
        val buf = ByteArray(16 * 1024)
        try {
            while (isActive && !t.isClosed) {
                val n = t.input.read(buf)
                if (n <= 0) break
                _bytesIn.value += n
                dumpOut?.let { out ->
                    runCatching {
                        out.write(buf, 0, n)
                        _dump.update { it?.copy(bytes = it.bytes + n) }
                    }
                }
                val frames = decoder.feed(buf, n)
                if (frames.isNotEmpty()) onFrames(frames)
                if (decoder.resyncs != _resyncs.value) _resyncs.value = decoder.resyncs
                appendLog(buf, n)
            }
            if (!t.isClosed) _state.value = AoaState.Error("EOF côté SC2")
        } catch (e: Throwable) {
            if (!t.isClosed) _state.value = AoaState.Error("lecture: ${e.message}")
        }
    }

    private fun onFrames(frames: List<MuxFrame>) {
        _frameStats.update { current ->
            val next = current.toMutableMap()
            for (f in frames) next[f.chanid] = (next[f.chanid] ?: 0L) + 1L
            next
        }
        for (f in frames) {
            if (f.chanid == 0) {
                MuxCtrlMessage.decode(f.payload)?.let { msg ->
                    _ctrlHistory.update { list -> (list + msg).takeLast(50) }
                    handleCtrlMessage(msg)
                }
                continue
            }
            if (f.chanid == 1) { handleChan1(f.payload); continue }
            if (f.chanid == MUX_ARSDK_CHANNEL_ID_STREAM_DATA) {
                if (_frameStats.value[MUX_ARSDK_CHANNEL_ID_STREAM_DATA] == 1L) {
                    Log.i(TAG, "premier paquet chan 4 reçu (${f.payload.size}B)")
                }
                val take4 = f.payload.size.coerceAtMost(32)
                _chan4RawHead.value = "[${f.payload.size}B] " +
                    f.payload.copyOfRange(0, take4).joinToString(" ") { "%02x".format(it) }
                val pomp = PompMessage.decode(f.payload)
                val rtpBuf = pomp?.takeIf { it.msgid == MUX_ARSDK_MSG_ID_RTP_DATA }
                    ?.args?.getOrNull(1) as? PompArg.Buf
                if (rtpBuf != null) {
                    rtpDepayloader.feed(rtpBuf.value)
                } else if (f.payload.size >= 12 && (f.payload[0].toInt() and 0xC0) == 0x80) {
                    rtpDepayloader.feed(f.payload)
                } else {
                    arStreamReader.feed(f.payload)
                }
                val s = rtpDepayloader
                _rtpStats.value = Triple(s.packetsIn, s.packetsDropped, s.nalsOut)
                continue
            }
            if (f.chanid == MUX_ARSDK_CHANNEL_ID_STREAM_CONTROL) continue  // RTCP, ignoré
            if (f.chanid == MUX_ARSDK_CHANNEL_ID_RTP) {
                // Chan 6 (RTP) : selon firmware peut être du POMP-wrapped ou du raw RTP.
                val pomp = PompMessage.decode(f.payload)
                val rtpBuf = pomp?.args?.getOrNull(1) as? PompArg.Buf
                val data = rtpBuf?.value ?: f.payload
                rtpDepayloader.feed(data)
                val s = rtpDepayloader
                _rtpStats.value = Triple(s.packetsIn, s.packetsDropped, s.nalsOut)
                continue
            }
            // Slave chanid alloué dynamiquement par le SC2 en réponse à nos
            // CHANNEL_OPEN(IP_MASTER) — on ne peut pas dériver master mathématiquement
            // (formule firmware ≠ formule libmux récent), donc on consulte slaveLabels
            // qu'on a populé à la réception du CHANNEL_OPEN type=IP_SLAVE.
            val label = slaveLabels[f.chanid]
            if (label != null) {
                if (label == SLAVE_LABEL_VIDEO) {
                    rtpDepayloader.feed(f.payload)
                    val s = rtpDepayloader
                    _rtpStats.value = Triple(s.packetsIn, s.packetsDropped, s.nalsOut)
                }
                // SLAVE_LABEL_CONTROL : RTCP, on ignore pour l'instant
                continue
            }
            val pomp = PompMessage.decode(f.payload)
            if (pomp == null) {
                _pompDecodeFailures.value = _pompDecodeFailures.value + 1
                continue
            }
            _lastPomp.update { it + (f.chanid to pomp) }
            when {
                f.chanid == 2 && pomp.msgid == 2 -> parseDeviceAdded(pomp)?.let { dev ->
                    _devices.update { list -> (list.filterNot { it.id == dev.id }) + dev }
                }
                f.chanid == 2 && pomp.msgid == 3 -> parseDeviceAdded(pomp)?.let { dev ->
                    _devices.update { list -> list.filterNot { it.id == dev.id } }
                }
                f.chanid == 3 && pomp.msgid == 2 -> parseConnResp(pomp)?.let { _connResp.value = it }
                f.chanid == 20 && pomp.msgid == 2 && pomp.args.size == 5 -> {
                    val vals = pomp.args.mapNotNull { (it as? PompArg.I8)?.value?.toInt() }
                    if (vals.size == 5) {
                        _piloting.value = PilotingInfo(vals[0], vals[1], vals[2], vals[3], vals[4])
                    }
                }
            }
        }
    }

    /**
     * Associe les CHANNEL_OPEN type=IP_SLAVE reçus aux labels qu'on a réservés
     * dans `sendOpenIpProxyUdp()`. Le SC2 envoie un CHANNEL_OPEN par slave qu'il
     * crée, dans le même ordre que nos requêtes (FIFO).
     */
    private fun handleCtrlMessage(msg: MuxCtrlMessage) {
        if (msg.id == MuxCtrlMessage.ID_CHANNEL_OPEN &&
            msg.args.isNotEmpty() &&
            msg.args[0] == MuxCtrlMessage.CHANNEL_TYPE_IP_SLAVE) {
            val label = pendingSlaveLabels.removeFirstOrNull() ?: "slave_${msg.chanid}"
            slaveLabels[msg.chanid] = label
            _slaveChanids.value = slaveLabels.toMap()
        }
    }

    private fun handleChan1(payload: ByteArray) {
        val take = payload.size.coerceAtMost(80)
        _chan1Raw.value = "[${payload.size}B] " +
            payload.copyOfRange(0, take).joinToString(" ") { "%02x".format(it) } +
            if (payload.size > take) " …" else ""
        if (payload.size < ArsdkTransport.HEADER_SIZE) return

        val frames = ArsdkTransport.decodeAll(payload)
        for (frame in frames) {
            _transportStats.update { map ->
                val key = frame.dataType to frame.bufferId
                map + (key to ((map[key] ?: 0L) + 1L))
            }
            if (frame.bufferId == MPP_D2C_VIDEO_DATA_ID) {
                _videoV1Frames.value = _videoV1Frames.value + 1
                _videoV1LastSize.value = frame.body.size + ArsdkTransport.HEADER_SIZE
                val t = frame.body.size.coerceAtMost(24)
                _videoV1LastHead.value = frame.body.copyOfRange(0, t).joinToString(" ") { "%02x".format(it) }
            }
            if (frame.dataType == ArsdkTransport.DATA_TYPE_WITHACK) {
                val ackBuf = frame.bufferId or ArsdkTransport.BUFFER_ID_ACKOFF
                val ack = ArsdkTransport.encode(ArsdkTransport.DATA_TYPE_ACK, ackBuf, frame.seq, ByteArray(0))
                scope.launch { sendPomp(chanid = 1, payload = ack) }
            }
            if (frame.dataType == ArsdkTransport.DATA_TYPE_ACK) continue
            if (frame.bufferId == ArsdkTransport.BUFFER_ID_PING ||
                frame.bufferId == ArsdkTransport.BUFFER_ID_PONG) continue
            parseArCommand(frame.body)
        }
    }

    private fun parseArCommand(body: ByteArray) {
        if (body.size < 4) return
        val prj = body[0].toInt() and 0xff
        val cls = body[1].toInt() and 0xff
        val cmd = (body[2].toInt() and 0xff) or ((body[3].toInt() and 0xff) shl 8)
        val tuple = Triple(prj, cls, cmd)
        val args = body.copyOfRange(4, body.size)
        _arCmdLast.value = ArCommandHeader(prj, cls, cmd, args)
        val prevCount = _arCmdStats.value[tuple] ?: 0L
        _arCmdStats.update { map -> map + (tuple to (prevCount + 1L)) }
        if (prevCount == 0L) Log.d(TAG, "ARCmd nouveau tuple: prj=$prj cls=$cls cmd=$cmd args=${args.size}B")
        if (tuple == ArsdkIds.COMMON_PRODUCT_VERSION) {
            val sw = readCString(args, 0)
            val hw = if (sw != null) readCString(args, sw.length + 1) else null
            if (sw != null) {
                _droneFirmware.value = if (hw != null) "$sw  /  hw $hw" else sw
            }
            return
        }
        when (tuple) {
            ArsdkIds.COMMON_BATTERY -> {
                if (args.isNotEmpty()) {
                    val pct = args[0].toInt() and 0xff
                    Log.i(TAG, "batterie drone: $pct%")
                    _droneBatteryPercent.value = pct
                }
            }
            ArsdkIds.SKYCTRL_BATTERY -> {
                if (args.isNotEmpty()) {
                    val pct = args[0].toInt() and 0xff
                    Log.i(TAG, "batterie SC2: $pct%")
                    _sc2BatteryPercent.value = pct
                }
            }
            ArsdkIds.ARDRONE3_VIDEO_RECORD_STATE ->
                if (args.size >= 4) _videoRecordState.value = readU32Le(args, 0)
            ArsdkIds.ARDRONE3_VIDEO_ENABLE_CHANGED ->
                if (args.size >= 4) _videoEnableState.value = readU32Le(args, 0)
            ArsdkIds.ARDRONE3_VIDEO_STREAM_MODE_CHANGED ->
                if (args.size >= 4) _videoStreamMode.value = readU32Le(args, 0)
        }
    }

    private fun readU32Le(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xff) or
            ((buf[offset + 1].toInt() and 0xff) shl 8) or
            ((buf[offset + 2].toInt() and 0xff) shl 16) or
            ((buf[offset + 3].toInt() and 0xff) shl 24)

    private fun readCString(buf: ByteArray, offset: Int): String? {
        var end = offset
        while (end < buf.size && buf[end] != 0.toByte()) end++
        if (end >= buf.size) return null
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    private fun parseDeviceAdded(pomp: PompMessage): ArsdkDevice? {
        if (pomp.args.size != 3) return null
        val name = (pomp.args[0] as? PompArg.Str)?.value ?: return null
        val type = (pomp.args[1] as? PompArg.U32)?.value ?: return null
        val id = (pomp.args[2] as? PompArg.Str)?.value ?: return null
        return ArsdkDevice(name, type, id)
    }

    private fun parseConnResp(pomp: PompMessage): ConnResp? {
        if (pomp.args.size != 2) return null
        val status = (pomp.args[0] as? PompArg.I32)?.value ?: return null
        val json = (pomp.args[1] as? PompArg.Str)?.value ?: return null
        return ConnResp(status, json)
    }

    private fun appendLog(buf: ByteArray, n: Int) {
        val take = n.coerceAtMost(64)
        val hex = buf.copyOfRange(0, take).joinToString(" ") { "%02x".format(it) }
        val line = "[${n}B] $hex${if (n > take) "…" else ""}"
        _logTail.update { list -> (list + line).takeLast(20) }
    }

    companion object {
        private const val TAG = "Bebop"
    }
}

