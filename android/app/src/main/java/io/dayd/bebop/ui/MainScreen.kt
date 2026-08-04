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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.Slider

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
    val aoaState by vm.aoaState.collectAsStateWithLifecycle()
    val autoStatus by vm.autoStatus.collectAsStateWithLifecycle()
    val isOpen = aoaState is io.dayd.bebop.aoa.AoaState.Open
    val dmState by vm.dmConnectionState.collectAsStateWithLifecycle()
    val connResp by vm.aoaConnResp.collectAsStateWithLifecycle()
    val videoFrames by vm.aoaVideoFrames.collectAsStateWithLifecycle()
    val droneBattery by vm.droneBatteryPercent.collectAsStateWithLifecycle()
    val sc2Battery by vm.sc2BatteryPercent.collectAsStateWithLifecycle()
    val arCmdStats by vm.arCmdStats.collectAsStateWithLifecycle()

    val droneLinked = videoFrames > 0 || droneBattery != null ||
        arCmdStats.keys.any { it.first == io.dayd.bebop.arsdk.ArsdkIds.PRJ_ARDRONE3 }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // --- Header : titre + batteries ---
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bebop 2", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                BatteryPill("Drone", droneBattery)
                BatteryPill("SC2", sc2Battery)
            }

            // --- Statut global + bouton principal ---
            StatusCard(
                aoaState = aoaState,
                autoStatus = autoStatus,
                dmState = dmState,
                connRespOk = connResp?.status == 0,
                droneLinked = droneLinked,
                videoFrames = videoFrames,
                onAutoConnect = vm::aoaAutoConnect,
                onOpenAoa = vm::aoaConnect,
                isOpen = isOpen,
            )

            // --- Connexion directe Wi-Fi au drone ---
            DirectCard(vm)

            // --- Connexion SC2 ↔ Drone (drone_manager + Wi-Fi) ---
            Sc2DroneCard(vm)

            // --- Vidéo ---
            VideoCard(vm)

            // --- Pilotage ---
            PilotingCard(vm)

            // --- Avancé (dépliable) ---
            AdvancedCard(vm)
        }
    }
}

