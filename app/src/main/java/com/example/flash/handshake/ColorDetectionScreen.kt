package com.example.flash.handshake

import android.Manifest
import android.content.Context
import android.graphics.Color
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Analyzes camera frames to detect when the dominant color matches a target.
 */
class ColorMatcher(private val targetColor: Int) {
    private val targetHsv = FloatArray(3)
    private val frameHsv = FloatArray(3)
    private var matchCount = 0
    private val matchThreshold = 5  // Frames to hold for confirmation

    init {
        Color.colorToHSV(targetColor, targetHsv)
    }

    fun analyzeFrame(rgbData: IntArray): Boolean {
        val dominantColor = computeDominantColor(rgbData)
        Color.colorToHSV(dominantColor, frameHsv)

        val hueDiff = minOf(abs(frameHsv[0] - targetHsv[0]), 360f - abs(frameHsv[0] - targetHsv[0]))
        val satDiff = abs(frameHsv[1] - targetHsv[1])
        val valDiff = abs(frameHsv[2] - targetHsv[2])

        // Hue is most important; allow 30° tolerance
        val isMatch = hueDiff < 30f && satDiff < 0.2f && valDiff < 0.2f

        matchCount = if (isMatch) matchCount + 1 else 0
        return matchCount >= matchThreshold
    }

    private fun computeDominantColor(rgbData: IntArray): Int {
        val r = rgbData.sumOf { (it shr 16) and 0xFF } / rgbData.size
        val g = rgbData.sumOf { (it shr 8) and 0xFF } / rgbData.size
        val b = rgbData.sumOf { it and 0xFF } / rgbData.size
        return Color.rgb(r, g, b)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ColorDetectionScreen(
    displayColor: Int,
    targetColor: Int,
    peerName: String,
    onColorDetected: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    var colorDetected by remember { mutableStateOf(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val executorService = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(Unit) {
        if (!cameraPermission.hasPermission) {
            cameraPermission.launchPermissionRequest()
        }
    }

    LaunchedEffect(cameraPermission.hasPermission) {
        if (!cameraPermission.hasPermission) return@LaunchedEffect

        ProcessCameraProvider.getInstance(context).addListener({
            cameraProvider = ProcessCameraProvider.getInstance(context).get()
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose {
            executorService.shutdown()
            cameraProvider?.unbindAll()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Display color target (what the other phone is showing)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(ComposeColor(targetColor))
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Tap camera on\nthis color on $peerName",
                color = ComposeColor.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.background(
                    ComposeColor.Black.copy(alpha = 0.5f),
                    RoundedCornerShape(8.dp)
                ).padding(16.dp)
            )
        }

        // Camera preview
        if (cameraPermission.hasPermission && cameraProvider != null) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        val preview = Preview.Builder().build()
                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        val colorMatcher = ColorMatcher(targetColor)

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .apply {
                                setAnalyzer(executorService) { imageProxy ->
                                    try {
                                        val planes = imageProxy.planes
                                        val buffer = planes[0].buffer
                                        val pixelStride = planes[0].pixelStride
                                        val width = imageProxy.width
                                        val height = imageProxy.height
                                        val rowPadding = planes[0].rowPadding

                                        val data = ByteArray(buffer.remaining())
                                        buffer.get(data)

                                        // Sample center region for efficiency
                                        val centerX = width / 2
                                        val centerY = height / 2
                                        val sampleSize = minOf(width, height) / 4
                                        val rgbData = mutableListOf<Int>()

                                        for (y in (centerY - sampleSize)..(centerY + sampleSize)) {
                                            for (x in (centerX - sampleSize)..(centerX + sampleSize)) {
                                                val index = y * rowPadding + x * pixelStride
                                                if (index in data.indices) {
                                                    val y_val = data[index].toInt() and 0xFF
                                                    val u = data[index + 1].toInt() and 0xFF
                                                    val v = data[index + 2].toInt() and 0xFF
                                                    val rgb = yuvToRgb(y_val, u, v)
                                                    rgbData.add(rgb)
                                                }
                                            }
                                        }

                                        if (colorMatcher.analyzeFrame(rgbData.toIntArray())) {
                                            if (!colorDetected) {
                                                colorDetected = true
                                                onColorDetected()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("ColorDetection", "Error analyzing frame", e)
                                    } finally {
                                        imageProxy.close()
                                    }
                                }
                            }

                        try {
                            cameraProvider?.unbindAll()
                            cameraProvider?.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )
                            preview.surfaceProvider = this.surfaceProvider
                        } catch (e: Exception) {
                            Log.e("ColorDetection", "Camera binding failed", e)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

private fun yuvToRgb(y: Int, u: Int, v: Int): Int {
    val yy = y - 16
    val uu = u - 128
    val vv = v - 128

    val r = ((298 * yy + 409 * vv + 128) shr 8).coerceIn(0, 255)
    val g = ((298 * yy - 100 * uu - 208 * vv + 128) shr 8).coerceIn(0, 255)
    val b = ((298 * yy + 516 * uu + 128) shr 8).coerceIn(0, 255)

    return (r shl 16) or (g shl 8) or b
}
