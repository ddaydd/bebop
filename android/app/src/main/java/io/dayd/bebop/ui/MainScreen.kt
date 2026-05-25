package io.dayd.bebop.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun MainScreen(vm: DroneViewModel = viewModel()) {
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = 0,
        pageCount = { 2 },
    )
    androidx.compose.foundation.pager.HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        when (page) {
            0 -> PilotScreen(vm)
            1 -> DebugScreen(vm)
        }
    }
}

@Composable
private fun DebugScreen(vm: DroneViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val host by vm.host.collectAsStateWithLifecycle()
    val interfaces by vm.interfaces.collectAsStateWithLifecycle()
    val probes by vm.probes.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val usb by vm.usb.collectAsStateWithLifecycle()
    val aoaState by vm.aoaState.collectAsStateWithLifecycle()
    val aoaBytesIn by vm.aoaBytesIn.collectAsStateWithLifecycle()
    val aoaBytesOut by vm.aoaBytesOut.collectAsStateWithLifecycle()
    val aoaLog by vm.aoaLog.collectAsStateWithLifecycle()
    val aoaDump by vm.aoaDump.collectAsStateWithLifecycle()
    val aoaFrameStats by vm.aoaFrameStats.collectAsStateWithLifecycle()
    val aoaResyncs by vm.aoaResyncs.collectAsStateWithLifecycle()
    val aoaPiloting by vm.aoaPiloting.collectAsStateWithLifecycle()
    val aoaPompFailures by vm.aoaPompFailures.collectAsStateWithLifecycle()
    val aoaCtrlHistory by vm.aoaCtrlHistory.collectAsStateWithLifecycle()
    val aoaLastPomp by vm.aoaLastPomp.collectAsStateWithLifecycle()
    val aoaDevices by vm.aoaDevices.collectAsStateWithLifecycle()
    val aoaConnResp by vm.aoaConnResp.collectAsStateWithLifecycle()
    val aoaVideoFrames by vm.aoaVideoFrames.collectAsStateWithLifecycle()
    val aoaVideoLastSize by vm.aoaVideoLastSize.collectAsStateWithLifecycle()
    val aoaVideoLastFlags by vm.aoaVideoLastFlags.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bebop 2", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                BatteryPill("Drone", vm.droneBatteryPercent.collectAsStateWithLifecycle().value)
                BatteryPill("SC2", vm.sc2BatteryPercent.collectAsStateWithLifecycle().value)
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = host,
                onValueChange = vm::setHost,
                label = { Text("Adresse drone") },
                singleLine = true,
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::connect) { Text("Connecter") }
                OutlinedButton(onClick = vm::refreshDiagnostic) { Text("Rafraîchir diag") }
            }

            when (val s = state) {
                ConnectionState.Idle -> {}
                ConnectionState.Connecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text("Handshake en cours…")
                    }
                }
                is ConnectionState.Connected -> ConnectedCard(s.config)
                is ConnectionState.Error -> {
                    Text("Erreur : ${s.message}", color = MaterialTheme.colorScheme.error)
                }
            }

            LinkStateCard(
                aoaState = aoaState,
                bytesIn = aoaBytesIn,
                bytesOut = aoaBytesOut,
                ctrlHistory = aoaCtrlHistory,
                devices = aoaDevices,
                connResp = aoaConnResp,
                piloting = aoaPiloting,
                videoFrames = aoaVideoFrames,
                droneBattery = vm.droneBatteryPercent.collectAsStateWithLifecycle().value,
                sc2Battery = vm.sc2BatteryPercent.collectAsStateWithLifecycle().value,
                droneFirmware = vm.droneFirmware.collectAsStateWithLifecycle().value,
                videoEnableState = vm.videoEnableState.collectAsStateWithLifecycle().value,
                videoStreamMode = vm.videoStreamMode.collectAsStateWithLifecycle().value,
                c2dSent = vm.c2dSent.collectAsStateWithLifecycle().value,
                arCmdStats = vm.arCmdStats.collectAsStateWithLifecycle().value,
                arCmdLast = vm.arCmdLast.collectAsStateWithLifecycle().value,
                transportStats = vm.transportStats.collectAsStateWithLifecycle().value,
                chan1Raw = vm.chan1Raw.collectAsStateWithLifecycle().value,
            )

            PilotingCard(vm)

            VideoCard(vm)

            AoaCard(
                state = aoaState,
                bytesIn = aoaBytesIn,
                bytesOut = aoaBytesOut,
                log = aoaLog,
                dump = aoaDump,
                frameStats = aoaFrameStats,
                resyncs = aoaResyncs,
                piloting = aoaPiloting,
                pompFailures = aoaPompFailures,
                ctrlHistory = aoaCtrlHistory,
                lastPomp = aoaLastPomp,
                devices = aoaDevices,
                connResp = aoaConnResp,
                videoFrames = aoaVideoFrames,
                videoLastSize = aoaVideoLastSize,
                videoLastFlags = aoaVideoLastFlags,
                onToggle = vm::aoaToggle,
                onSendProbe = vm::aoaSendProbe,
                onToggleDump = vm::aoaToggleDump,
                onSendHandshake = vm::aoaSendHandshake,
                onSendDiscover = vm::aoaSendDiscover,
                onConnectDevice = vm::aoaConnectDevice,
                onStartVideo = vm::aoaStartVideo,
                onStopVideo = vm::aoaStopVideo,
                onOpenVideoProxy = vm::aoaOpenVideoProxy,
                onRequestSc2States = vm::aoaRequestSc2States,
                onRequestDroneStates = vm::aoaRequestDroneStates,
            )

            UsbCard(usb)

            DiagnosticCard(
                interfaces = interfaces,
                probes = probes,
                scanning = scanning,
                onPickHost = vm::setHost,
            )
        }
    }
}

