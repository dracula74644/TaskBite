package com.dracula.taskbite

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Lime = Color(0xFFCCFF5F)
val Ink = Color(0xFF10120E)
val Panel = Color(0xFF1C2018)
val Muted = Color(0xFFA6AF9B)
val Coral = Color(0xFFFFA18D)
val Cream = Color(0xFFF0F3E8)

@Composable fun TaskBiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Lime, onPrimary = Ink, primaryContainer = Color(0xFF303E1D),
            onPrimaryContainer = Lime, background = Ink, onBackground = Cream, surface = Ink,
            onSurface = Cream, surfaceVariant = Panel, onSurfaceVariant = Muted,
            secondary = Coral, outline = Color(0xFF414836), error = Coral),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 39.sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp),
        ), content = content)
}

@Composable fun BiteMascot(modifier: Modifier = Modifier, happy: Boolean = false) {
    Canvas(modifier.size(76.dp)) {
        val w = size.width; val h = size.height
        drawRoundRect(Lime, Offset(w * .08f, h * .08f), Size(w * .84f, h * .74f), CornerRadius(w * .20f))
        drawPath(Path().apply { moveTo(w * .23f, h * .74f); lineTo(w * .23f, h * .98f); lineTo(w * .49f, h * .74f); close() }, Lime)
        if (happy) {
            drawLine(Ink, Offset(w * .25f, h * .36f), Offset(w * .39f, h * .30f), w * .055f)
            drawLine(Ink, Offset(w * .62f, h * .30f), Offset(w * .76f, h * .36f), w * .055f)
        } else {
            drawRoundRect(Ink, Offset(w * .25f, h * .30f), Size(w * .12f, h * .15f), CornerRadius(3f))
            drawRoundRect(Ink, Offset(w * .63f, h * .30f), Size(w * .12f, h * .15f), CornerRadius(3f))
        }
        drawLine(Ink, Offset(w * .25f, h * .56f), Offset(w * .75f, h * .56f), w * .045f)
        drawPath(Path().apply { moveTo(w * .30f, h * .56f); lineTo(w * .39f, h * .71f); lineTo(w * .43f, h * .56f); close() }, Ink)
        drawPath(Path().apply { moveTo(w * .58f, h * .56f); lineTo(w * .62f, h * .71f); lineTo(w * .72f, h * .56f); close() }, Ink)
    }
}
