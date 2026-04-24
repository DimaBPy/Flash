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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
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

    // Slow blob morph clock — different speed so edges feel alive independently
    val blobTime by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 1000f,
        animationSpec = infiniteRepeatable(
            tween(1_000_000, easing = LinearEasing)
        ),
        label = "blob_time"
    )

    // Low-frequency radial drift  ±8 dp
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

            // Elliptical trajectory (x/y use different angular speeds)
            val x = coreCenter.x + orbitR * cos(time + phaseOffset)
            val y = coreCenter.y + orbitR * sin((time * 0.6f) + phaseOffset)

            // Each photo gets its own phase in the blob clock so edges differ
            val photoBlob = blobTime + index * 137f  // 137 ≈ golden-angle offset

            val blobShape = remember(photoBlob) {
                BlobPhotoShape(photoBlob.toDouble(), index)
            }

            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(photoSizeDp)
                    .graphicsLayer(
                        shape = blobShape,
                        clip  = true,
                        compositingStrategy = CompositingStrategy.Offscreen
                    )
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

/** A [Shape] whose outline is a Perlin-noise blob — re-created each time [blobTime] changes. */
private class BlobPhotoShape(
    private val blobTime: Double,
    private val seed: Int
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = buildBlobPath(
            cx       = size.width  / 2f,
            cy       = size.height / 2f,
            baseR    = minOf(size.width, size.height) / 2f - with(density) { 3.dp.toPx() },
            noiseAmp = with(density) { 5.dp.toPx() },
            time     = blobTime + seed * 137.0
        )
        return Outline.Generic(path)
    }
}
