package com.example.flash.ui.core

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.os.Build
import androidx.compose.ui.graphics.nativeCanvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
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

    // Continuous time for Perlin noise seed
    val infiniteTransition = rememberInfiniteTransition(label = "core")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_000_000, easing = LinearEasing)
        ),
        label = "time"
    )

    // Hollow progress during receive
    val hollowProgress by animateFloatAsState(
        targetValue = if (isReceiving) progress else 0f,
        animationSpec = tween(300),
        label = "hollow"
    )

    // Exit scale animation
    val exitScale = remember { Animatable(1f) }
    var exitDone by remember { mutableStateOf(false) }
    LaunchedEffect(shouldExit) {
        if (shouldExit && !exitDone) {
            exitScale.animateTo(
                0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness    = Spring.StiffnessMedium
                )
            )
            exitDone = true
            onAnimationComplete()
        }
    }

    Canvas(
        modifier = modifier.size((BASE_RADIUS_DP * 2 + NOISE_OFFSET_DP * 2 + 24f).dp)
    ) {
        val cx = size.width  / 2f
        val cy = size.height / 2f
        val baseR = BASE_RADIUS_DP.dp.toPx()
        val noiseR = NOISE_OFFSET_DP.dp.toPx()
        val t = time.toDouble()

        scale(scale = exitScale.value, pivot = Offset(cx, cy)) {
            val blobPath = buildBlobPath(cx, cy, baseR, noiseR, t)

            // Outer neon pulse — Dark mode only
            if (isDark) {
                drawBlobBlurred(
                    path        = blobPath,
                    color       = AquaPulse.toArgb(),
                    blurRadius  = 24.dp.toPx(),
                    blurStyle   = BlurMaskFilter.Blur.OUTER
                )
            }

            // Core fill with inner glow
            drawBlobBlurred(
                path       = blobPath,
                color      = OceanAqua.toArgb(),
                blurRadius = 0f,
                blurStyle  = BlurMaskFilter.Blur.NORMAL
            )
            drawBlobBlurred(
                path       = blobPath,
                color      = AquaGlow.toArgb(),
                blurRadius = 14.dp.toPx(),
                blurStyle  = BlurMaskFilter.Blur.INNER
            )

            // Hollow cutout during receive
            if (isReceiving && hollowProgress > 0f) {
                val hollowR = baseR * hollowProgress * 0.85f
                clipPath(blobPath) {
                    drawCircle(
                        color  = androidx.compose.ui.graphics.Color.Transparent,
                        radius = hollowR,
                        center = Offset(cx, cy)
                    )
                }

                // Crystallized photo inside hollow
                if (crystallizedBitmap != null && hollowProgress > 0.2f) {
                    clipPath(blobPath) {
                        val bitmapSize = (hollowR * 2).toInt().coerceAtLeast(1)
                        drawImage(
                            image  = crystallizedBitmap,
                            topLeft = Offset(cx - hollowR, cy - hollowR)
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.buildBlobPath(
    cx: Float,
    cy: Float,
    baseR: Float,
    noiseAmp: Float,
    time: Double
): Path {
    val path = Path()
    val step = (2 * PI / CONTROL_POINTS)

    // Generate perturbed control points
    val points = Array(CONTROL_POINTS) { i ->
        val angle = i * step
        val nx = cos(angle) + time * 0.0003
        val ny = sin(angle) + time * 0.0003
        val noise = PerlinNoise.octaveNoise(nx, ny, octaves = 3, persistence = 0.6).toFloat()
        val r = baseR + noise * noiseAmp
        Offset(
            x = cx + r * cos(angle).toFloat(),
            y = cy + r * sin(angle).toFloat()
        )
    }

    // Build smooth closed curve through control points using cubic Bézier
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

// BlurMaskFilter requires nativeCanvas — hardware-accelerated Compose Canvas ignores it otherwise.
private fun DrawScope.drawBlobBlurred(
    path: Path,
    color: Int,
    blurRadius: Float,
    blurStyle: BlurMaskFilter.Blur
) {
    val frameworkPaint = Paint().apply {
        isAntiAlias = true
        this.color = color
        if (blurRadius > 0f) {
            maskFilter = BlurMaskFilter(blurRadius, blurStyle)
        }
    }
    drawContext.canvas.nativeCanvas.drawPath(path.asAndroidPath(), frameworkPaint)
}
