package com.example.flash.handshake

import android.graphics.Color
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.abs

class ColorMatcher(private val targetColor: Int) {
    private val targetHsv = FloatArray(3)
    private var firstMatchTimeMs = -1L
    private val lockDurationMs = 1000L
    var lastMatchStrength = 0f
        private set

    init { Color.colorToHSV(targetColor, targetHsv) }

    fun analyzeFrame(
        yData: ByteArray, uData: ByteArray, vData: ByteArray,
        width: Int, height: Int,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int
    ): Boolean {
        val cx = width / 2; val cy = height / 2
        val radius = minOf(width, height) / 5
        var matchingPixels = 0; var highSatPixels = 0
        val hsv = FloatArray(3)

        for (y in maxOf(0, cy - radius)..minOf(height - 1, cy + radius)) {
            for (x in maxOf(0, cx - radius)..minOf(width - 1, cx + radius)) {
                val yIdx = y * yRowStride + x
                val uvIdx = (y / 2) * uvRowStride + (x / 2) * uvPixelStride
                if (yIdx >= yData.size || uvIdx >= uData.size || uvIdx >= vData.size) continue
                val yv = (yData[yIdx].toInt() and 0xFF) - 16
                val uv = (uData[uvIdx].toInt() and 0xFF) - 128
                val vv = (vData[uvIdx].toInt() and 0xFF) - 128
                val r = ((298 * yv + 409 * vv + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * yv - 100 * uv - 208 * vv + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * yv + 516 * uv + 128) shr 8).coerceIn(0, 255)
                Color.RGBToHSV(r, g, b, hsv)
                if (hsv[1] < 0.50f) continue
                highSatPixels++
                val hueDiff = minOf(abs(hsv[0] - targetHsv[0]), 360f - abs(hsv[0] - targetHsv[0]))
                if (hueDiff < 35f) matchingPixels++
            }
        }

        if (highSatPixels < 20) { lastMatchStrength = 0f; firstMatchTimeMs = -1L; return false }
        val fillRatio = matchingPixels.toFloat() / highSatPixels
        lastMatchStrength = fillRatio
        val isMatch = fillRatio > 0.35f
        val now = System.currentTimeMillis()
        return if (isMatch) {
            if (firstMatchTimeMs < 0) firstMatchTimeMs = now
            (now - firstMatchTimeMs) >= lockDurationMs
        } else { firstMatchTimeMs = -1L; false }
    }
}

@Composable
fun MotherCoreViewfinder(
    targetColor: Int,
    onMatchStrengthChanged: (Float) -> Unit,
    onColorLocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(Unit) {
        ProcessCameraProvider.getInstance(context).addListener({
            cameraProvider = ProcessCameraProvider.getInstance(context).get()
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown(); cameraProvider?.unbindAll() }
    }

    Box(modifier = modifier.size(160.dp).clip(CircleShape)) {
        if (cameraProvider != null) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } },
                modifier = Modifier.size(160.dp)
            ) { previewView ->
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val matcher = ColorMatcher(targetColor)
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { analysis ->
                        analysis.setAnalyzer(executor) { image ->
                            val planes = image.planes
                            val locked = matcher.analyzeFrame(
                                planes[0].buffer.array(), planes[1].buffer.array(), planes[2].buffer.array(),
                                image.width, image.height,
                                planes[0].rowPitch, planes[1].rowPitch, planes[1].pixelStride
                            )
                            ContextCompat.getMainExecutor(context).execute {
                                onMatchStrengthChanged(matcher.lastMatchStrength)
                                if (locked) onColorLocked()
                            }
                            image.close()
                        }
                    }
                try {
                    cameraProvider?.unbindAll()
                    cameraProvider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalyzer)
                } catch (e: Exception) { Log.e("MotherCoreViewfinder", "Camera binding failed", e) }
            }
        }
    }
}