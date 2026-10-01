package app.mami.ui.chat

import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.mami.data.db.MediaType
import app.mami.data.db.MessageEntity
import app.mami.sync.MamiBackend
import app.mami.sync.Notifications
import app.mami.ui.Files
import app.mami.ui.Format
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Photos and videos that can be shown full screen. A view-once item is only ever shown on its own. */
fun viewable(messages: List<MessageEntity>, backend: MamiBackend, startId: String): List<MessageEntity> {
    val start = messages.firstOrNull { it.id == startId }
    if (start?.viewOnce == true) return listOf(start)
    return messages.filter {
        it.isMedia && !it.unsent && !it.viewOnce &&
            (it.mediaKind == MediaType.PHOTO || it.mediaKind == MediaType.VIDEO) &&
            backend.fileFor(it) != null
    }
}

/**
 * Full-screen photos and videos: swipe between them, pinch or double-tap to
 * zoom. A view-once photo blocks screenshots and is gone when closed.
 */
@Composable
fun MediaViewer(
    backend: MamiBackend,
    items: List<MessageEntity>,
    startId: String,
    partnerName: String,
    onShowInChat: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val viewOnce = items.size == 1 && items[0].viewOnce
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = items.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    val current = items.getOrNull(pager.currentPage) ?: items.first()

    if (viewOnce) {
        DisposableEffect(current.id) { onDispose { backend.viewOnceOpened(current.id) } }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = if (viewOnce) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
        ),
    ) {
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, key = { items[it].id }, modifier = Modifier.fillMaxSize()) { page ->
                val message = items[page]
                val file = backend.fileFor(message)
                when {
                    file == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("This is no longer on your phone.", color = Color.White)
                    }
                    message.mediaKind == MediaType.VIDEO -> VideoPlayer(file, active = page == pager.currentPage, chrome = chrome, onTap = { chrome = !chrome })
                    else -> ZoomableImage(file, onTap = { chrome = !chrome }, onZoom = { if (page == pager.currentPage) zoomed = it })
                }
            }
            AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(if (current.fromMe) "You" else partnerName, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(Format.preciseDateTime(current.sentAtMs), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                    }
                    if (viewOnce) {
                        Text("View once · screenshots off", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 12.dp))
                    } else {
                        IconButton(onClick = { backend.setStarred(current.id, !current.starred) }) {
                            Icon(if (current.starred) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = "Star", tint = Color.White)
                        }
                        IconButton(onClick = { backend.fileFor(current)?.let { Files.share(context, it, current.mediaMime) } }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                val saved = backend.saveToDevice(current)
                                Toast.makeText(context, if (saved) "Saved to your gallery" else "Couldn't save it", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Filled.Download, contentDescription = "Save to phone", tint = Color.White)
                        }
                        IconButton(onClick = { onShowInChat(current.id) }) {
                            Icon(Icons.Filled.Forum, contentDescription = "Show in chat", tint = Color.White)
                        }
                    }
                }
            }
            val caption = current.body
            AnimatedVisibility(visible = chrome && caption.isNotBlank(), enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Text(
                    caption,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = if (current.mediaKind == MediaType.VIDEO) 72.dp else 20.dp),
                )
            }
        }
    }
}

/** Pinch to zoom, drag when zoomed, double-tap to zoom in or out. One finger at normal size swipes pages. */
@Composable
private fun ZoomableImage(file: File, onTap: () -> Unit, onZoom: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    fun set(newScale: Float, newOffset: Offset) {
        scale = newScale.coerceIn(1f, 5f)
        offset = if (scale > 1f) newOffset else Offset.Zero
        onZoom(scale > 1f)
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(file) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            set(1f, Offset.Zero)
                        } else {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            set(2.5f, (center - tap) * 1.5f)
                        }
                    },
                )
            }
            .pointerInput(file) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1f) {
                            set(scale * event.calculateZoom(), offset + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = file,
            contentDescription = "Photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/** The platform video view with MaMi's own play button and seek bar. */
@Composable
private fun VideoPlayer(file: File, active: Boolean, chrome: Boolean, onTap: () -> Unit) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    LaunchedEffect(active, view) {
        val video = view ?: return@LaunchedEffect
        if (!active && video.isPlaying) {
            video.pause()
            playing = false
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            position = view?.currentPosition?.toLong() ?: 0L
            delay(200)
        }
    }
    DisposableEffect(file) { onDispose { view?.stopPlayback() } }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setVideoPath(file.path)
                    setOnPreparedListener { player ->
                        duration = player.duration.toLong()
                        if (active) {
                            start()
                            playing = true
                        }
                    }
                    setOnCompletionListener {
                        playing = false
                        position = duration
                    }
                    view = this
                }
            },
        )
        AnimatedVisibility(visible = chrome || !playing, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable {
                        val video = view ?: return@clickable
                        if (video.isPlaying) {
                            video.pause()
                            playing = false
                        } else {
                            if (position >= duration && duration > 0) video.seekTo(0)
                            video.start()
                            playing = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }
        AnimatedVisibility(visible = chrome && duration > 0, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Notifications.duration(position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { fraction ->
                        position = (duration * fraction).toLong()
                        view?.seekTo(position.toInt())
                    },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Text(Notifications.duration(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}
