package io.dayd.bebop.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
fun PilotScreen(vm: DroneViewModel) {
    val droneBatt by vm.anyDroneBattery.collectAsStateWithLifecycle()
    val sc2Batt by vm.sc2BatteryPercent.collectAsStateWithLifecycle()
    val directConnected by vm.directConnected.collectAsStateWithLifecycle()
    val flyingState by vm.directFlyingState.collectAsStateWithLifecycle()
    val pilotingActive by vm.pilotingActive.collectAsStateWithLifecycle()
    val configured by vm.decoderConfigured.collectAsStateWithLifecycle()
    val aoaState by vm.aoaState.collectAsStateWithLifecycle()
    val connResp by vm.aoaConnResp.collectAsStateWithLifecycle()
    val videoFrames by vm.anyVideoFrames.collectAsStateWithLifecycle()

    val autoStatus by vm.autoStatus.collectAsStateWithLifecycle()
    val recordState by vm.videoRecordState.collectAsStateWithLifecycle()
    val recording = recordState == 1
    // Deux voies possibles vers le drone : SC2/AOA ou Wi-Fi direct. L'une suffit.
    val connected = (aoaState is io.dayd.bebop.aoa.AoaState.Open && connResp != null) || directConnected

    var leftX by remember { mutableFloatStateOf(0f) }
    var leftY by remember { mutableFloatStateOf(0f) }
    var rightX by remember { mutableFloatStateOf(0f) }
    var rightY by remember { mutableFloatStateOf(0f) }

    fun updatePiloting() {
        vm.setPilotingInput(
            roll = (rightX * 100).roundToInt(),
            pitch = (rightY * 100).roundToInt(),
            yaw = (leftX * 100).roundToInt(),
            gaz = (leftY * 100).roundToInt(),
        )
    }

    DisposableEffect(pilotingActive) {
        onDispose {}
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Video surface
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            vm.setVideoSurface(holder.surface)
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            vm.setVideoSurface(null)
                        }
                    })
                }
            },
        )

        // HUD top
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val statusText = when {
                !connected -> "Déconnecté"
                videoFrames > 0 && configured -> "Live"
                videoFrames > 0 -> "Décodage…"
                else -> "Connecté"
            }
            val statusColor = when {
                !connected -> Color(0xFFFF5252)
                videoFrames > 0 && configured -> Color(0xFF69F0AE)
                else -> Color(0xFFFFD740)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(statusColor, CircleShape))
                HudText(statusText)
                // En vol, l'état du drone prime sur tout le reste du HUD.
                flyingState?.let { st ->
                    val label = when (st) {
                        0 -> "posé"; 1 -> "décollage"; 2 -> "stationnaire"; 3 -> "en vol"
                        4 -> "atterrissage"; 5 -> "URGENCE"; 6 -> "décollage"
                        7 -> "moteurs"; 8 -> "atterr. urgence"
                        else -> "état $st"
                    }
                    val c = when (st) {
                        0 -> Color(0xFFB0BEC5)
                        2, 3 -> Color(0xFF69F0AE)
                        5, 8 -> Color(0xFFFF1744)
                        else -> Color(0xFFFFD740)
                    }
                    HudText(label, c)
                }
                if (recording) {
                    HudText("● REC", Color(0xFFFF1744))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                droneBatt?.let { HudText("Drone $it%") }
                sc2Batt?.let { HudText("SC2 $it%") }
                HudText("Swipe →")
            }
        }

        // Connection status overlay
        if (!connected) {
            val isError = aoaState is io.dayd.bebop.aoa.AoaState.Error
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!isError) {
                    CircularProgressIndicator(color = Color.White)
                }
                Text(
                    autoStatus,
                    color = if (isError) Color(0xFFFF5252) else Color.White,
                    fontSize = 16.sp,
                )
            }
        }

        // Joystick areas — bottom 55%
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .align(Alignment.BottomCenter),
        ) {
            FloatingJoystick(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onInput = { x, y -> leftX = x; leftY = y; updatePiloting() },
            )
            FloatingJoystick(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onInput = { x, y -> rightX = x; rightY = y; updatePiloting() },
            )
        }

        // Action buttons — center bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!pilotingActive && connected) {
                PilotButton("ARMER", Color(0xFF2196F3)) {
                    vm.startPilotingLoop()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (pilotingActive) {
                    PilotButton("DÉCOLLER", Color(0xFF4CAF50)) { vm.aoaTakeoff() }
                    PilotButton("ATTERRIR", Color(0xFFFFC107)) { vm.aoaLanding() }
                    PilotButton("STOP", Color(0xFFFF1744)) { vm.stopPilotingLoop() }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (connected) {
                    PilotButton(
                        if (recording) "STOP REC" else "REC",
                        if (recording) Color(0xFFFF5252) else Color(0xFF757575),
                    ) { vm.aoaToggleRecord() }
                    PilotButton("URGENCE", Color(0xFFD50000)) { vm.aoaEmergency() }
                }
            }
        }
    }
}

@Composable
private fun HudText(text: String, color: Color = Color.White) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun PilotButton(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.85f)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}
