package com.example.flash.ui.workbench

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.example.flash.ui.core.updateBlobPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun PhotoOrbit(
    photos: List<Uri>,
    coreCenter: Offset,
    modifier: Modifier = Modifier,
    shouldExit: Boolean = false
) {
    val density = LocalDensity.current

    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "orbit")

    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_time"
    )

    val blobTime by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 1000f,
        animationSpec = infiniteRepeatable(tween(1_000_000, easing = LinearEasing)),
        label = "blob_time"
    )

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
    val photoSizeDp = 56.dp
    val photoSizePx = with(density) { photoSizeDp.toPx() }

    // Track all photos including those animating out
    val visiblePhotos = remember { mutableStateListOf<Uri>() }
    val exitingPhotos = remember { mutableStateSetOf<Uri>() }

    LaunchedEffect(photos) {
        // Add newly arrived photos
        photos.forEach { uri ->
            if (uri !in visiblePhotos) visiblePhotos.add(uri)
        }
        // Mark removed photos as exiting (they'll animate out)
        visiblePhotos.filter { it !in photos && it !in exitingPhotos }.forEach { uri ->
            exitingPhotos.add(uri)
        }
    }

    if (visiblePhotos.isEmpty()) return

    Box(modifier = modifier.fillMaxSize()) {
        visiblePhotos.toList().forEachIndexed { index, uri ->
            key(uri) {
                OrbitPhotoItem(
                    uri = uri,
                    index = index,
                    totalPhotos = visiblePhotos.size,
                    coreCenter = coreCenter,
                    time = time,
                    blobTime = blobTime,
                    baseOrbitRadiusPx = baseOrbitRadiusPx,
                    radialDrift = radialDrift,
                    photoSizeDp = photoSizeDp,
                    photoSizePx = photoSizePx,
                    isExiting = uri in exitingPhotos || shouldExit,
                    onExitComplete = {
                        visiblePhotos.remove(uri)
                        exitingPhotos.remove(uri)
                    }
                )
            }
        }
    }
}

@Composable
private fun OrbitPhotoItem(
    uri: Uri,
    index: Int,
    totalPhotos: Int,
    coreCenter: Offset,
    time: Float,
    blobTime: Float,
    baseOrbitRadiusPx: Float,
    radialDrift: Float,
    photoSizeDp: androidx.compose.ui.unit.Dp,
    photoSizePx: Float,
    isExiting: Boolean,
    onExitComplete: () -> Unit
) {
    val entryProgress = remember { Animatable(0f) }
    val exitProgress = remember { Animatable(0f) }

    // Entry animation
    LaunchedEffect(Unit) {
        entryProgress.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 150f))
    }

    // Exit animation
    LaunchedEffect(isExiting) {
        if (isExiting && exitProgress.value == 0f) {
            exitProgress.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
            onExitComplete()
        }
    }

    val phaseOffset = (index.toFloat() / totalPhotos) * (2 * PI).toFloat()
    val orbitR = baseOrbitRadiusPx + radialDrift

    val orbitX = coreCenter.x + orbitR * cos(time + phaseOffset)
    val orbitY = coreCenter.y + orbitR * sin(time + phaseOffset)

    val ep = entryProgress.value
    val xp = exitProgress.value

    // During exit: lerp toward coreCenter; during entry: lerp from coreCenter
    val x = lerp(lerp(coreCenter.x, orbitX, ep), coreCenter.x, xp)
    val y = lerp(lerp(coreCenter.y, orbitY, ep), coreCenter.y, xp)
    val scale = lerp(ep, 0f, xp)

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
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = scale
            }
            .drawWithCache {
                val path = Path()
                onDrawWithContent {
                    updateBlobPath(
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
