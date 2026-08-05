package io.dayd.bebop.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Premier écran : le pilote annonce comment il va voler. Rien n'est tenté
 * avant sa réponse — c'est ce choix qui décide de la voie, et une seule voie
 * est essayée ensuite, pour que l'échec désigne toujours le bon coupable.
 */
@Composable
fun ModeScreen(vm: DroneViewModel) {
    val sc2Plugged by vm.sc2Plugged.collectAsStateWithLifecycle()
    val wifiEnabled by vm.wifiEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .systemBarsPadding()
            .padding(24.dp),
    ) {
        Column(
            Modifier.align(Alignment.TopStart),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Comment veux-tu piloter ?",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Allume le drone en premier, la manette ensuite.",
                color = Color(0xFF90A4AE),
                fontSize = 14.sp,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .fillMaxHeight(0.62f),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ModeCard(
                modifier = Modifier.weight(1f),
                title = "Avec la manette",
                subtitle = "Skycontroller 2",
                detail = if (sc2Plugged) {
                    "Manette branchée en USB"
                } else {
                    "Brancher la manette au téléphone en USB"
                },
                detailColor = if (sc2Plugged) OK_GREEN else WARN_AMBER,
                ready = sc2Plugged,
                hint = "Portée maximale, sticks physiques",
                onClick = { vm.chooseMode(FlightMode.Sc2) },
            )
            ModeCard(
                modifier = Modifier.weight(1f),
                title = "Téléphone seul",
                subtitle = "Wi-Fi direct",
                detail = if (wifiEnabled) {
                    "L'app rejoint le Wi-Fi ${io.dayd.bebop.network.DroneWifi.SSID_PREFIX}… toute seule"
                } else {
                    "Wi-Fi du téléphone éteint — l'activer d'abord"
                },
                detailColor = if (wifiEnabled) OK_GREEN else WARN_AMBER,
                ready = wifiEnabled,
                hint = "Sticks tactiles, portée réduite",
                onClick = { vm.chooseMode(FlightMode.Phone) },
            )
        }

        // Le Wi-Fi coupé ne bloque que le mode téléphone, et l'app ne peut plus
        // le rallumer elle-même depuis Android 10 : on ouvre le panneau système.
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Text("Réglages Wi-Fi", color = Color(0xFF82B1FF), fontSize = 13.sp)
        }
    }
}

@Composable
private fun ModeCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    detail: String,
    detailColor: Color,
    ready: Boolean,
    hint: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(
                if (ready) Color(0xFF1B2A38) else Color(0xFF1A1F24),
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(detailColor, CircleShape))
                Text(subtitle, color = Color(0xFF90A4AE), fontSize = 13.sp)
            }
            Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = detailColor, fontSize = 14.sp)
        }
        Text(
            hint,
            color = Color(0xFF607D8B),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

private val OK_GREEN = Color(0xFF69F0AE)
private val WARN_AMBER = Color(0xFFFFD740)
