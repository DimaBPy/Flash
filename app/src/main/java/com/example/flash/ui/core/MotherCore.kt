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

private const val CONTROL_POINTS  = 16
private const val BASE_RADIUS_DP  = 60f
private const val NOISE_OFFSET_DP = 14f

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

    val blobSize = (BASE_RADIUS_DP * 2 + NOISE_OFFSET_DP * 2 + 32f).dp

    Box(modifier = modifier.size(blobSize).scale(exitScale.value)) {

        // Layer 1a: Primary outer glow (dark mode only)
        if (isDark) {
            Canvas(modifier = Modifier.fillMaxSize().blur(32.dp)) {
                drawPath(
                    buildBlobPath(center.x, center.y, BASE_RADIUS_DP.dp.toPx(), NOISE_OFFSET_DP.dp.toPx(), time.toDouble()),
                    color = AquaPulse
                )
            }
            // Layer 1b: Wider ambient halo
            Canvas(modifier = Modifier.fillMaxSize().blur(56.dp)) {
                drawPath(
                    buildBlobPath(center.x, center.y, BASE_RADIUS_DP.dp.toPx(), NOISE_OFFSET_DP.dp.toPx(), time.toDouble()),
                    color = AquaPulse.copy(alpha = 0.35f)
                )
            }
        } else {
            // Light mode: subtle teal rim
            Canvas(modifier = Modifier.fillMaxSize().blur(8.dp)) {
                drawPath(
                    buildBlobPath(center.x, center.y, BASE_RADIUS_DP.dp.toPx(), NOISE_OFFSET_DP.dp.toPx(), time.toDouble()),
                    color = AquaPulse.copy(alpha = 0.55f)
                )
            }
        }

        // Layer 2: Core fill + hollow/crystallize (Offscreen so BlendMode.Clear works)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            val baseR  = BASE_RADIUS_DP.dp.toPx()
            val noiseR = NOISE_OFFSET_DP.dp.toPx()
            val path   = buildBlobPath(center.x, center.y, baseR, noiseR, time.toDouble())

            drawPath(path, color = OceanAqua)

            if (isReceiving && hollowProgress > 0f) {
                val hollowR = baseR * hollowProgress * 0.85f
                clipPath(path) {
                    drawCircle(
                        color     = Color.Black,
                        radius    = hollowR,
                        center    = center,
                        blendMode = BlendMode.Clear
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

        // Layer 3: Glass shimmer — soft radial highlight in upper-left
        Canvas(modifier = Modifier.fillMaxSize().blur(14.dp)) {
            val baseR  = BASE_RADIUS_DP.dp.toPx()
            val noiseR = NOISE_OFFSET_DP.dp.toPx()
            val path   = buildBlobPath(center.x, center.y, baseR, noiseR, time.toDouble())
            clipPath(path) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors  = listOf(Color.White.copy(alpha = 0.65f), AquaGlow, Color.Transparent),
                        center  = Offset(center.x - baseR * 0.25f, center.y - baseR * 0.40f),
                        radius  = baseR * 0.80f
                    ),
                    radius = baseR * 0.80f,
                    center = Offset(center.x - baseR * 0.25f, center.y - baseR * 0.40f)
                )
            }
        }
    }
}

// Catmull-Rom → cubic Bézier for a smooth, organic closed blob curve.
// `internal` so PhotoOrbit can reuse it for photo shape morphing.
internal fun buildBlobPath(cx: Float, cy: Float, baseR: Float, noiseAmp: Float, time: Double): Path {
    val path = Path()
    val n    = CONTROL_POINTS
    val step = 2 * PI / n
    val pts  = Array(n) { i ->
        val angle = i * step
        val noise = PerlinNoise.octaveNoise(
            cos(angle) * 1.5 + time * 0.0004,
            sin(angle) * 1.5 + time * 0.0004,
            octaves = 3, persistence = 0.55
        ).toFloat()
        val r = baseR + noise * noiseAmp
        Offset(cx + r * cos(angle).toFloat(), cy + r * sin(angle).toFloat())
    }

    path.moveTo(pts[0].x, pts[0].y)
    for (i in 0 until n) {
        val p0 = pts[(i - 1 + n) % n]
        val p1 = pts[i]
        val p2 = pts[(i + 1)     % n]
        val p3 = pts[(i + 2)     % n]
        val cp1x = p1.x + (p2.x - p0.x) / 6f
        val cp1y = p1.y + (p2.y - p0.y) / 6f
        val cp2x = p2.x - (p3.x - p1.x) / 6f
        val cp2y = p2.y - (p3.y - p1.y) / 6f
        path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
    }
    path.close()
    return path
}
