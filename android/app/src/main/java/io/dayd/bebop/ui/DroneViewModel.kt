package io.dayd.bebop.ui

import android.app.Application
import io.dayd.bebop.FileLogger as Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.dayd.bebop.aoa.AoaController
import io.dayd.bebop.aoa.AoaState
import io.dayd.bebop.aoa.DumpState
import io.dayd.bebop.aoa.ArsdkDevice
import io.dayd.bebop.aoa.ConnResp
import io.dayd.bebop.aoa.PilotingInfo
import android.view.Surface
import io.dayd.bebop.mux.MuxCtrlMessage
import io.dayd.bebop.mux.PompMessage
import io.dayd.bebop.video.H264Decoder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.dayd.bebop.network.ArDiscovery
import io.dayd.bebop.network.DroneConfig
import io.dayd.bebop.network.InterfaceInfo
import io.dayd.bebop.network.NetworkInspector
import io.dayd.bebop.network.UsbInspector
import io.dayd.bebop.network.UsbReport
import io.dayd.bebop.network.getGatewaysFromContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val config: DroneConfig) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class HostProbe(val host: String, val reachable: Boolean? = null)

class DroneViewModel(app: Application) : AndroidViewModel(app) {

    private val directController = io.dayd.bebop.network.DirectController(app.applicationContext)
    val directConnected: StateFlow<Boolean> = directController.connected
    val directDroneBattery: StateFlow<Int?> = directController.droneBattery
    val directDroneStatus: StateFlow<String> = directController.droneStatus
    val directDroneFirmware: StateFlow<String?> = directController.droneFirmware
    val directPacketsIn: StateFlow<Long> = directController.packetsIn
    val directVideoFrames: StateFlow<Long> = directController.videoFrames
    val directRtpStats: StateFlow<Triple<Long, Long, Long>> = directController.rtpStats
    val directVideoPath: StateFlow<String?> = directController.videoPath
    val directMaxRotationSpeed = directController.maxRotationSpeed
    val directMaxVerticalSpeed = directController.maxVerticalSpeed
    val directMaxTilt = directController.maxTilt
    val directAlertState: StateFlow<Int> = directController.alertState
    val directLinkLost: StateFlow<Boolean> = directController.linkLost
    val directGpsFix: StateFlow<Boolean> = directController.gpsFix
    val directHomeAvailable: StateFlow<Boolean> = directController.homeAvailable
    val directNavigateHomeState: StateFlow<Int?> = directController.navigateHomeState

    /** Lance ou annule le retour au point de décollage (Wi-Fi direct uniquement). */
    fun navigateHome(start: Boolean) {
        if (useDirect()) directController.sendNavigateHome(start)
    }

    fun directPerfModerate() = directController.applyPerformance(0.5f)
    fun directPerfMax() = directController.applyPerformance(1f)
    fun directStartVideo() { directController.startVideo() }
    fun directStopVideo() { directController.stopVideo() }
    val directLastPacketAt: StateFlow<Long> = directController.lastPacketAt
    val directLastTuple: StateFlow<String?> = directController.lastTuple

    fun directConnect() { viewModelScope.launch { directController.connect() } }
    fun directDisconnect() { directController.disconnect() }
    fun directAllStates() { directController.requestAllStates() }

