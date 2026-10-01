package app.mami.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.data.db.MediaType
import app.mami.data.db.MessageEntity
import app.mami.data.db.Transfer
import app.mami.media.VoicePlayer
import app.mami.sync.Notifications
import app.mami.ui.Files
import app.mami.ui.decodeWaveform
import app.mami.ui.rememberBase64Image
import app.mami.ui.theme.Mami
import coil3.compose.AsyncImage
import java.io.File

/** Width of photos and videos in the chat. */
val MediaWidth: Dp = 250.dp

/** Height for a photo or video of this size, kept between tall-portrait and panorama. */
fun mediaAspect(message: MessageEntity): Float {
    val w = message.mediaWidth ?: return 1f
    val h = message.mediaHeight ?: return 1f
    if (w <= 0 || h <= 0) return 1f
    return (w.toFloat() / h).coerceIn(0.62f, 1.7f)
}

/** A photo or video still: the real file once it's here, the blurred thumbnail until then. */
@Composable
fun MediaImage(message: MessageEntity, file: File?, modifier: Modifier = Modifier, blurPreview: Boolean = true) {
    val thumb = rememberBase64Image(message.mediaThumb)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (thumb != null) {
            Image(
                thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (blurPreview) Modifier.blur(14.dp) else Modifier),
            )
        }
        if (file != null && message.mediaKind == MediaType.PHOTO) {
            AsyncImage(
                model = file,
                contentDescription = message.body.ifBlank { "Photo" },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Photo or video in a bubble, with download / upload progress on top. */
@Composable
fun VisualContent(
    message: MessageEntity,
    file: File?,
    progress: Float?,
    shape: RoundedCornerShape,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    footer: (@Composable BoxScope.() -> Unit)?,
) {
    Box(
        Modifier
            .width(MediaWidth)
            .aspectRatio(mediaAspect(message))
            .clip(shape)
            .clickable(enabled = file != null || message.transfer != Transfer.DONE) {
                if (file != null) onOpen() else onRetry()
            },
    ) {
        MediaImage(message, file, Modifier.fillMaxSize(), blurPreview = file == null)
        if (message.mediaKind == MediaType.VIDEO) {
            if (file != null && progress == null) {
                Box(
                    Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
            message.mediaDurationMs?.let { duration ->
                Badge("▶ ${Notifications.duration(duration)}", Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
        }
        TransferState(message, file, progress, onRetry, Modifier.align(Alignment.Center))
        footer?.invoke(this)
    }
}

/** A small dark pill on top of a photo. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.5f), contentColor = Color.White, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Progress ring, a download button, a retry button or "gone", over an attachment. */
@Composable
fun TransferState(message: MessageEntity, file: File?, progress: Float?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val dark = Color.Black.copy(alpha = 0.5f)
    when {
        progress != null -> Box(modifier.size(56.dp).clip(CircleShape).background(dark), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { progress.coerceAtLeast(0.03f) }, color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(42.dp))
        }
        message.transfer == Transfer.UPLOAD || message.transfer == Transfer.DOWNLOAD -> Box(
            modifier.size(56.dp).clip(CircleShape).background(dark),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(42.dp))
        }
        message.transfer == Transfer.ASK -> Pill(Icons.Filled.Download, Files.size(message.mediaSize), onRetry, modifier)
        message.transfer == Transfer.FAILED_UPLOAD || message.transfer == Transfer.FAILED_DOWNLOAD -> Pill(Icons.Filled.Refresh, "Retry", onRetry, modifier)
        message.transfer == Transfer.GONE && file == null && !message.viewOnce -> Pill(Icons.Filled.CloudOff, "No longer available", null, modifier)
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: (() -> Unit)?, modifier: Modifier) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A view-once photo or video: never shown in the chat, only opened full screen,
 * once. Afterwards it says when it was opened.
 */
@Composable
fun ViewOnceContent(message: MessageEntity, file: File?, progress: Float?, partnerName: String, contentColor: Color, onOpen: () -> Unit, onRetry: () -> Unit) {
    val kind = if (message.mediaKind == MediaType.VIDEO) "Video" else "Photo"
    val opened = message.openedAtMs
    val canOpen = !message.fromMe && file != null && opened == null
    val subtitle = when {
        message.fromMe && opened != null && opened > 0 -> "Opened by $partnerName"
        message.fromMe -> "View once · not opened yet"
        opened != null -> "Opened"
        progress != null || message.transfer == Transfer.DOWNLOAD -> "Arriving…"
        message.transfer == Transfer.ASK || message.transfer == Transfer.FAILED_DOWNLOAD -> "Tap to download"
        canOpen -> "Tap to view · it disappears after"
        else -> "No longer available"
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = canOpen || message.transfer == Transfer.ASK || message.transfer == Transfer.FAILED_DOWNLOAD) {
                if (canOpen) onOpen() else onRetry()
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(contentColor.copy(alpha = if (opened != null) 0.12f else 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, color = contentColor, strokeWidth = 2.dp, modifier = Modifier.size(30.dp))
            } else if (opened != null) {
                Icon(Icons.Filled.Visibility, contentDescription = null, tint = contentColor.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
            } else {
                Text("1", color = contentColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
        Column(Modifier.padding(start = 10.dp, end = 6.dp)) {
            Text("$kind · view once", style = MaterialTheme.typography.titleSmall, color = contentColor)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = 0.75f))
        }
    }
}

/** A voice note: play button, the waveform (tap it to jump), the time and a speed toggle. */
@Composable
fun VoiceContent(message: MessageEntity, file: File?, progress: Float?, mine: Boolean, contentColor: Color, onRetry: () -> Unit) {
    val playback by VoicePlayer.state.collectAsStateWithLifecycle()
    val isThis = playback.messageId == message.id
    val playing = isThis && playback.playing
    val duration = message.mediaDurationMs ?: playback.durationMs.takeIf { isThis } ?: 0L
    val played = if (isThis && duration > 0) (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bars = decodeWaveform(message.mediaWaveform)
    Row(Modifier.width(250.dp).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .then(if (mine) Modifier.background(Color.White) else Modifier.background(Mami.colors.brush))
                .clickable {
                    when {
                        file != null -> VoicePlayer.toggle(message.id, file)
                        else -> onRetry()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                progress != null -> CircularProgressIndicator(progress = { progress }, strokeWidth = 2.dp, modifier = Modifier.size(30.dp), color = if (mine) Mami.colors.gradient.first() else Color.White)
                file == null && (message.transfer == Transfer.DOWNLOAD || message.transfer == Transfer.UPLOAD) ->
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(30.dp), color = if (mine) Mami.colors.gradient.first() else Color.White)
                file == null -> Icon(Icons.Filled.Download, contentDescription = "Download", tint = if (mine) Mami.colors.gradient.first() else Color.White)
                else -> Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = if (mine) Mami.colors.gradient.first() else Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Waveform(
                bars = bars,
                played = played,
                color = contentColor,
                modifier = Modifier.fillMaxWidth().height(30.dp),
                onSeek = { fraction -> if (isThis) VoicePlayer.seek(message.id, fraction) else if (file != null) VoicePlayer.toggle(message.id, file) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Notifications.duration(if (isThis && playback.positionMs > 0) playback.positionMs else duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.8f),
                )
                if (isThis) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = VoicePlayer::cycleSpeed,
                        shape = CircleShape,
                        color = contentColor.copy(alpha = 0.18f),
                        contentColor = contentColor,
                    ) {
                        Text(
                            if (playback.speed == 1f) "1×" else "${playback.speed}×",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Loudness bars; the played part is solid, the rest faded. */
@Composable
fun Waveform(bars: ByteArray, played: Float, color: Color, modifier: Modifier = Modifier, onSeek: ((Float) -> Unit)? = null) {
    val values = if (bars.isEmpty()) ByteArray(40) { 40 } else bars
    Canvas(
        modifier.then(
            if (onSeek != null) {
                Modifier.pointerInput(onSeek) { detectTapGestures { offset -> onSeek(offset.x / size.width) } }
            } else {
                Modifier
            },
        ),
    ) {
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(1f)
        values.forEachIndexed { i, raw ->
            val level = (raw.toInt() and 0xFF) / 255f
            val barHeight = (size.height * (0.15f + 0.85f * level)).coerceAtLeast(barWidth)
            val x = i * (barWidth + gap)
            val done = (i + 0.5f) / values.size <= played
            drawRoundRect(
                color = if (done) color else color.copy(alpha = 0.38f),
                topLeft = Offset(x, (size.height - barHeight) / 2),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

/** A document: type badge, name, size; tap to open it in another app. */
@Composable
fun FileContent(message: MessageEntity, file: File?, progress: Float?, contentColor: Color, onOpen: () -> Unit, onRetry: () -> Unit) {
    Row(
        Modifier
            .width(250.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(contentColor.copy(alpha = 0.1f))
            .clickable { if (file != null) onOpen() else onRetry() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 40.dp, height = 48.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(Mami.colors.gradient)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                progress != null -> CircularProgressIndicator(progress = { progress }, color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                file == null && message.transfer != Transfer.GONE -> Icon(Icons.Filled.Download, contentDescription = "Download", tint = Color.White)
                else -> Text(
                    Files.typeLabel(message.mediaName, message.mediaMime).take(4),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                message.mediaName ?: "File",
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(Files.typeLabel(message.mediaName, message.mediaMime), Files.size(message.mediaSize))
                    .filter { it.isNotEmpty() }.joinToString(" · ") +
                    when (message.transfer) {
                        Transfer.GONE -> if (file == null) " · no longer available" else ""
                        Transfer.FAILED_DOWNLOAD, Transfer.FAILED_UPLOAD -> " · tap to retry"
                        Transfer.ASK -> " · tap to download"
                        else -> ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.75f),
            )
        }
    }
}

/** The preview card under a message with a link. */
@Composable
fun LinkPreviewCard(url: String, title: String?, description: String?, image: String?, contentColor: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bitmap = rememberBase64Image(image)
    Column(
        modifier
            .width(250.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(contentColor.copy(alpha = 0.1f))
            .clickable(onClick = onClick),
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(120.dp))
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!title.isNullOrBlank()) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = contentColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                url.substringAfter("://").substringBefore('/').removePrefix("www."),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
    }
}
