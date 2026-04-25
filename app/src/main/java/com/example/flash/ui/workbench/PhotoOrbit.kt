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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.flash.ui.core.PerlinNoise
import com.example.flash.ui.core.buildBlobPath
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

    val infiniteTransition = rememberInfiniteTransition(label = "orbit")

    // Orbit angle — full revolution in 8 s
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_time"
    )

    // Slow blob morph clock
    val blobTime by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 1000f,
        animationSpec = infiniteRepeatable(
            tween(1_000_000, easing = LinearEasing)
        ),
        label = "blob_time"
    )

    // Low-frequency radial drift
    val radialDrift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = with(density) { 8.dp.toPx() },
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radial_drift"
    )

    val baseOrbitRadiusPx = with(density) { 100.dp.toPx() }
    val photoSizeDp       = 56.dp
    val photoSizePx       = with(density) { photoSizeDp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        photos.forEachIndexed { index, uri ->
            val phaseOffset = (index.toFloat() / photos.size) * (2 * PI).toFloat()
            val orbitR = baseOrbitRadiusPx + radialDrift

            val x = coreCenter.x + orbitR * cos(time + phaseOffset)
            val y = coreCenter.y + orbitR * sin((time * 0.6f) + phaseOffset)

            val photoBlobTime = (blobTime + index * 137f).toDouble()

            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(photoSizeDp)
                    .offset {
                        IntOffset(
                            (x - photoSizePx / 2f).roundToInt(),
                            (y - photoSizePx / 2f).roundToInt()
                        )
                    }
                    .drawWithCache {
                        val path = Path()
                        onDrawWithContent {
                            // Reduced complexity for orbiting items: 1 octave instead of 3
                            com.example.flash.ui.core.updateBlobPath(
                                path     = path,
                                cx       = size.width  / 2f,
                                cy       = size.height / 2f,
                                baseR    = minOf(size.width, size.height) / 2f - 3.dp.toPx(),
                                noiseAmp = 5.dp.toPx(),
                                time     = photoBlobTime,
                                octaves  = 1
                            )
                            clipPath(path) {
                                this@onDrawWithContent.drawContent()
                            }
                        }
                    }
            )
        }
    }
}
