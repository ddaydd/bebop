package io.dayd.bebop.ui

import android.app.Application
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val config: DroneConfig) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class HostProbe(val host: String, val reachable: Boolean? = null)

class DroneViewModel(app: Application) : AndroidViewModel(app) {

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

    init {
        refreshDiagnostic()
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
    val videoRecordState: StateFlow<Int?> = aoaController.videoRecordState
    val videoEnableState: StateFlow<Int?> = aoaController.videoEnableState
    val videoStreamMode: StateFlow<Int?> = aoaController.videoStreamMode

    fun aoaToggleRecord() {
        viewModelScope.launch {
            val recording = aoaController.videoRecordState.value == 1
            aoaController.sendVideoRecord(!recording)
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
    val pilotingActive: StateFlow<Boolean> = aoaController.pilotingActive

    fun setPilotingInput(roll: Int, pitch: Int, yaw: Int, gaz: Int) {
        aoaController.setPilotingInput(AoaController.PilotingInput(roll, pitch, yaw, gaz))
    }

    fun startPilotingLoop() = aoaController.startPilotingLoop()
    fun stopPilotingLoop() = aoaController.stopPilotingLoop()

    fun togglePilotingLoop() {
        if (aoaController.pilotingActive.value) stopPilotingLoop()
        else startPilotingLoop()
    }

    fun aoaTakeoff() { viewModelScope.launch { aoaController.sendTakeoff() } }
    fun aoaLanding() { viewModelScope.launch { aoaController.sendLanding() } }
    fun aoaEmergency() { viewModelScope.launch { aoaController.sendEmergency() } }
    fun aoaFlatTrim() { viewModelScope.launch { aoaController.sendFlatTrim() } }

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
        if (surface != null) {
            val dec = H264Decoder(surface)
            decoder = dec
            aoaController.videoFrameSink = { data, key ->
                dec.feed(data, key)
                _decoderConfigured.value = dec.configured
                _decoderQueued.value = dec.framesQueued
                _decoderSize.value = dec.outputWidth to dec.outputHeight
                _decoderError.value = dec.lastError
            }
        }
    }

    override fun onCleared() {
        aoaController.videoFrameSink = null
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
