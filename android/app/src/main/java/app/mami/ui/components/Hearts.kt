package app.mami.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import app.mami.ui.theme.Mami
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** A heart of width [size] centred on [center]. */
fun heartPath(size: Float, center: Offset): Path {
    val left = center.x - size / 2
    val top = center.y - size / 2
    fun x(v: Float) = left + v * size
    fun y(v: Float) = top + v * size
    return Path().apply {
        moveTo(x(0.5f), y(0.9f))
        cubicTo(x(0.15f), y(0.65f), x(0f), y(0.45f), x(0f), y(0.28f))
        cubicTo(x(0f), y(0.12f), x(0.13f), y(0f), x(0.28f), y(0f))
        cubicTo(x(0.38f), y(0f), x(0.46f), y(0.06f), x(0.5f), y(0.14f))
        cubicTo(x(0.54f), y(0.06f), x(0.62f), y(0f), x(0.72f), y(0f))
        cubicTo(x(0.87f), y(0f), x(1f), y(0.12f), x(1f), y(0.28f))
        cubicTo(x(1f), y(0.45f), x(0.85f), y(0.65f), x(0.5f), y(0.9f))
        close()
    }
}

/** Soft, slowly drifting colour blobs in the theme's gradient colours. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = Mami.colors.gradient
    val strength = if (Mami.colors.isDark) 0.38f else 0.30f
    val t by rememberInfiniteTransition(label = "aurora").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "t",
    )
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                val w = size.width
                val h = size.height
                val blobs = listOf(
                    Triple(Offset(w * (0.1f + 0.3f * t), h * (0.1f + 0.08f * t)), w * 0.85f, colors.first()),
                    Triple(Offset(w * (1.0f - 0.3f * t), h * (0.42f + 0.12f * t)), w * 0.75f, colors[colors.size / 2]),
                    Triple(Offset(w * (0.2f + 0.45f * t), h * (0.92f - 0.1f * t)), w * 0.9f, colors.last()),
                )
                blobs.forEach { (center, radius, color) ->
                    drawCircle(
                        brush = Brush.radialGradient(listOf(color.copy(alpha = strength), Color.Transparent), center, radius),
                        radius = radius,
                        center = center,
                    )
                }
            },
        content = content,
    )
}

private class Floater(val x: Float, val size: Float, val speed: Float, val phase: Float, val wobble: Float)

/** Hearts floating gently upwards, for welcome screens. */
@Composable
fun FloatingHearts(modifier: Modifier = Modifier, count: Int = 14, color: Color = Mami.colors.gradient.first()) {
    val floaters = remember(count) {
        List(count) { i ->
            val r = Random(i * 7919 + 13)
            Floater(r.nextFloat(), 10f + r.nextFloat() * 20f, 0.035f + r.nextFloat() * 0.05f, r.nextFloat(), r.nextFloat() * 6.28f)
        }
    }
    val seconds by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1_000_000_000f }
    }
    Canvas(modifier.fillMaxSize()) {
        floaters.forEach { f ->
            val p = (seconds * f.speed + f.phase) % 1f
            val y = size.height * (1.08f - p * 1.16f)
            val x = size.width * f.x + sin(p * 12.56f + f.wobble) * 16.dp.toPx()
            val fade = sin(p * PI).toFloat()
            drawPath(heartPath(f.size.dp.toPx(), Offset(x, y)), color.copy(alpha = 0.32f * fade))
        }
    }
}

private class BurstHeart(val dx: Float, val rise: Float, val size: Float, val color: Color, val spin: Float, val delay: Float)

/** A shower of hearts. Each new [trigger] value (other than 0) plays it once. */
@Composable
fun HeartBurst(trigger: Int, modifier: Modifier = Modifier) {
    if (trigger == 0) return
    val palette = Mami.colors.gradient + Color(0xFFFF4F8B)
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { progress.animateTo(1f, tween(1800, easing = LinearOutSlowInEasing)) }
    val hearts = remember(trigger) {
        List(26) {
            val r = Random(trigger * 31 + it)
            BurstHeart(
                dx = r.nextFloat() * 2 - 1,
                rise = 0.35f + r.nextFloat() * 0.5f,
                size = 16f + r.nextFloat() * 30f,
                color = palette[r.nextInt(palette.size)],
                spin = r.nextFloat() * 50 - 25,
                delay = r.nextFloat() * 0.3f,
            )
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        val origin = Offset(size.width / 2, size.height * 0.82f)
        hearts.forEach { h ->
            val local = ((p - h.delay) / (1 - h.delay)).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach
            val x = origin.x + h.dx * size.width * 0.42f * local
            val y = origin.y - h.rise * size.height * local
            val heartSize = h.size.dp.toPx() * (0.5f + 0.7f * local)
            rotate(h.spin * local, Offset(x, y)) {
                drawPath(heartPath(heartSize, Offset(x, y)), h.color.copy(alpha = (1f - local) * 0.95f))
            }
        }
    }
}

/** A faint pattern of little hearts behind the conversation. */
fun Modifier.heartWallpaper(color: Color, enabled: Boolean): Modifier = if (!enabled) this else drawBehind {
    val step = 54.dp.toPx()
    val heart = 11.dp.toPx()
    var row = 0
    var y = step / 2
    while (y < size.height + step) {
        var x = if (row % 2 == 0) step / 2 else step
        while (x < size.width + step) {
            drawPath(heartPath(heart, Offset(x, y)), color)
            x += step
        }
        y += step * 0.8f
        row++
    }
}
