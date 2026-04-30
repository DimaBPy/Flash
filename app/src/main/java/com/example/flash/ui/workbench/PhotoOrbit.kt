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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.example.flash.ui.core.updateBlobPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Golden angle — each new photo lands at a position that never bunches with others,
// regardless of how many photos are added over time.
private val GOLDEN_ANGLE = (PI * (3.0 - sqrt(5.0))).toFloat()

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

    val visiblePhotos = remember { mutableStateListOf<Uri>() }
    val exitingPhotos = remember { mutableStateSetOf<Uri>() }
    // Each URI gets a golden-angle phase assigned once on entry — never recalculated,
    // so adding a new photo cannot shift existing photos' positions.
    val phaseMap = remember { mutableStateMapOf<Uri, Float>() }
    var nextPhaseIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(photos) {
        photos.forEach { uri ->
            if (uri !in visiblePhotos) {
                visiblePhotos.add(uri)
            }
            if (uri !in phaseMap) {
                phaseMap[uri] = nextPhaseIndex * GOLDEN_ANGLE
                nextPhaseIndex++
            }
        }
        visiblePhotos.filter { it !in photos && it !in exitingPhotos }.forEach { uri ->
            exitingPhotos.add(uri)
        }
    }

    if (visiblePhotos.isEmpty()) return

    Box(modifier = modifier.fillMaxSize()) {
        visiblePhotos.toList().forEach { uri ->
            key(uri) {
                val phaseOffset = phaseMap[uri] ?: 0f
                OrbitPhotoItem(
                    uri = uri,
                    phaseOffset = phaseOffset,
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
                        phaseMap.remove(uri)
                    }
                )
            }
        }
    }
}

@Composable
private fun OrbitPhotoItem(
    uri: Uri,
    phaseOffset: Float,
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
    val exitProgress  = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        entryProgress.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 150f))
    }

    LaunchedEffect(isExiting) {
        if (isExiting && exitProgress.value == 0f) {
            exitProgress.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
            onExitComplete()
        }
    }

    val orbitR = baseOrbitRadiusPx + radialDrift
    val orbitX = coreCenter.x + orbitR * cos(time + phaseOffset)
    val orbitY = coreCenter.y + orbitR * sin(time + phaseOffset)

    val ep = entryProgress.value
    val xp = exitProgress.value

    val x     = lerp(lerp(coreCenter.x, orbitX, ep), coreCenter.x, xp)
    val y     = lerp(lerp(coreCenter.y, orbitY, ep), coreCenter.y, xp)
    val scale = lerp(ep, 0f, xp)

    val photoBlobTime = (blobTime + phaseOffset * 137f).toDouble()

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
                alpha  = scale
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