@Composable
private fun StatusCard(
    aoaState: io.dayd.bebop.aoa.AoaState,
    autoStatus: String,
    dmState: io.dayd.bebop.aoa.AoaController.DmConnectionState?,
    connRespOk: Boolean,
    droneLinked: Boolean,
    videoFrames: Long,
    onAutoConnect: () -> Unit,
    onOpenAoa: () -> Unit,
    isOpen: Boolean,
) {
    val aoaLabel = when (aoaState) {
        io.dayd.bebop.aoa.AoaState.Disconnected -> "USB non connecté"
        io.dayd.bebop.aoa.AoaState.AwaitingPermission -> "Permission USB…"
        is io.dayd.bebop.aoa.AoaState.Open -> "USB OK"
        is io.dayd.bebop.aoa.AoaState.Error -> "Erreur USB : ${aoaState.message}"
    }
    val dmLabel = when (dmState?.state) {
        null -> "—"
        0 -> "idle"
        1 -> "recherche drone…"
        2 -> "connexion…"
        3 -> "connecté"
        4 -> "déconnexion…"
        else -> "état=${dmState.state}"
    }

    val dmConnected = dmState?.state == 3
    val globalOk = isOpen && connRespOk && droneLinked
    val globalColor = when {
        globalOk -> Color(0xFF1B5E20)
        isOpen -> Color(0xFFEF6C00)
        else -> MaterialTheme.colorScheme.error
    }
    val globalText = when {
        !isOpen -> "Brancher le SC2 au Pixel"
        !connRespOk -> "SC2 détecté — connexion MUX en cours…"
        droneLinked -> "Tout OK — vidéo $videoFrames frames"
        !dmConnected -> "Phone ↔ SC2 OK — drone non trouvé en Wi-Fi"
        else -> "Phone ↔ SC2 ↔ Drone OK — en attente de données"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(globalText, style = MaterialTheme.typography.titleMedium, color = globalColor)
            Text("USB : $aoaLabel", style = MaterialTheme.typography.bodySmall)
            Text("Drone Wi-Fi : $dmLabel${dmState?.name?.let { " ($it)" } ?: ""}", style = MaterialTheme.typography.bodySmall)
            Text("Statut : $autoStatus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isOpen) {
                    Button(onClick = onOpenAoa) { Text("Ouvrir USB") }
                } else {
                    Button(onClick = onAutoConnect) { Text("Auto-connect") }
                }
            }
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
private fun DirectCard(vm: DroneViewModel) {
    val connected by vm.directConnected.collectAsStateWithLifecycle()
    val status by vm.directDroneStatus.collectAsStateWithLifecycle()
    val battery by vm.directDroneBattery.collectAsStateWithLifecycle()
    val firmware by vm.directDroneFirmware.collectAsStateWithLifecycle()
    val packetsIn by vm.directPacketsIn.collectAsStateWithLifecycle()
    val lastPacketAt by vm.directLastPacketAt.collectAsStateWithLifecycle()
    val lastTuple by vm.directLastTuple.collectAsStateWithLifecycle()

    // Tick 1 Hz pour recalculer l'âge du dernier paquet reçu.
    var now by remember { mutableStateOf(android.os.SystemClock.uptimeMillis()) }
    LaunchedEffect(connected) {
        while (connected) {
            now = android.os.SystemClock.uptimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Connexion directe Wi-Fi", style = MaterialTheme.typography.titleMedium)
            Text(
                "Connecter le Pixel au Wi-Fi du drone (Bebop2-xxx) puis toucher Connecter",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(status, style = MaterialTheme.typography.bodySmall,
                color = if (connected) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSurface)

            if (battery != null) {
                Text("Batterie drone : $battery%", style = MaterialTheme.typography.titleSmall,
                    color = when {
                        battery!! >= 60 -> Color(0xFF1B5E20)
                        battery!! >= 25 -> Color(0xFFEF6C00)
                        else -> Color(0xFFB71C1C)
                    })
            }
            if (firmware != null) {
                Text("Firmware : $firmware", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }

            if (connected) {
                // Le lien peut être vivant sans aucune ARCommand (PONG/ACK seuls) :
                // sans ce compteur, silence radio et lien mort sont indiscernables.
                val ageS = if (lastPacketAt > 0) (now - lastPacketAt) / 1000 else -1
                val alive = ageS in 0..2
                Text(
                    "Paquets reçus : $packetsIn" + if (ageS >= 0) " — dernier il y a ${ageS}s" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (alive) Color(0xFF1B5E20) else Color(0xFFB71C1C),
                )
                Text("Dernier tuple : ${lastTuple ?: "—"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

                val vFrames by vm.directVideoFrames.collectAsStateWithLifecycle()
                val vRtp by vm.directRtpStats.collectAsStateWithLifecycle()
                val vPath by vm.directVideoPath.collectAsStateWithLifecycle()
                Text(
                    "Vidéo : $vFrames frames" + (vPath?.let { " via $it" } ?: " — aucune") +
                        "  (RTP in=${vRtp.first} drop=${vRtp.second} NAL=${vRtp.third})",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vFrames > 0) Color(0xFF1B5E20) else MaterialTheme.colorScheme.outline,
                )
            }

            if (connected) {
                val rot by vm.directMaxRotationSpeed.collectAsStateWithLifecycle()
                val vert by vm.directMaxVerticalSpeed.collectAsStateWithLifecycle()
                val tilt by vm.directMaxTilt.collectAsStateWithLifecycle()
                val anyThrottled = listOfNotNull(rot, vert, tilt).any { it.throttled }

                HorizontalDivider()
                Text("Performances", style = MaterialTheme.typography.titleSmall)
                SettingLine("Rotation (yaw)", rot, "°/s")
                SettingLine("Vitesse verticale", vert, "m/s")
                SettingLine("Inclinaison max", tilt, "°")
                if (anyThrottled) {
                    Text(
                        "Le drone est en dessous de ce qu'il sait faire",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFEF6C00),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::directPerfModerate) { Text("Modéré") }
                    OutlinedButton(onClick = vm::directPerfMax) { Text("Max") }
                }
                Text(
                    "« Max » = 200 °/s et 35° d'inclinaison : très nerveux, à éviter tant que le drone n'est pas en main",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!connected) {
                    Button(onClick = vm::directConnect) { Text("Connecter") }
                } else {
                    OutlinedButton(onClick = vm::directDisconnect) { Text("Déconnecter") }
                }
            }
            if (connected) {
                OutlinedButton(onClick = vm::directAllStates) { Text("Redemander états") }
            }
        }
    }
}

/** Affiche « courant / max » et signale en orange un réglage bridé. */
@Composable
private fun SettingLine(
    label: String,
    setting: io.dayd.bebop.network.DirectController.Setting?,
    unit: String,
) {
    if (setting == null) {
        Text("$label : —", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline)
        return
    }
    Text(
        "$label : ${"%.1f".format(setting.current)} / ${"%.1f".format(setting.max)} $unit",
        style = MaterialTheme.typography.bodySmall,
        color = if (setting.throttled) Color(0xFFEF6C00) else Color(0xFF1B5E20),
    )
}

@Composable
private fun Sc2DroneCard(vm: DroneViewModel) {
    val dmState by vm.dmConnectionState.collectAsStateWithLifecycle()
    val dmDrones by vm.dmDroneList.collectAsStateWithLifecycle()
    val wifiList by vm.sc2WifiList.collectAsStateWithLifecycle()
    val wifiConnected by vm.sc2WifiConnected.collectAsStateWithLifecycle()
    val aoaState by vm.aoaState.collectAsStateWithLifecycle()
    val isOpen = aoaState is io.dayd.bebop.aoa.AoaState.Open
    val dmOk = dmState?.state == 3

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("SC2 → Drone", style = MaterialTheme.typography.titleMedium)

            if (dmState != null) {
                val stateLabel = when (dmState!!.state) {
                    0 -> "idle"; 1 -> "recherche…"; 2 -> "connexion…"; 3 -> "connecté"; 4 -> "déconnexion…"
                    else -> "état=${dmState!!.state}"
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (dmOk) "✓" else "•", color = if (dmOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text("$stateLabel — ${dmState!!.name}", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "serial=${dmState!!.serial}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (wifiConnected != null) {
                Text("Wi-Fi : $wifiConnected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::aoaDmDiscoverDrones, enabled = isOpen && !dmOk) { Text("Chercher drones") }
                OutlinedButton(onClick = vm::aoaSc2WifiScan, enabled = isOpen) { Text("Scan Wi-Fi") }
            }

            if (dmDrones.isNotEmpty()) {
                Text("${dmDrones.size} drone(s) trouvé(s) :", style = MaterialTheme.typography.labelMedium)
                for (drone in dmDrones) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${drone.name}${if (drone.active) " [actif]" else ""}${if (drone.hasSavedKey) " ★" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "rssi=${drone.rssi}  visible=${drone.visible}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Button(
                            onClick = { vm.aoaDmConnect(drone.serial) },
                            enabled = isOpen,
                        ) { Text("Connecter") }
                    }
                }
            }

            if (wifiList.isNotEmpty()) {
                HorizontalDivider()
                Text("${wifiList.size} réseau(x) Wi-Fi :", style = MaterialTheme.typography.labelMedium)
                for (entry in wifiList.sortedByDescending { it.rssi }) {
                    val isBebop = entry.ssid.contains("Bebop", ignoreCase = true) ||
                        entry.ssid.contains("Disco", ignoreCase = true) ||
                        entry.ssid.contains("Mambo", ignoreCase = true)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${entry.ssid}  ${entry.rssi} dBm${if (entry.saved) " ★" else ""}${if (entry.secured) " 🔒" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isBebop) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            )
                        }
                        if (isBebop) {
                            Button(
                                onClick = { vm.aoaSc2WifiConnect(entry.bssid, entry.ssid) },
                                enabled = isOpen,
                            ) { Text("Connecter") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoCard(vm: DroneViewModel) {
    val configured by vm.decoderConfigured.collectAsStateWithLifecycle()
    val queued by vm.decoderQueued.collectAsStateWithLifecycle()
    val size by vm.decoderSize.collectAsStateWithLifecycle()
    val err by vm.decoderError.collectAsStateWithLifecycle()
    val rtp by vm.rtpStats.collectAsStateWithLifecycle()
    val aoaState by vm.aoaState.collectAsStateWithLifecycle()
    val isOpen = aoaState is io.dayd.bebop.aoa.AoaState.Open

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Vidéo", style = MaterialTheme.typography.titleMedium)
            val cfg = if (configured) "OK" else "en attente SPS/PPS"
            val dim = if (size.first > 0) "${size.first}x${size.second}" else "?"
            Text("Décodeur : $cfg  $dim  queued=$queued", style = MaterialTheme.typography.bodySmall)
            Text("RTP : in=${rtp.first}  dropped=${rtp.second}  NALs=${rtp.third}", style = MaterialTheme.typography.bodySmall)
            if (err != null) {
                Text("Erreur : $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::aoaStartVideo, enabled = isOpen) { Text("Start video") }
                OutlinedButton(onClick = vm::aoaStopVideo, enabled = isOpen) { Text("Stop video") }
            }

            Text(
                "← Swipe pour l'écran pilotage avec vidéo",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun PilotingCard(vm: DroneViewModel) {
    val input by vm.pilotingInput.collectAsStateWithLifecycle()
    val active by vm.pilotingActive.collectAsStateWithLifecycle()
    val aoaState by vm.aoaState.collectAsStateWithLifecycle()
    val isOpen = aoaState is io.dayd.bebop.aoa.AoaState.Open
    val connResp by vm.aoaConnResp.collectAsStateWithLifecycle()
    val directConnected by vm.directConnected.collectAsStateWithLifecycle()
    // Le drone est pilotable par l'une ou l'autre voie : SC2/AOA ou Wi-Fi direct.
    val canPilot = (isOpen && connResp?.status == 0) || directConnected

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pilotage", style = MaterialTheme.typography.titleMedium)
            if (!canPilot) {
                Text("Connecter le drone d'abord", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::aoaTakeoff, enabled = canPilot) { Text("Takeoff") }
                Button(onClick = vm::aoaLanding, enabled = canPilot) { Text("Land") }
                OutlinedButton(onClick = vm::aoaFlatTrim, enabled = canPilot) { Text("Flat trim") }
            }
            Button(
                onClick = vm::aoaEmergency,
                enabled = canPilot,
            ) { Text("EMERGENCY") }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = active, onCheckedChange = { vm.togglePilotingLoop() }, enabled = canPilot)
                // 25 Hz en Wi-Fi direct (boucle permanente), 20 Hz via le SC2.
                Text(if (active) "PCMD actif (${if (directConnected) 25 else 20} Hz)" else "PCMD inactif")
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
private fun AdvancedCard(vm: DroneViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val aoaState by vm.aoaState.collectAsStateWithLifecycle()
    val isOpen = aoaState is io.dayd.bebop.aoa.AoaState.Open

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Avancé", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Replier" else "Déplier")
                }
            }

            if (expanded) {
                val bytesIn by vm.aoaBytesIn.collectAsStateWithLifecycle()
                val bytesOut by vm.aoaBytesOut.collectAsStateWithLifecycle()
                val ctrlHistory by vm.aoaCtrlHistory.collectAsStateWithLifecycle()
                val devices by vm.aoaDevices.collectAsStateWithLifecycle()
                val connResp by vm.aoaConnResp.collectAsStateWithLifecycle()
                val droneFirmware by vm.droneFirmware.collectAsStateWithLifecycle()
                val videoEnableState by vm.videoEnableState.collectAsStateWithLifecycle()
                val videoStreamMode by vm.videoStreamMode.collectAsStateWithLifecycle()
                val arCmdStats by vm.arCmdStats.collectAsStateWithLifecycle()
                val frameStats by vm.aoaFrameStats.collectAsStateWithLifecycle()
                val dump by vm.aoaDump.collectAsStateWithLifecycle()

                Text("USB : in=${bytesIn}B  out=${bytesOut}B", style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::aoaToggle) { Text(if (isOpen) "Fermer AOA" else "Ouvrir AOA") }
                    OutlinedButton(onClick = vm::aoaToggleDump, enabled = isOpen || dump != null) {
                        Text(if (dump != null) "Stop dump" else "Dump")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::aoaSendHandshake, enabled = isOpen) { Text("Handshake") }
                    OutlinedButton(onClick = vm::aoaSendDiscover, enabled = isOpen) { Text("Discover") }
                    OutlinedButton(onClick = vm::aoaOpenVideoProxy, enabled = isOpen) { Text("Open RTP") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::aoaRequestSc2States, enabled = isOpen) { Text("AllStates SC2") }
                    OutlinedButton(onClick = vm::aoaRequestDroneStates, enabled = isOpen) { Text("AllStates drone") }
                }

                dump?.let {
                    Text("Dump ${it.bytes} B → ${it.path}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1B5E20))
                }

                HorizontalDivider()
                Text("État du lien", style = MaterialTheme.typography.labelMedium)
                LinkRow("USB AOA", if (isOpen) "ouvert" else "fermé", isOpen)
                LinkRow("Handshake", if (ctrlHistory.isNotEmpty()) "OK (${ctrlHistory.size} ctrl msgs)" else "en attente", ctrlHistory.isNotEmpty())
                LinkRow("Devices", if (devices.isEmpty()) "aucun" else devices.joinToString { it.name }, devices.isNotEmpty())
                LinkRow("CONN_RESP", when { connResp == null -> "en attente"; connResp!!.status == 0 -> "OK"; else -> "refusé" }, connResp?.status == 0)
                LinkRow("Firmware drone", droneFirmware ?: "non reçu", droneFirmware != null)
                val vEnable = when (videoEnableState) { 0 -> "enabled"; 1 -> "disabled"; 2 -> "ERROR"; null -> "—"; else -> "$videoEnableState" }
                LinkRow("VideoEnable", vEnable, videoEnableState == 0)
                val vMode = when (videoStreamMode) { 0 -> "low_latency"; 1 -> "high_reliability"; null -> "—"; else -> "$videoStreamMode" }
                LinkRow("VideoStreamMode", vMode, videoStreamMode != null)

                if (arCmdStats.isNotEmpty()) {
                    HorizontalDivider()
                    Text("ARCommands reçues (${arCmdStats.size} tuples)", style = MaterialTheme.typography.labelMedium)
                    val top = arCmdStats.entries.sortedByDescending { it.value }.take(10)
                    for ((tuple, count) in top) {
                        Text(
                            "(${tuple.first},${tuple.second},${tuple.third}) x$count",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                if (frameStats.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Frames MUX par canal", style = MaterialTheme.typography.labelMedium)
                    frameStats.toSortedMap().forEach { (chanid, count) ->
                        Text(
                            "chan $chanid (${channelName(chanid)}) : $count",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, value: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (ok) "✓" else "•", color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Text("$label : $value", style = MaterialTheme.typography.bodySmall)
    }
}

private fun channelName(chanid: Int): String = when (chanid) {
    0 -> "control"; 1 -> "transport"; 2 -> "discovery"; 3 -> "backend"
    4 -> "stream-data"; 5 -> "stream-ctrl"; 6 -> "rtp"; 10 -> "update"; 20 -> "blackbox"
    else -> "?"
}
