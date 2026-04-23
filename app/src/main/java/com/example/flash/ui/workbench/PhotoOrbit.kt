package com.example.flash.ui.workbench

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun PhotoOrbit(
    photos: List<Uri>,
    coreCenter: Offset,
    modifier: Modifier = Modifier
) {
    if (photos.isEmpty()) return

    val density = LocalDensity.current
    val baseOrbitRadiusPx = with(density) { 100.dp.toPx() }
    val photoSizeDp = 56.dp

    val infiniteTransition = rememberInfiniteTransition(label = "orbit")

    // Main rotation — full circle in 8 seconds (≈ 1dp/s arc speed)
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_time"
    )

    // Low-frequency radial drift — 0 → 8dp → 0 over 8s (sine wave at 1dp/s)
    val radialDrift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = with(density) { 8.dp.toPx() },
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radial_drift"
    )

    Box(modifier = modifier.fillMaxSize()) {
        photos.forEachIndexed { index, uri ->
            val phaseOffset = (index.toFloat() / photos.size) * (2 * PI).toFloat()
            val orbitR = baseOrbitRadiusPx + radialDrift

            // Elliptical drift: different x/y frequencies
            val x = coreCenter.x + orbitR * cos(time + phaseOffset)
            val y = coreCenter.y + orbitR * sin((time * 0.6f) + phaseOffset)

            val photoSizePx = with(density) { photoSizeDp.toPx() }

            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(photoSizeDp)
                    .clip(CircleShape)
                    .offset {
                        IntOffset(
                            x = (x - photoSizePx / 2f).roundToInt(),
                            y = (y - photoSizePx / 2f).roundToInt()
                        )
                    }
            )
        }
    }
}
