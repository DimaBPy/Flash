package com.example.flash.ui.core

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.flash.ui.theme.AquaGlow
import com.example.flash.ui.theme.AquaPulse
import com.example.flash.ui.theme.OceanAqua
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val CONTROL_POINTS = 8
private const val BASE_RADIUS_DP  = 56f
private const val NOISE_OFFSET_DP = 10f

@Composable
fun MotherCore(
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    isReceiving: Boolean = false,
    crystallizedBitmap: ImageBitmap? = null,
    shouldExit: Boolean = false,
    onAnimationComplete: () -> Unit = {}
) {
    val isDark = isSystemInDarkTheme()

    val infiniteTransition = rememberInfiniteTransition(label = "core")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 1000f,
        animationSpec = infiniteRepeatable(tween(1_000_000, easing = LinearEasing)),
        label = "time"
    )

    val hollowProgress by animateFloatAsState(
        targetValue   = if (isReceiving) progress else 0f,
        animationSpec = tween(300),
        label         = "hollow"
    )

    val exitScale = remember { Animatable(1f) }
    var exitDone by remember { mutableStateOf(false) }
    LaunchedEffect(shouldExit) {
        if (shouldExit && !exitDone) {
            exitScale.animateTo(
                0f,
                spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)
            )
            exitDone = true
            onAnimationComplete()
        }
    }

    val blobSize = (BASE_RADIUS_DP * 2 + NOISE_OFFSET_DP * 2 + 24f).dp
    val scaleValue = exitScale.value

    Box(modifier = modifier.size(blobSize).scale(scaleValue)) {

        // ── Layer 1: Outer neon pulse (dark mode only, hardware-blurred) ──────
        if (isDark) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(28.dp)
            ) {
                val t = time.toDouble()
                val path = buildBlobPath(center.x, center.y, BASE_RADIUS_DP.dp.toPx(), NOISE_OFFSET_DP.dp.toPx(), t)
                drawPath(path, color = AquaPulse)
            }
        }

        // ── Layer 2: Core solid fill + hollow/crystallize ─────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            val t     = time.toDouble()
            val baseR = BASE_RADIUS_DP.dp.toPx()
            val noiseR = NOISE_OFFSET_DP.dp.toPx()
            val path  = buildBlobPath(center.x, center.y, baseR, noiseR, t)

            drawPath(path, color = OceanAqua)

            if (isReceiving && hollowProgress > 0f) {
                val hollowR = baseR * hollowProgress * 0.85f
                clipPath(path) {
                    drawCircle(
                        color      = Color.Black,
                        radius     = hollowR,
                        center     = center,
                        blendMode  = BlendMode.Clear
                    )
                }
                if (crystallizedBitmap != null && hollowProgress > 0.2f) {
                    clipPath(path) {
                        drawImage(
                            image   = crystallizedBitmap,
                            topLeft = Offset(center.x - hollowR, center.y - hollowR)
                        )
                    }
                }
            }
        }

        // ── Layer 3: Glass inner shimmer (top-left highlight, blurred) ────────
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .blur(14.dp)
        ) {
            val t     = time.toDouble()
            val baseR = BASE_RADIUS_DP.dp.toPx()
            val noiseR = NOISE_OFFSET_DP.dp.toPx()
            val path  = buildBlobPath(center.x, center.y, baseR, noiseR, t)
            clipPath(path) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors  = listOf(Color.White.copy(alpha = 0.55f), AquaGlow, Color.Transparent),
                        center  = Offset(center.x - baseR * 0.22f, center.y - baseR * 0.38f),
                        radius  = baseR * 0.7f
                    ),
                    radius = baseR * 0.7f,
                    center = Offset(center.x - baseR * 0.22f, center.y - baseR * 0.38f)
                )
            }
        }
    }
}

private fun buildBlobPath(cx: Float, cy: Float, baseR: Float, noiseAmp: Float, time: Double): Path {
    val path = Path()
    val step = (2 * PI / CONTROL_POINTS)
    val points = Array(CONTROL_POINTS) { i ->
        val angle = i * step
        val noise = PerlinNoise.octaveNoise(
            cos(angle) + time * 0.0003,
            sin(angle) + time * 0.0003,
            octaves = 3, persistence = 0.6
        ).toFloat()
        val r = baseR + noise * noiseAmp
        Offset(cx + r * cos(angle).toFloat(), cy + r * sin(angle).toFloat())
    }
    path.moveTo(points[0].x, points[0].y)
    for (i in points.indices) {
        val curr = points[i]
        val next = points[(i + 1) % points.size]
        val cp1  = Offset(curr.x + (next.x - curr.x) / 3f, curr.y + (next.y - curr.y) / 3f)
        val cp2  = Offset(next.x - (next.x - curr.x) / 3f, next.y - (next.y - curr.y) / 3f)
        path.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, next.x, next.y)
    }
    path.close()
    return path
}
