package com.dracula.taskbite

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Lime = Color(0xFFCCFF5F)
val Ink = Color(0xFF10120E)
val Panel = Color(0xFF1C2018)
val PanelRaised = Color(0xFF242A1E)
val Muted = Color(0xFFA6AF9B)
val Coral = Color(0xFFFFA18D)
val Cream = Color(0xFFF0F3E8)
val HeroGreen = Color(0xFF28321D)
val OutlineGreen = Color(0xFF414836)
val SurfaceLow = Color(0xFF171B14)
val SurfaceHigh = Color(0xFF2D3427)
val PrimaryContainer = Color(0xFF344620)

@Composable fun TaskBiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Lime, onPrimary = Ink, primaryContainer = PrimaryContainer,
            onPrimaryContainer = Lime, background = Ink, onBackground = Cream, surface = Ink,
            onSurface = Cream, surfaceVariant = Panel, onSurfaceVariant = Muted,
            secondary = Color(0xFFBDCCA9), onSecondary = Ink, secondaryContainer = PanelRaised, onSecondaryContainer = Cream,
            tertiary = Coral, onTertiary = Ink, tertiaryContainer = Color(0xFF4A3028), onTertiaryContainer = Color(0xFFFFDAD0),
            surfaceContainerHighest = SurfaceHigh, surfaceContainerHigh = PanelRaised,
            surfaceContainer = Panel, surfaceContainerLow = SurfaceLow, surfaceContainerLowest = Ink,
            surfaceTint = Lime, inverseSurface = Cream, inverseOnSurface = Ink, inversePrimary = Color(0xFF446118),
            outline = OutlineGreen, outlineVariant = Color(0xFF30382A), error = Coral, onError = Ink,
            errorContainer = Color(0xFF4A3028), onErrorContainer = Color(0xFFFFDAD0)),
        shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 39.sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
            headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
            labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp),
        ), content = content)
}

/** One selected option, with Material selection semantics and room for larger text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BiteChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().heightIn(min = 48.dp).height(IntrinsicSize.Min)) {
        options.forEachIndexed { index, label ->
            SegmentedButton(selected = selected == index, onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.fillMaxHeight().heightIn(min = 48.dp), icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.primaryContainer, activeContentColor = colors.onPrimaryContainer,
                    activeBorderColor = colors.primaryContainer, inactiveContainerColor = colors.surfaceContainerLow,
                    inactiveContentColor = colors.onSurfaceVariant, inactiveBorderColor = colors.outlineVariant),
            ) { Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
        }
    }
}

/** Small pill used for task state at a glance (Overdue, Started, priority, duration). */
@Composable fun StatusChip(text: String, color: Color = Muted, filled: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (filled) Ink else color,
        modifier = Modifier
            .background(if (filled) color else color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** Dot marker for timeline/legend use. */
@Composable fun Dot(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier.size(9.dp).background(color, CircleShape))
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