@Composable
private fun BatteryPill(label: String, pct: Int?) {
    val color = when {
        pct == null -> MaterialTheme.colorScheme.outline
        pct >= 60 -> Color(0xFF1B5E20)
        pct >= 25 -> Color(0xFFEF6C00)
        else -> Color(0xFFB71C1C)
    }
    val value = pct?.let { "$it%" } ?: "—"
    Text(
        "$label $value",
        color = color,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun LinkStateCard(
    aoaState: io.dayd.bebop.aoa.AoaState,
    bytesIn: Long,
    bytesOut: Long,
    ctrlHistory: List<io.dayd.bebop.mux.MuxCtrlMessage>,
    devices: List<io.dayd.bebop.aoa.ArsdkDevice>,
    connResp: io.dayd.bebop.aoa.ConnResp?,
    piloting: io.dayd.bebop.aoa.PilotingInfo?,
    videoFrames: Long,
    droneBattery: Int?,
    sc2Battery: Int?,
    droneFirmware: String?,
    videoEnableState: Int?,
    videoStreamMode: Int?,
    c2dSent: Map<String, Long>,
    arCmdStats: Map<Triple<Int, Int, Int>, Long>,
    arCmdLast: io.dayd.bebop.arsdk.ArCommandHeader?,
    transportStats: Map<Pair<Int, Int>, Long>,
    chan1Raw: String?,
) {
    val usbOk = aoaState is io.dayd.bebop.aoa.AoaState.Open
    // Le SC2 ne renvoie pas un HANDSHAKE (id=127) en retour — il ouvre directement les
    // canaux MUX (CHANNEL_OPEN id=0). Donc dès qu'on a reçu un seul ctrl_msg, le handshake
    // a forcément marché.
    val handshakeAcked = ctrlHistory.isNotEmpty()
    val sc2Responds = handshakeAcked
    val sc2AcceptedConn = connResp != null && connResp.status == 0
    // Indicateurs fiables d'un lien actif vers le drone : du trafic d'origine drone.
    // - frame ARStream reçue (le SC2 route vraiment vers le Bebop)
    // - batterie drone reçue (Common.CommonState.BatteryStateChanged pousse depuis le drone)
    // - n'importe quelle ARCommand prj=1 (Ardrone3) reçue
    val ardrone3CmdSeen = arCmdStats.keys.any { it.first == io.dayd.bebop.arsdk.ArsdkIds.PRJ_ARDRONE3 }
    val droneTrafficSeen = videoFrames > 0 || droneBattery != null || ardrone3CmdSeen
    val verdictColor = if (droneTrafficSeen) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error

    val verdict = when {
        !usbOk -> "USB AOA non ouvert — brancher le SC2 et toucher \"Ouvrir AOA\""
        !sc2Responds -> "SC2 muet — vérifier branchement USB et que SC2 est bien allumé"
        connResp == null -> "SC2 répond mais pas encore connecté — toucher \"Connect\" sur le device SC2"
        connResp.status != 0 -> "CONN_RESP refusé (status=${connResp.status}) — SC2 n'accepte pas la connexion"
        droneTrafficSeen -> {
            val proofs = buildList {
                if (videoFrames > 0) add("$videoFrames frame(s) vidéo")
                droneBattery?.let { add("batt drone $it %") }
                if (ardrone3CmdSeen) add("ARCommand prj=1 reçue")
            }.joinToString(" + ")
            "Lien SC2 ↔ drone CONFIRMÉ ($proofs)"
        }
        sc2AcceptedConn -> "Phone ↔ SC2 OK, mais lien SC2 ↔ drone NON confirmé. Lancer \"Start video\" : si 0 frame reste, le SC2 ne route pas vers le drone (LED orange clignotante = pas apparié)"
        else -> "État indéterminé"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("État du lien", style = MaterialTheme.typography.titleMedium)
            LinkRow("USB AOA", if (usbOk) "ouvert" else "fermé", usbOk, detail = "in=${bytesIn}B out=${bytesOut}B")
            LinkRow(
                "Handshake SC2",
                if (handshakeAcked) "OK — SC2 ouvre les canaux" else "en attente (SC2 muet)",
                handshakeAcked,
                detail = "ctrl msgs reçus = ${ctrlHistory.size}",
            )
            LinkRow(
                "Devices",
                if (devices.isEmpty()) "aucun" else "${devices.size}",
                devices.isNotEmpty(),
                detail = devices.joinToString(", ") { "${it.name} (type=${it.type})" }.ifEmpty { "lancer Discover" },
            )
            LinkRow(
                "CONN_RESP",
                when {
                    connResp == null -> "en attente"
                    connResp.status == 0 -> "ok (status=0)"
                    else -> "refusé (status=${connResp.status})"
                },
                connResp?.status == 0,
                detail = connResp?.json?.take(80) ?: "lancer Connect",
            )
            LinkRow(
                "Sticks SC2",
                if (piloting == null) "pas encore reçus" else "roll=${piloting.roll} pitch=${piloting.pitch} yaw=${piloting.yaw} gaz=${piloting.gaz}",
                piloting != null,
                detail = "BLACKBOX chan 20 — bouge les sticks pour confirmer que le SC2 est actif",
            )
            LinkRow(
                "Vidéo",
                "$videoFrames frame(s)",
                videoFrames > 0,
                detail = if (videoFrames == 0L) "lancer Start video après CONN_RESP" else "flux ARStream actif",
            )
            LinkRow(
                "Batterie drone",
                droneBattery?.let { "$it %" } ?: "non reçu",
                droneBattery != null,
                detail = "Common.CommonState.BatteryStateChanged (prj=0 cls=1 cmd=1)",
            )
            LinkRow(
                "Batterie SC2",
                sc2Battery?.let { "$it %" } ?: "non identifié",
                sc2Battery != null,
                detail = "skyctrl.SkyControllerState.BatteryChanged (prj=4 cls=8 cmd=0)",
            )
            LinkRow(
                "Firmware drone",
                droneFirmware ?: "non reçu",
                droneFirmware != null,
                detail = "common.SettingsState.ProductVersionChanged (prj=0 cls=5 cmd=0)",
            )
            val vEnableTxt = when (videoEnableState) {
                0 -> "enabled"; 1 -> "disabled"; 2 -> "ERROR"; null -> "pas de réponse"; else -> "enum=$videoEnableState"
            }
            LinkRow(
                "VideoEnable répondu ?",
                vEnableTxt,
                videoEnableState == 0,
                detail = "ardrone3.MediaStreamingState.VideoEnableChanged (prj=1 cls=22 cmd=0)",
            )
            val vModeTxt = when (videoStreamMode) {
                0 -> "low_latency"; 1 -> "high_reliability"; 2 -> "hr_low_framerate"
                null -> "pas de réponse"; else -> "enum=$videoStreamMode"
            }
            LinkRow(
                "VideoStreamMode",
                vModeTxt,
                videoStreamMode != null,
                detail = "ardrone3.MediaStreamingState.VideoStreamModeChanged (prj=1 cls=22 cmd=1)",
            )
            if (c2dSent.isNotEmpty()) {
                Text(
                    "C2D envoyées (${c2dSent.values.sum()} total)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                for ((label, count) in c2dSent.entries.sortedByDescending { it.value }) {
                    Text(
                        "$label ×$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            chan1Raw?.let {
                Text(
                    "Dernier payload MUX chan 1 (brut)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (transportStats.isNotEmpty()) {
                Text(
                    "Transport chan 1 (${transportStats.size} (dataType,bufferId) uniques)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val topT = transportStats.entries.sortedByDescending { it.value }.take(6)
                for ((dtBuf, count) in topT) {
                    val dtLabel = when (dtBuf.first) {
                        1 -> "ACK"
                        2 -> "NOACK"
                        3 -> "LOWLATENCY"
                        4 -> "WITHACK"
                        else -> "dt=${dtBuf.first}"
                    }
                    Text(
                        "$dtLabel  buf=${dtBuf.second}  ×$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (arCmdStats.isNotEmpty()) {
                Text(
                    "ARCommands reçues (${arCmdStats.size} tuples uniques)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val top = arCmdStats.entries.sortedByDescending { it.value }.take(8)
                for ((tuple, count) in top) {
                    Text(
                        "prj=${tuple.first} cls=${tuple.second} cmd=${tuple.third}  ×$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                arCmdLast?.let {
                    val hex = it.args.take(8).joinToString(" ") { b -> "%02x".format(b) }
                    Text(
                        "dernier args[${it.args.size}B] : $hex",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                "→ $verdict",
                style = MaterialTheme.typography.bodyMedium,
                color = verdictColor,
            )
        }
    }
}

@Composable
private fun LinkRow(label: String, value: String, ok: Boolean, detail: String = "") {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (ok) "✓" else "•", color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 4.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun PilotingCard(vm: DroneViewModel) {
    val input by vm.pilotingInput.collectAsStateWithLifecycle()
    val active by vm.pilotingActive.collectAsStateWithLifecycle()
    val aoaState by vm.aoaState.collectAsStateWithLifecycle()
    val isOpen = aoaState is io.dayd.bebop.aoa.AoaState.Open

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pilotage", style = MaterialTheme.typography.titleMedium)
            Text(
                "Boucle PCMD à 20 Hz quand le toggle est ON. Les boutons sont WITHACK.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::aoaTakeoff, enabled = isOpen) { Text("Takeoff") }
                Button(onClick = vm::aoaLanding, enabled = isOpen) { Text("Land") }
                OutlinedButton(onClick = vm::aoaFlatTrim, enabled = isOpen) { Text("Flat trim") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = vm::aoaEmergency,
                    enabled = isOpen,
                ) { Text("Emergency") }
                Spacer(Modifier.fillMaxWidth().padding(end = 8.dp).height(0.dp))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = active, onCheckedChange = { vm.togglePilotingLoop() }, enabled = isOpen)
                Text(if (active) "PCMD actif (20 Hz)" else "PCMD inactif")
            }

            PilotingSlider("Roll", input.roll) { vm.setPilotingInput(it, input.pitch, input.yaw, input.gaz) }
            PilotingSlider("Pitch", input.pitch) { vm.setPilotingInput(input.roll, it, input.yaw, input.gaz) }
            PilotingSlider("Yaw", input.yaw) { vm.setPilotingInput(input.roll, input.pitch, it, input.gaz) }
            PilotingSlider("Gaz", input.gaz) { vm.setPilotingInput(input.roll, input.pitch, input.yaw, it) }

            TextButton(onClick = { vm.setPilotingInput(0, 0, 0, 0) }) { Text("Centrer sticks") }
        }
    }
}

@Composable
private fun PilotingSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Text("$label : $value", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = -100f..100f,
            steps = 0,
        )
    }
}

@Composable
private fun VideoCard(vm: DroneViewModel) {
    val configured by vm.decoderConfigured.collectAsStateWithLifecycle()
    val queued by vm.decoderQueued.collectAsStateWithLifecycle()
    val size by vm.decoderSize.collectAsStateWithLifecycle()
    val err by vm.decoderError.collectAsStateWithLifecycle()
    val rtp by vm.rtpStats.collectAsStateWithLifecycle()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vidéo Bebop 2", style = MaterialTheme.typography.titleMedium)
            val cfg = if (configured) "cfg ok" else "en attente SPS/PPS"
            val dim = if (size.first > 0) "${size.first}x${size.second}" else "?"
            Text(
                "Décodeur — $cfg  •  $dim  •  queued=$queued" + (err?.let { "  •  err=$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "RTP — in=${rtp.first}  dropped=${rtp.second}  NALs=${rtp.third}",
                style = MaterialTheme.typography.bodySmall,
            )
            val v1n by vm.videoV1Frames.collectAsStateWithLifecycle()
            val v1sz by vm.videoV1LastSize.collectAsStateWithLifecycle()
            val v1head by vm.videoV1LastHead.collectAsStateWithLifecycle()
            Text(
                "ARStream v1 (buf 125) — frames=$v1n  lastSize=$v1sz",
                style = MaterialTheme.typography.bodySmall,
                color = if (v1n > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (v1head != null) {
                Text(
                    "  head: $v1head",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            val ch4head by vm.chan4RawHead.collectAsStateWithLifecycle()
            if (ch4head != null) {
                Text(
                    "Chan 4 raw: $ch4head",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            val asmHead by vm.assembledFrameHead.collectAsStateWithLifecycle()
            if (asmHead != null) {
                Text(
                    "Frame assemblée: $asmHead",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            val ctrlSent by vm.ctrlSent.collectAsStateWithLifecycle()
            val openCount = ctrlSent[io.dayd.bebop.mux.MuxCtrlMessage.ID_CHANNEL_OPEN] ?: 0L
            Text(
                "Ctrl sent : CHANNEL_OPEN×$openCount  (≥2 attendu après Open RTP proxy)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            val slaves by vm.slaveChanids.collectAsStateWithLifecycle()
            if (slaves.isNotEmpty()) {
                slaves.forEach { (chanid, label) ->
                    Text(
                        "  slave chan=$chanid → $label",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Text(
                "Vidéo sur l'écran Pilotage (← swipe)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ConnectedCard(config: io.dayd.bebop.network.DroneConfig) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Drone connecté", style = MaterialTheme.typography.titleMedium)
            Text("host = ${config.deviceHost}")
            Text("c2d_port = ${config.c2dPort}")
            Text("d2c_port = ${config.d2cPort}")
            Text("fragment_size = ${config.arstreamFragmentSize}")
            Text("fragment_max = ${config.arstreamFragmentMaxNumber}")
            Spacer(Modifier.height(4.dp))
            Text("Réponse brute :", style = MaterialTheme.typography.labelSmall)
            Text(config.rawResponse, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatArg(a: io.dayd.bebop.mux.PompArg): String = when (a) {
    is io.dayd.bebop.mux.PompArg.I8 -> "i8:${a.value}"
    is io.dayd.bebop.mux.PompArg.U8 -> "u8:${a.value}"
    is io.dayd.bebop.mux.PompArg.I16 -> "i16:${a.value}"
    is io.dayd.bebop.mux.PompArg.U16 -> "u16:${a.value}"
    is io.dayd.bebop.mux.PompArg.I32 -> "i32:${a.value}"
    is io.dayd.bebop.mux.PompArg.U32 -> "u32:${a.value}"
    is io.dayd.bebop.mux.PompArg.I64 -> "i64:${a.value}"
    is io.dayd.bebop.mux.PompArg.U64 -> "u64:${a.value}"
    is io.dayd.bebop.mux.PompArg.Str -> "\"${a.value}\""
    is io.dayd.bebop.mux.PompArg.Buf -> "buf[${a.value.size}]"
    is io.dayd.bebop.mux.PompArg.F32 -> "f32:${a.value}"
    is io.dayd.bebop.mux.PompArg.F64 -> "f64:${a.value}"
    is io.dayd.bebop.mux.PompArg.Fd -> "fd:${a.value}"
}

private fun channelName(chanid: Int): String = when (chanid) {
    0 -> "control"
    1 -> "arsdk-transport"
    2 -> "arsdk-discovery"
    3 -> "arsdk-backend"
    4 -> "arsdk-stream-data"
    5 -> "arsdk-stream-control"
    6 -> "arsdk-rtp"
    10 -> "update"
    20 -> "blackbox"
    else -> "?"
}

@Composable
private fun AoaCard(
    state: io.dayd.bebop.aoa.AoaState,
    bytesIn: Long,
    bytesOut: Long,
    log: List<String>,
    dump: io.dayd.bebop.aoa.DumpState?,
    frameStats: Map<Int, Long>,
    resyncs: Int,
    piloting: io.dayd.bebop.aoa.PilotingInfo?,
    pompFailures: Int,
    ctrlHistory: List<io.dayd.bebop.mux.MuxCtrlMessage>,
    lastPomp: Map<Int, io.dayd.bebop.mux.PompMessage>,
    devices: List<io.dayd.bebop.aoa.ArsdkDevice>,
    connResp: io.dayd.bebop.aoa.ConnResp?,
    videoFrames: Long,
    videoLastSize: Int,
    videoLastFlags: Int,
    onToggle: () -> Unit,
    onSendProbe: () -> Unit,
    onToggleDump: () -> Unit,
    onSendHandshake: () -> Unit,
    onSendDiscover: () -> Unit,
    onConnectDevice: (String) -> Unit,
    onStartVideo: () -> Unit,
    onStopVideo: () -> Unit,
    onOpenVideoProxy: () -> Unit,
    onRequestSc2States: () -> Unit,
    onRequestDroneStates: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Canal AOA", style = MaterialTheme.typography.titleMedium)

            val (label, color) = when (val s = state) {
                io.dayd.bebop.aoa.AoaState.Disconnected -> "déconnecté" to MaterialTheme.colorScheme.onSurfaceVariant
                io.dayd.bebop.aoa.AoaState.AwaitingPermission -> "demande de permission USB…" to Color(0xFFFF9800)
                is io.dayd.bebop.aoa.AoaState.Open -> "ouvert  ${s.accessory.manufacturer}/${s.accessory.model}" to Color(0xFF1B5E20)
                is io.dayd.bebop.aoa.AoaState.Error -> "erreur : ${s.message}" to MaterialTheme.colorScheme.error
            }
            Text(label, color = color)

            Text("in: $bytesIn B   out: $bytesOut B", style = MaterialTheme.typography.bodySmall)

            val isOpen = state is io.dayd.bebop.aoa.AoaState.Open
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggle) { Text(if (isOpen) "Fermer AOA" else "Ouvrir AOA") }
                OutlinedButton(onClick = onSendProbe, enabled = isOpen) { Text("Probe") }
                OutlinedButton(
                    onClick = onToggleDump,
                    enabled = isOpen || dump != null,
                ) { Text(if (dump != null) "Stop dump" else "Dump") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSendHandshake, enabled = isOpen) { Text("Handshake") }
                OutlinedButton(onClick = onSendDiscover, enabled = isOpen) { Text("Discover") }
                OutlinedButton(onClick = onStartVideo, enabled = isOpen) { Text("Start video") }
                OutlinedButton(onClick = onStopVideo, enabled = isOpen) { Text("Stop video") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenVideoProxy, enabled = isOpen) {
                    Text("Open RTP proxy")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onRequestSc2States, enabled = isOpen) { Text("AllStates SC2") }
                OutlinedButton(onClick = onRequestDroneStates, enabled = isOpen) { Text("AllStates drone") }
            }

            if (ctrlHistory.isNotEmpty()) {
                Text("Ctrl chan0 reçus (${ctrlHistory.size}) :", style = MaterialTheme.typography.labelSmall)
                ctrlHistory.takeLast(10).forEach {
                    val args = it.args.joinToString(", ")
                    Text(
                        "  ${it.name()}  chanid=${it.chanid}  args=[$args]",
                        color = Color(0xFF1B5E20),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (devices.isNotEmpty()) {
                Text("Devices ARSDK détectés :", style = MaterialTheme.typography.labelSmall)
                devices.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "  ${d.name} (type=0x%04x)".format(d.type),
                                color = Color(0xFF1B5E20),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("  id=${d.id}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onConnectDevice(d.id) }) { Text("Connect") }
                    }
                }
            }

            if (videoFrames > 0) {
                val flush = (videoLastFlags and 1) != 0
                Text(
                    "Vidéo — frames=$videoFrames  dernière=${videoLastSize}B${if (flush) " [FLUSH]" else ""}",
                    color = Color(0xFF1B5E20),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            connResp?.let {
                val color = if (it.status == 0) Color(0xFF1B5E20) else MaterialTheme.colorScheme.error
                Text(
                    "ConnResp — status=${it.status}  json=${it.json}",
                    color = color,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (lastPomp.isNotEmpty()) {
                Text("Dernier POMP par canal :", style = MaterialTheme.typography.labelSmall)
                lastPomp.toSortedMap().forEach { (chanid, msg) ->
                    val args = msg.args.joinToString(", ") { formatArg(it) }
                    Text(
                        "  chan $chanid msg=${msg.msgid} args=[$args]",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            dump?.let {
                Text(
                    "Dump ${it.bytes} B → ${it.path}",
                    color = Color(0xFF1B5E20),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (frameStats.isNotEmpty()) {
                Text("Frames MUX par canal :", style = MaterialTheme.typography.labelSmall)
                frameStats.toSortedMap().forEach { (chanid, count) ->
                    Text(
                        "  chan $chanid (${channelName(chanid)}) : $count",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (resyncs > 0) {
                    Text("  resyncs: $resyncs", style = MaterialTheme.typography.bodySmall)
                }
                if (pompFailures > 0) {
                    Text("  POMP decode failures: $pompFailures",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            piloting?.let {
                Text(
                    "Sticks SC2 — src=${it.source}  roll=${it.roll}  pitch=${it.pitch}  yaw=${it.yaw}  gaz=${it.gaz}",
                    color = Color(0xFF1B5E20),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (log.isNotEmpty()) {
                Text("Bytes reçus :", style = MaterialTheme.typography.labelSmall)
                log.takeLast(8).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun UsbCard(report: io.dayd.bebop.network.UsbReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("USB", style = MaterialTheme.typography.titleMedium)

            Text("Devices (mode host) — ${report.devices.size}", style = MaterialTheme.typography.labelLarge)
            if (report.devices.isEmpty()) {
                Text("(aucun — normal si téléphone en mode device)", style = MaterialTheme.typography.bodySmall)
            }
            report.devices.forEach { d ->
                val parrot = io.dayd.bebop.network.UsbInspector.looksLikeParrot(d)
                Column {
                    Text(
                        "VID=0x%04x PID=0x%04x  class=%d/%d".format(d.vendorId, d.productId, d.deviceClass, d.deviceSubclass),
                        color = if (parrot) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    d.manufacturerName?.let { Text("  mfg: $it", style = MaterialTheme.typography.bodySmall) }
                    d.productName?.let { Text("  product: $it", style = MaterialTheme.typography.bodySmall) }
                    d.serialNumber?.let { Text("  serial: $it", style = MaterialTheme.typography.bodySmall) }
                    Text("  interfaces: ${d.interfaceCount}", style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider()

            Text("Accessories (mode AOA) — ${report.accessories.size}", style = MaterialTheme.typography.labelLarge)
            if (report.accessories.isEmpty()) {
                Text("(aucun — le SC2 n'a pas négocié AOA)", style = MaterialTheme.typography.bodySmall)
            }
            report.accessories.forEach { a ->
                val parrot = io.dayd.bebop.network.UsbInspector.looksLikeParrot(a)
                Column {
                    Text(
                        "${a.manufacturer} / ${a.model}",
                        color = if (parrot) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    a.version?.let { Text("  version: $it", style = MaterialTheme.typography.bodySmall) }
                    a.description?.let { Text("  desc: $it", style = MaterialTheme.typography.bodySmall) }
                    a.serial?.let { Text("  serial: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticCard(
    interfaces: List<io.dayd.bebop.network.InterfaceInfo>,
    probes: List<HostProbe>,
    scanning: Boolean,
    onPickHost: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Diagnostic réseau", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(modifier = Modifier.height(18.dp))
            }

            Text("Interfaces", style = MaterialTheme.typography.labelLarge)
            if (interfaces.isEmpty()) Text("(aucune)")
            interfaces.forEach { i ->
                Column {
                    Text("${i.name}  ${if (i.isUp) "UP" else "down"}${if (i.isLoopback) "  (loopback)" else ""}",
                        style = MaterialTheme.typography.bodyMedium)
                    if (i.ipv4.isNotEmpty()) {
                        i.ipv4.forEachIndexed { idx, ip ->
                            val prefix = i.ipv4Prefix.getOrNull(idx)
                            Text("  IPv4: $ip${prefix?.let { "/$it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    i.gateway?.let { Text("  gateway: $it", style = MaterialTheme.typography.bodySmall) }
                    i.mac?.let { Text("  mac: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }

            HorizontalDivider()

            Text("IPs candidates (TCP :44444)", style = MaterialTheme.typography.labelLarge)
            if (probes.isEmpty()) Text("(aucune)")
            probes.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val label = when (p.reachable) {
                        null -> "…"
                        true -> "OK"
                        false -> "KO"
                    }
                    val color = when (p.reachable) {
                        true -> Color(0xFF1B5E20)
                        false -> Color(0xFFB71C1C)
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(label, color = color, modifier = Modifier.padding(end = 8.dp))
                    Text(p.host, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onPickHost(p.host) }) { Text("Utiliser") }
                }
            }
        }
    }
}
