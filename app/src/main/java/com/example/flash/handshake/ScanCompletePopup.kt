package com.example.flash.handshake

import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

fun colorName(color: Int): String {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color, hsv)
    return when {
        hsv[0] < 15 || hsv[0] >= 345 -> "red"
        hsv[0] < 45  -> "orange"
        hsv[0] < 75  -> "yellow"
        hsv[0] < 150 -> "green"
        hsv[0] < 185 -> "mint"
        hsv[0] < 225 -> "blue"
        hsv[0] < 260 -> "sky blue"
        hsv[0] < 295 -> "purple"
        else         -> "pink"
    }
}

@Composable
fun ScanCompletePopup(visible: Boolean, lockedColor: Int, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), { it }) + fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
        exit  = slideOutVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium), { it }) + fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0D1A1A).copy(alpha = 0.88f))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(lockedColor)))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Scanning complete. The color is ${colorName(lockedColor)}. You no longer need to hold the phones together, just keep them on the same Wi-Fi 😉",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}