    private val aoaController = AoaController(app.applicationContext)
    val aoaState: StateFlow<AoaState> = aoaController.state
    val aoaBytesIn: StateFlow<Long> = aoaController.bytesIn
    val aoaBytesOut: StateFlow<Long> = aoaController.bytesOut
    val aoaLog: StateFlow<List<String>> = aoaController.logTail
    val aoaDump: StateFlow<DumpState?> = aoaController.dump
    val aoaFrameStats: StateFlow<Map<Int, Long>> = aoaController.frameStats
    val aoaResyncs: StateFlow<Int> = aoaController.resyncs
    val aoaPiloting: StateFlow<PilotingInfo?> = aoaController.piloting
    val aoaPompFailures: StateFlow<Int> = aoaController.pompDecodeFailures
    val aoaCtrlHistory: StateFlow<List<MuxCtrlMessage>> = aoaController.ctrlHistory
    val aoaLastPomp: StateFlow<Map<Int, PompMessage>> = aoaController.lastPomp
    val aoaDevices: StateFlow<List<ArsdkDevice>> = aoaController.devices
    val aoaConnResp: StateFlow<ConnResp?> = aoaController.connResp
    val aoaVideoFrames: StateFlow<Long> = aoaController.videoFrameCount
    val aoaVideoLastSize: StateFlow<Int> = aoaController.videoLastFrameSize
    val aoaVideoLastFlags: StateFlow<Int> = aoaController.videoLastFrameFlags
    val droneBatteryPercent: StateFlow<Int?> = aoaController.droneBatteryPercent
    val sc2BatteryPercent: StateFlow<Int?> = aoaController.sc2BatteryPercent

