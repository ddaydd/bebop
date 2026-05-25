package io.dayd.bebop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

@Composable
fun FloatingJoystick(
    modifier: Modifier = Modifier,
    radius: Dp = 70.dp,
    onInput: (x: Float, y: Float) -> Unit,
) {
    var center by remember { mutableStateOf(Offset.Unspecified) }
    var thumb by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val radiusPx = with(density) { radius.toPx() }
    val thumbRadiusPx = radiusPx * 0.35f
    val strokePx = with(density) { 2.dp.toPx() }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    center = down.position
                    thumb = Offset.Zero
                    active = true
                    onInput(0f, 0f)
                    down.consume()

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            val delta = change.position - center
                            val dist = sqrt(delta.x * delta.x + delta.y * delta.y)
                            val clamped = if (dist > radiusPx) delta * (radiusPx / dist) else delta
                            thumb = clamped
                            onInput(clamped.x / radiusPx, -clamped.y / radiusPx)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed && it.id == down.id })

                    active = false
                    thumb = Offset.Zero
                    onInput(0f, 0f)
                }
            }
    ) {
        if (active && center != Offset.Unspecified) {
            drawCircle(
                color = Color.White.copy(alpha = 0.12f),
                radius = radiusPx,
                center = center,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.35f),
                radius = radiusPx,
                center = center,
                style = Stroke(strokePx),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.55f),
                radius = thumbRadiusPx,
                center = center + thumb,
            )
        }
    }
}
