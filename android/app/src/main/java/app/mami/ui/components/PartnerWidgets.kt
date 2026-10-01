package app.mami.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mami.ui.theme.Fredoka
import app.mami.ui.theme.Mami
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Green when fine or charging, amber when getting low, red when nearly empty. */
@Composable
fun batteryColor(percent: Int?, charging: Boolean): Color = when {
    percent == null -> MaterialTheme.colorScheme.outline
    charging -> Mami.colors.good
    percent <= 15 -> Mami.colors.bad
    percent <= 35 -> Mami.colors.warn
    else -> Mami.colors.good
}

/**
 * The partner's initial on the theme gradient. When their battery is shared,
 * a ring around it shows how full it is; a green dot means the app is open.
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    battery: Int? = null,
    charging: Boolean = false,
    online: Boolean = false,
) {
    val ringColor = batteryColor(battery, charging)
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val sweep by animateFloatAsState((battery ?: 0).coerceIn(0, 100) / 100f, tween(900), label = "ring")
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (battery != null) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = this.size.minDimension * 0.08f
                val inset = stroke / 2
                val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
                drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(ringColor, -90f, 360f * sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        val inner = if (battery != null) size * 0.76f else size
        Box(
            Modifier
                .size(inner)
                .clip(CircleShape)
                .background(Mami.colors.brush),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.firstOrNull()?.uppercase() ?: "♥",
                color = Color.White,
                fontFamily = Fredoka,
                fontWeight = FontWeight.SemiBold,
                fontSize = (inner.value * 0.44f).sp,
            )
        }
        if (online) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.28f)
                    .clip(CircleShape)
                    .background(Mami.colors.online)
                    .border(2.dp, surface, CircleShape),
            )
        }
        if (charging && battery != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(size * 0.32f)
                    .clip(CircleShape)
                    .background(Mami.colors.good)
                    .border(2.dp, surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = "Charging", tint = Color.White, modifier = Modifier.padding(1.dp))
            }
        }
    }
}

/** Three dots hopping one after another. */
@Composable
fun TypingDots(color: Color = MaterialTheme.colorScheme.onSurfaceVariant, dotSize: Dp = 7.dp) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(dotSize * 0.6f), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = 1200
                        0f at i * 150
                        -1f at i * 150 + 300
                        0f at i * 150 + 600
                    },
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .graphicsLayer {
                        translationY = lift * dotSize.toPx()
                        alpha = 0.55f - lift * 0.45f
                    }
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** Four bars, [level] (0–4) of them filled. */
@Composable
fun SignalBars(level: Int?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val empty = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.size(22.dp, 16.dp)) {
        val gap = size.width * 0.1f
        val barWidth = (size.width - gap * 3) / 4
        repeat(4) { i ->
            val height = size.height * (0.35f + 0.65f * (i + 1) / 4f)
            drawRoundRect(
                color = if (level != null && i < level) color else empty,
                topLeft = Offset(i * (barWidth + gap), size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 3),
            )
        }
    }
}

/** A big ring that fills with the battery level. */
@Composable
fun BatteryGauge(percent: Int?, charging: Boolean, modifier: Modifier = Modifier, size: Dp = 76.dp) {
    val color = batteryColor(percent, charging)
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val sweep by animateFloatAsState((percent ?: 0) / 100f, tween(1000), label = "gauge")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.11f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(color.copy(alpha = 0.6f), color)),
                135f,
                270f * sweep,
                false,
                Offset(inset, inset),
                arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            if (percent == null) "—" else "$percent%",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (charging) {
            Icon(
                Icons.Filled.Bolt,
                contentDescription = "Charging",
                tint = color,
                modifier = Modifier.align(Alignment.BottomCenter).size(size * 0.26f),
            )
        }
    }
}

/** An analogue clock face showing [hour]:[minute]. */
@Composable
fun MiniClock(hour: Int, minute: Int, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val face = MaterialTheme.colorScheme.surfaceContainerHighest
    val hands = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val night = hour < 7 || hour >= 22
    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2
        val center = Offset(this.size.width / 2, this.size.height / 2)
        drawCircle(if (night) Color(0xFF1E2140) else face, radius, center)
        repeat(12) { i ->
            val angle = i * 30.0 * PI / 180
            val outer = Offset(center.x + sin(angle).toFloat() * radius * 0.86f, center.y - cos(angle).toFloat() * radius * 0.86f)
            drawCircle(if (night) Color(0xFFB9BFFF) else hands.copy(alpha = 0.4f), radius * if (i % 3 == 0) 0.06f else 0.035f, outer)
        }
        fun hand(fraction: Float, length: Float, width: Float, color: Color) {
            val angle = fraction * 2 * PI
            val end = Offset(center.x + sin(angle).toFloat() * radius * length, center.y - cos(angle).toFloat() * radius * length)
            drawLine(color, center, end, strokeWidth = width, cap = StrokeCap.Round)
        }
        val handColor = if (night) Color.White else hands
        hand(((hour % 12) + minute / 60f) / 12f, 0.5f, radius * 0.12f, handColor)
        hand(minute / 60f, 0.74f, radius * 0.08f, accent)
        drawCircle(accent, radius * 0.09f, center)
    }
}

/** A compact, tappable fact about the partner, like "🔋 72%". */
@Composable
fun StatusPill(
    icon: ImageVector?,
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = tint.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
            trailing?.invoke()
        }
    }
}