    /**
     * Batterie drone quelle que soit la voie utilisée (Wi-Fi direct ou SC2/AOA).
     * Le direct prime : quand il est actif, c'est lui qui a la valeur fraîche.
     */
    val anyDroneBattery: StateFlow<Int?> =
        combine(directController.droneBattery, aoaController.droneBatteryPercent) { direct, aoa ->
            direct ?: aoa
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Frames vidéo reçues, toutes voies confondues (Wi-Fi direct ou SC2). */
    val anyVideoFrames: StateFlow<Long> =
        combine(directController.videoFrames, aoaController.videoFrameCount) { direct, aoa ->
            if (direct > 0) direct else aoa
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val arCmdStats: StateFlow<Map<Triple<Int, Int, Int>, Long>> = aoaController.arCmdStats
    val arCmdLast: StateFlow<io.dayd.bebop.arsdk.ArCommandHeader?> = aoaController.arCmdLast
    val transportStats: StateFlow<Map<Pair<Int, Int>, Long>> = aoaController.transportStats
    val chan1Raw: StateFlow<String?> = aoaController.chan1Raw

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _host = MutableStateFlow("192.168.42.1")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _interfaces = MutableStateFlow<List<InterfaceInfo>>(emptyList())
    val interfaces: StateFlow<List<InterfaceInfo>> = _interfaces.asStateFlow()

    private val _probes = MutableStateFlow<List<HostProbe>>(emptyList())
    val probes: StateFlow<List<HostProbe>> = _probes.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _usb = MutableStateFlow(UsbReport(emptyList(), emptyList()))
    val usb: StateFlow<UsbReport> = _usb.asStateFlow()

    private val _autoStatus = MutableStateFlow("Démarrage…")
    val autoStatus: StateFlow<String> = _autoStatus.asStateFlow()

    private var autoConnectJob: Job? = null

    init {
        refreshDiagnostic()
        retryAutoConnect()
    }

    /**
     * Choisit la voie puis se connecte. Le SC2 branché en USB prime : c'est un
     * choix matériel explicite. Sinon on tente le Wi-Fi direct, qui est le mode
     * nominal tant que le module Wi-Fi du SC2 est HS.
     */
    fun retryAutoConnect() {
        if (autoConnectJob?.isActive == true) return
        autoConnectJob = viewModelScope.launch {
            val hasSc2 = runCatching { UsbInspector.snapshot(getApplication()) }
                .getOrNull()?.accessories?.isNotEmpty() == true
            if (hasSc2) {
                Log.i(TAG, "autoConnect: SC2 détecté en USB — voie AOA")
                runCatching { autoConnect() }.onFailure {
                    Log.w(TAG, "autoConnect AOA échoué: ${it.message}")
                    _autoStatus.value = "Erreur SC2 : ${it.message}"
                }
            } else {
                Log.i(TAG, "autoConnect: pas de SC2 — voie Wi-Fi directe")
                autoConnectDirect()
            }
        }
    }

    private suspend fun autoConnectDirect() {
        // Reporte la progression de DirectController dans l'overlay du pilotage,
        // sinon l'écran resterait sur un message figé pendant les 6 tentatives.
        val mirror = viewModelScope.launch {
            directController.droneStatus.collect { _autoStatus.value = it }
        }
        try {
            directController.connect()
        } finally {
            mirror.cancel()
        }
        _autoStatus.value = if (directController.connected.value) {
            directController.droneStatus.value
        } else {
            "Drone introuvable — connecter le Pixel au Wi-Fi Bebop2-…"
        }
    }

    private suspend fun autoConnect() {
        Log.i(TAG, "autoConnect: début de la séquence")
        _autoStatus.value = "Ouverture USB AOA…"
        if (aoaController.state.value !is AoaState.Open) {
            aoaController.connectFirstAvailable()
            val aoaResult = aoaController.state.first { it is AoaState.Open || it is AoaState.Error }
            if (aoaResult is AoaState.Error) {
                Log.w(TAG, "autoConnect: AOA échoué — ${aoaResult.message}")
                _autoStatus.value = "Erreur AOA — brancher le SC2"
                return
            }
        }
        Log.i(TAG, "autoConnect: AOA ouvert")

        _autoStatus.value = "Handshake MUX…"
        if (aoaController.ctrlHistory.value.isEmpty()) {
            aoaController.sendHandshake(isAck = false)
            aoaController.ctrlHistory.first { it.isNotEmpty() }
        }
        Log.i(TAG, "autoConnect: handshake OK — canaux MUX ouverts")

        _autoStatus.value = "Découverte des appareils…"
        if (aoaController.devices.value.isEmpty()) {
            aoaController.sendDiscover()
        }
        val devices = aoaController.devices.first { it.isNotEmpty() }
        Log.i(TAG, "autoConnect: ${devices.size} device(s) trouvé(s) — ${devices.first().name}")

        val dev = devices.first()
        _autoStatus.value = "Connexion à ${dev.name}…"
        if (aoaController.connResp.value?.status != 0) {
            aoaController.sendConnect(dev.id)
        }
        val resp = aoaController.connResp.first { it != null && it.status == 0 }
        Log.i(TAG, "autoConnect: CONN_RESP ok — ${resp!!.json.take(80)}")

        _autoStatus.value = "Ouverture flux vidéo…"
        kotlinx.coroutines.delay(300)
        val sc2ok = aoaController.sendAllStates(io.dayd.bebop.arsdk.ArsdkIds.PRJ_SKYCTRL)
        val droneOk = aoaController.sendAllStates(io.dayd.bebop.arsdk.ArsdkIds.PRJ_COMMON)
        Log.i(TAG, "autoConnect: AllStates SC2=$sc2ok drone=$droneOk")
        aoaController.sendOpenStreamChannels()
        kotlinx.coroutines.delay(200)
        aoaController.sendVideoStreamMode(0)
        kotlinx.coroutines.delay(200)
        aoaController.sendVideoEnable(true)
        Log.i(TAG, "autoConnect: séquence complète — vidéo demandée")
        _autoStatus.value = "Connecté"
    }

    companion object {
        private const val TAG = "Bebop"
    }

    fun setHost(value: String) {
        _host.value = value
    }

    fun connect() {
        if (_state.value is ConnectionState.Connecting) return
        _state.value = ConnectionState.Connecting
        viewModelScope.launch {
            _state.value = runCatching { ArDiscovery.connect(host = _host.value) }
                .fold(
                    onSuccess = { ConnectionState.Connected(it) },
                    onFailure = { ConnectionState.Error(it.message ?: it::class.java.simpleName) },
                )
        }
    }

    fun reset() {
        _state.value = ConnectionState.Idle
    }

    fun aoaConnect() = aoaController.connectFirstAvailable()
    fun aoaDisconnect() = aoaController.disconnect()
    fun aoaToggle() {
        if (aoaController.state.value is io.dayd.bebop.aoa.AoaState.Open) aoaDisconnect()
        else aoaConnect()
    }

    fun aoaSendHandshake() {
        viewModelScope.launch { aoaController.sendHandshake(isAck = false) }
    }

    fun aoaSendDiscover() {
        viewModelScope.launch { aoaController.sendDiscover() }
    }

    fun aoaConnectDevice(deviceId: String) {
        viewModelScope.launch {
            aoaController.sendConnect(deviceId)
            // Le drone ne push BatteryStateChanged que lorsque la valeur change.
            // Pour avoir la batterie tout de suite après Connect, on demande aux
            // 2 peers (SC2 et drone) de re-pousser leurs states.
            kotlinx.coroutines.delay(500)
            aoaController.sendAllStates(io.dayd.bebop.arsdk.ArsdkIds.PRJ_SKYCTRL)
            aoaController.sendAllStates(io.dayd.bebop.arsdk.ArsdkIds.PRJ_COMMON)
        }
    }

    fun aoaStartVideo() {
        viewModelScope.launch {
            aoaController.sendVideoStreamMode(0)
            kotlinx.coroutines.delay(200)
            aoaController.sendVideoEnable(true)
        }
    }

    fun aoaStopVideo() {
        viewModelScope.launch { aoaController.sendVideoEnable(false) }
    }

    /**
     * Ouvre les 2 IP proxies UDP via libmux : drone:5004 (RTP) et drone:5005 (RTCP).
     * Sans ces 2 ctrl messages, le SC2 ne forward jamais la vidéo (le drone stream en
     * RTP, pas en ARStream v1). À appeler APRÈS Connect device et AVANT Start video.
     */
    fun aoaOpenVideoProxy() {
        // CHANNEL_OPEN(4, NORMAL) + CHANNEL_OPEN(5, NORMAL) — déclenche le forward
        // RTP/RTCP du SC2 (vs IP proxy qu'on avait essayé avant, mauvaise piste).
        viewModelScope.launch { aoaController.sendOpenStreamChannels() }
    }

    val rtpStats: StateFlow<Triple<Long, Long, Long>> = aoaController.rtpStats
    val slaveChanids: StateFlow<Map<Int, String>> = aoaController.slaveChanids
    val ctrlSent: StateFlow<Map<Int, Long>> = aoaController.ctrlSent

    val videoV1Frames: StateFlow<Long> = aoaController.videoV1Frames
    val videoV1LastSize: StateFlow<Int> = aoaController.videoV1LastSize
    val videoV1LastHead: StateFlow<String?> = aoaController.videoV1LastHead

    val chan4RawHead: StateFlow<String?> = aoaController.chan4RawHead
    val assembledFrameHead: StateFlow<String?> = aoaController.assembledFrameHead
    val droneFirmware: StateFlow<String?> = aoaController.droneFirmware
    val videoRecordState: StateFlow<Int?> =
        combine(directController.videoRecordState, aoaController.videoRecordState) { direct, aoa ->
            direct ?: aoa
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val videoEnableState: StateFlow<Int?> = aoaController.videoEnableState
    val videoStreamMode: StateFlow<Int?> = aoaController.videoStreamMode

    val sc2WifiList: StateFlow<List<AoaController.Sc2WifiEntry>> = aoaController.sc2WifiList
    val sc2WifiConnected: StateFlow<String?> = aoaController.sc2WifiConnected
    val sc2DroneConnectionState: StateFlow<Int?> = aoaController.sc2DroneConnectionState
    val dmConnectionState: StateFlow<AoaController.DmConnectionState?> = aoaController.dmConnectionState
    val dmDroneList: StateFlow<List<AoaController.DmDroneItem>> = aoaController.dmDroneList

    fun aoaSc2WifiScan() { viewModelScope.launch { aoaController.sendSc2WifiScan() } }
    fun aoaSc2WifiConnect(bssid: String, ssid: String, passphrase: String = "") {
        viewModelScope.launch { aoaController.sendSc2WifiConnect(bssid, ssid, passphrase) }
    }
    fun aoaSc2WifiRequestCurrent() { viewModelScope.launch { aoaController.sendSc2WifiRequestCurrent() } }
    fun aoaDmDiscoverDrones() { viewModelScope.launch { aoaController.sendDmDiscoverDrones() } }
    fun aoaDmConnect(serial: String, key: String = "") { viewModelScope.launch { aoaController.sendDmConnect(serial, key) } }

    fun aoaAutoConnect() = retryAutoConnect()

    fun aoaToggleRecord() {
        if (useDirect()) {
            directController.sendVideoRecord(directController.videoRecordState.value != 1)
        } else viewModelScope.launch {
            aoaController.sendVideoRecord(aoaController.videoRecordState.value != 1)
        }
    }
    val c2dSent: StateFlow<Map<String, Long>> = aoaController.c2dSent

    fun aoaRequestSc2States() {
        viewModelScope.launch { aoaController.sendAllStates(io.dayd.bebop.arsdk.ArsdkIds.PRJ_SKYCTRL) }
    }

    fun aoaRequestDroneStates() {
        viewModelScope.launch { aoaController.sendAllStates(io.dayd.bebop.arsdk.ArsdkIds.PRJ_COMMON) }
    }

    val pilotingInput: StateFlow<AoaController.PilotingInput> = aoaController.pilotingInput

    /**
     * En Wi-Fi direct la boucle PCMD tourne en permanence (elle maintient la
     * liaison), donc rien à « démarrer ». On garde malgré tout un armement
     * explicite : sans lui les boutons DÉCOLLER/URGENCE seraient exposés en
     * permanence, à un tap d'un décollage involontaire.
     */
    private val _directArmed = MutableStateFlow(false)

    val pilotingActive: StateFlow<Boolean> =
        combine(aoaController.pilotingActive, _directArmed) { aoa, direct -> aoa || direct }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** État de vol du drone (Wi-Fi direct) — null si inconnu. */
    val directFlyingState: StateFlow<Int?> = directController.flyingState

    /**
     * Voie active pour les commandes de vol. Le Wi-Fi direct prime : quand il
     * est connecté c'est lui qui parle au drone, le SC2 n'étant qu'un relais
     * alternatif. Envoyer sur les deux ferait partir chaque commande en double.
     */
    private fun useDirect(): Boolean = directController.connected.value

    /**
     * Vrai quand le drone n'est plus au sol (tout état ≠ 0 : décollage, vol,
     * atterrissage…). `null` (état inconnu) est traité comme au sol, le cas
     * prudent : au pire les sticks sont inhibés au sol, jamais en vol.
     */
    private fun airborne(): Boolean = directController.flyingState.value?.let { it != 0 } ?: false

    /** Sticks inhibés : au sol, non armé. Un frôlement de l'écran n'envoie rien. */
    val sticksInhibited: StateFlow<Boolean> =
        combine(directController.connected, _directArmed, directController.flyingState) { conn, armed, fs ->
            conn && !armed && (fs == null || fs == 0)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setPilotingInput(roll: Int, pitch: Int, yaw: Int, gaz: Int) {
        val input = AoaController.PilotingInput(roll, pitch, yaw, gaz)
        if (useDirect()) {
            // Dès que le drone est en l'air les sticks répondent toujours, armé
            // ou non : perdre le contrôle en vol serait pire que tout ce qu'un
            // garde-fou pourrait éviter.
            if (!_directArmed.value && !airborne()) {
                directController.centerSticks()
                return
            }
            directController.setPilotingInput(input)
        } else aoaController.setPilotingInput(input)
    }

    fun startPilotingLoop() {
        if (useDirect()) _directArmed.value = true
        else aoaController.startPilotingLoop()
    }

    fun stopPilotingLoop() {
        if (useDirect()) {
            _directArmed.value = false
            directController.centerSticks()
        } else aoaController.stopPilotingLoop()
    }

    fun togglePilotingLoop() {
        if (pilotingActive.value) stopPilotingLoop()
        else startPilotingLoop()
    }

    fun aoaTakeoff() {
        if (useDirect()) directController.sendTakeoff()
        else viewModelScope.launch { aoaController.sendTakeoff() }
    }

    fun aoaLanding() {
        if (useDirect()) {
            directController.centerSticks()
            directController.sendLanding()
        } else viewModelScope.launch { aoaController.sendLanding() }
    }

    fun aoaEmergency() {
        if (useDirect()) directController.sendEmergency()
        else viewModelScope.launch { aoaController.sendEmergency() }
    }
    fun aoaFlatTrim() {
        if (useDirect()) directController.sendFlatTrim()
        else viewModelScope.launch { aoaController.sendFlatTrim() }
    }

    private var decoder: H264Decoder? = null

    private val _decoderConfigured = MutableStateFlow(false)
    val decoderConfigured: StateFlow<Boolean> = _decoderConfigured.asStateFlow()

    private val _decoderQueued = MutableStateFlow(0L)
    val decoderQueued: StateFlow<Long> = _decoderQueued.asStateFlow()

    private val _decoderSize = MutableStateFlow(0 to 0)
    val decoderSize: StateFlow<Pair<Int, Int>> = _decoderSize.asStateFlow()

    private val _decoderError = MutableStateFlow<String?>(null)
    val decoderError: StateFlow<String?> = _decoderError.asStateFlow()

    fun setVideoSurface(surface: Surface?) {
        decoder?.release()
        decoder = null
        _decoderConfigured.value = false
        _decoderQueued.value = 0L
        _decoderSize.value = 0 to 0
        _decoderError.value = null
        aoaController.videoFrameSink = null
        directController.videoFrameSink = null
        if (surface != null) {
            val dec = H264Decoder(surface)
            decoder = dec
            // Même sink pour les deux voies : une seule est active à la fois.
            val sink: (ByteArray, Boolean) -> Unit = { data, key ->
                dec.feed(data, key)
                _decoderConfigured.value = dec.configured
                _decoderQueued.value = dec.framesQueued
                _decoderSize.value = dec.outputWidth to dec.outputHeight
                _decoderError.value = dec.lastError
            }
            aoaController.videoFrameSink = sink
            directController.videoFrameSink = sink
        }
    }

    override fun onCleared() {
        aoaController.videoFrameSink = null
        directController.videoFrameSink = null
        decoder?.release()
        decoder = null
        super.onCleared()
    }

    fun aoaToggleDump() {
        if (aoaController.dump.value != null) {
            aoaController.stopDump()
        } else {
            val dir: File = getApplication<Application>().getExternalFilesDir(null) ?: return
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            aoaController.startDump(File(dir, "aoa-dump-$ts.bin"))
        }
    }

    fun aoaSendProbe() {
        viewModelScope.launch {
            // ARDiscovery JSON terminé par null byte — c'est ce que le SDK Parrot envoie
            // sur le canal AOA pour initier la communication avec le SC2.
            val json = """{"controller_type":"Android","controller_name":"BebopApp","d2c_port":43210}"""
            val payload = json.toByteArray(Charsets.UTF_8) + 0.toByte()
            aoaController.write(payload)
        }
    }

    fun refreshDiagnostic() {
        viewModelScope.launch {
            _scanning.value = true
            _usb.value = runCatching { UsbInspector.snapshot(getApplication()) }
                .getOrDefault(UsbReport(emptyList(), emptyList()))
            val gateways = runCatching { getGatewaysFromContext(getApplication()) }.getOrDefault(emptyMap())
            _interfaces.value = NetworkInspector.listInterfaces(gateways)
            val candidates = NetworkInspector.candidateHosts(gateways)
            _probes.value = candidates.map { HostProbe(it) }
            _probes.value = candidates.map { host ->
                HostProbe(host, NetworkInspector.ping(host))
            }
            _scanning.value = false
        }
    }
}
