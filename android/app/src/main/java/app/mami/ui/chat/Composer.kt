package app.mami.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.mami.core.CheckInKind
import app.mami.core.LinkPreview
import app.mami.core.NudgeKind
import app.mami.core.extractLinks
import app.mami.data.db.MessageEntity
import app.mami.media.VoiceRecorder
import app.mami.sync.Notifications
import app.mami.ui.Files
import app.mami.ui.UiController
import app.mami.ui.Overlay
import app.mami.ui.theme.Mami
import app.mami.ui.together.ScheduleSheet
import app.mami.ui.together.whenIn
import java.io.File
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What's being written: the text, and whether it's a reply, an edit or has a link preview. */
@Stable
class ComposerState {
    var text by mutableStateOf("")
    var replyTo by mutableStateOf<MessageEntity?>(null)
    var editing by mutableStateOf<MessageEntity?>(null)
    var preview by mutableStateOf<LinkPreview?>(null)
    var dismissedLink by mutableStateOf<String?>(null)

    fun reply(message: MessageEntity) {
        editing = null
        replyTo = message
    }

    fun edit(message: MessageEntity) {
        replyTo = null
        editing = message
        text = message.body
        preview = null
    }

    fun clear() {
        text = ""
        replyTo = null
        editing = null
        preview = null
        dismissedLink = null
    }
}

private val nudges = listOf(
    NudgeKind.THINKING_OF_YOU to "💗  Thinking of you",
    NudgeKind.HUG to "🤗  Hug",
    NudgeKind.KISS to "😘  Kiss",
    NudgeKind.MISS_YOU to "🥺  Miss you",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Composer(ui: UiController, state: ComposerState, partnerName: String, partnerZone: ZoneId?, onNudge: (NudgeKind) -> Unit) {
    val backend = ui.backend
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var nudgeMenu by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var scheduling by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var captureFile by remember { mutableStateOf<File?>(null) }
    var captureVideo by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(context.applicationContext, scope) }
    var recording by remember { mutableStateOf(false) }
    var slide by remember { mutableFloatStateOf(0f) }

    // ---- pickers ----
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) picked = uris
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ui.run({ backend.sendMedia(listOf(uri), null, viewOnce = false, asDocument = true, replyTo = state.replyTo?.id) }) { state.replyTo = null }
        }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = captureFile
        if (ok && file != null) picked = listOf(Files.uri(context, file))
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val file = captureFile
        if (ok && file != null) picked = listOf(Files.uri(context, file))
    }
    fun launchCamera(video: Boolean) {
        val file = File(File(context.cacheDir, "capture").apply { mkdirs() }, "${UUID.randomUUID()}.${if (video) "mp4" else "jpg"}")
        captureFile = file
        val uri = Files.uri(context, file)
        if (video) takeVideo.launch(uri) else takePhoto.launch(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera(captureVideo) else Toast.makeText(context, "MaMi needs the camera for that.", Toast.LENGTH_SHORT).show()
    }
    fun camera(video: Boolean) {
        captureVideo = video
        if (granted(context, Manifest.permission.CAMERA)) launchCamera(video) else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(context, if (granted) "Hold the mic to record" else "MaMi needs the microphone for voice messages.", Toast.LENGTH_SHORT).show()
    }

    // ---- link preview while typing ----
    LaunchedEffect(state.text, state.editing) {
        if (state.editing != null) return@LaunchedEffect
        delay(650)
        val link = extractLinks(state.text).firstOrNull()
        when {
            link == null -> state.preview = null
            link == state.dismissedLink || link == state.preview?.url -> Unit
            else -> state.preview = backend.linkPreview(link)
        }
    }

    fun send(deliverAt: Long? = null) {
        val text = state.text.trim()
        val editing = state.editing
        when {
            editing != null -> if (text.isNotEmpty() && text != editing.body) backend.edit(editing.id, text)
            text.isNotEmpty() -> {
                val link = state.preview?.takeIf { p -> text.contains(p.url) }
                backend.sendText(text, state.replyTo?.id, link, deliverAt)
            }
        }
        state.clear()
        backend.onComposerChanged("")
    }

    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
            AnimatedVisibility(visible = state.replyTo != null || state.editing != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                val target = state.editing ?: state.replyTo
                if (target != null) {
                    ContextBar(
                        icon = if (state.editing != null) Icons.Filled.Edit else Icons.AutoMirrored.Filled.Reply,
                        title = when {
                            state.editing != null -> "Editing"
                            target.fromMe -> "Replying to yourself"
                            else -> "Replying to $partnerName"
                        },
                        text = snippet(target, partnerName),
                        onClose = {
                            if (state.editing != null) state.text = ""
                            state.replyTo = null
                            state.editing = null
                        },
                    )
                }
            }
            AnimatedVisibility(visible = state.preview != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                state.preview?.let { p ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LinkPreviewCard(p.url, p.title, p.description, p.image, MaterialTheme.colorScheme.onSurface, onClick = {}, modifier = Modifier.weight(1f, fill = false))
                        IconButton(onClick = {
                            state.dismissedLink = p.url
                            state.preview = null
                        }) { Icon(Icons.Filled.Close, contentDescription = "Remove preview") }
                    }
                }
            }
            // The mic button stays put while recording, so the finger holding it keeps control.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    if (recording) {
                        RecordingBar(recorder, slide)
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Box {
                                Box(
                                    Modifier
                                        .size(50.dp)
                                        .clip(CircleShape)
                                        .combinedClickable(onClick = { onNudge(NudgeKind.THINKING_OF_YOU) }, onLongClick = { nudgeMenu = true }),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Filled.Favorite, contentDescription = "Send a heart (hold for more)", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                                }
                                DropdownMenu(expanded = nudgeMenu, onDismissRequest = { nudgeMenu = false }) {
                                    nudges.forEach { (kind, label) ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                            nudgeMenu = false
                                            onNudge(kind)
                                        })
                                    }
                                }
                            }
                            val attachIcons: (@Composable () -> Unit)? = if (state.text.isEmpty() && state.editing == null) {
                                @Composable {
                                    Row {
                                        IconButton(onClick = { attachMenu = true }) { Icon(Icons.Filled.AttachFile, contentDescription = "Attach") }
                                        IconButton(onClick = { camera(video = false) }) { Icon(Icons.Filled.PhotoCamera, contentDescription = "Camera") }
                                    }
                                }
                            } else if (state.editing == null) {
                                @Composable {
                                    IconButton(onClick = { scheduling = true }) {
                                        Icon(Icons.Filled.Schedule, contentDescription = "Schedule", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            } else {
                                null
                            }
                            TextField(
                                value = state.text,
                                onValueChange = {
                                    state.text = it
                                    backend.onComposerChanged(it)
                                },
                                placeholder = { Text(if (state.editing != null) "Edit your message" else "Say something sweet…") },
                                maxLines = 5,
                                shape = RoundedCornerShape(26.dp),
                                trailingIcon = attachIcons,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                val canSend = !recording && (state.text.isNotBlank() || state.editing != null)
                AnimatedContent(canSend, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "send") { sendable ->
                    if (sendable) {
                        Box(
                            Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Mami.colors.brush)
                                .combinedClickable(
                                    onClick = { send() },
                                    onLongClick = if (state.editing == null) {
                                        {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            scheduling = true
                                        }
                                    } else {
                                        null
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (state.editing != null) "Save" else "Send", tint = Color.White)
                        }
                    } else {
                        MicButton(
                            hasPermission = { granted(context, Manifest.permission.RECORD_AUDIO) },
                            askPermission = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                            onStart = {
                                val file = backend.newVoiceFile()
                                if (file != null && recorder.start(file)) {
                                    recording = true
                                    slide = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                } else if (file == null) {
                                    Toast.makeText(context, "Voice messages need a real phone.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onSlide = { slide = it },
                            onRelease = { cancelled ->
                                if (recording) {
                                    recording = false
                                    if (cancelled) {
                                        recorder.cancel()
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } else {
                                        val voice = recorder.stop()
                                        if (voice != null) {
                                            backend.sendVoice(voice, state.replyTo?.id)
                                            state.replyTo = null
                                        } else {
                                            Toast.makeText(context, "Hold the mic to record", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (attachMenu) {
        AttachSheet(
            onDismiss = { attachMenu = false },
            onGallery = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onCamera = { camera(video = false) },
            onVideo = { camera(video = true) },
            onDocument = { pickDocument.launch(arrayOf("*/*")) },
            onLetter = { ui.writingLetter = true },
            onLocation = { ui.locationOpen = true },
            onHomeSafe = {
                backend.checkIn(CheckInKind.HOME_SAFE)
                Toast.makeText(context, "Told $partnerName you're home safe", Toast.LENGTH_SHORT).show()
            },
            onSchedule = { ui.overlay = Overlay.Together },
        )
    }
    if (scheduling) {
        val now = System.currentTimeMillis()
        ScheduleSheet(
            text = state.text.trim(),
            partnerName = partnerName,
            partnerZone = partnerZone,
            now = now,
            onDismiss = { scheduling = false },
            onSchedule = { at ->
                scheduling = false
                send(deliverAt = at)
                Toast.makeText(context, "Scheduled for ${whenIn(at, partnerZone ?: ZoneId.systemDefault(), now, capitalize = false)}" + if (partnerZone != null) " their time" else "", Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (picked.isNotEmpty()) {
        SendMediaSheet(
            uris = picked,
            partnerName = partnerName,
            initialCaption = state.text,
            onDismiss = { picked = emptyList() },
            onSend = { caption, viewOnce ->
                val uris = picked
                val replyTo = state.replyTo?.id
                picked = emptyList()
                state.clear()
                ui.run({ backend.sendMedia(uris, caption.ifBlank { null }, viewOnce, asDocument = false, replyTo = replyTo) })
            },
        )
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Hold to record, slide left to cancel, let go to send. */
@Composable
private fun MicButton(
    hasPermission: () -> Boolean,
    askPermission: () -> Unit,
    onStart: () -> Unit,
    onSlide: (Float) -> Unit,
    onRelease: (cancelled: Boolean) -> Unit,
) {
    val cancelAt = with(LocalDensity.current) { 120.dp.toPx() }
    val start by rememberUpdatedState(onStart)
    val slide by rememberUpdatedState(onSlide)
    val release by rememberUpdatedState(onRelease)
    val allowed by rememberUpdatedState(hasPermission)
    val ask by rememberUpdatedState(askPermission)
    Box(
        Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(Mami.colors.brush)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!allowed()) {
                        ask()
                        return@awaitEachGesture
                    }
                    start()
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dx = (down.position.x - change.position.x).coerceAtLeast(0f)
                        slide(dx / cancelAt)
                        if (dx > cancelAt) cancelled = true
                        if (!change.pressed) break
                        change.consume()
                    }
                    release(cancelled)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Mic, contentDescription = "Hold to record a voice message", tint = Color.White)
    }
}

/** Shown instead of the text field while recording: a pulsing dot, the time, the level, and the cancel hint. */
@Composable
private fun RecordingBar(recorder: VoiceRecorder, slide: Float) {
    val elapsed by recorder.elapsedMs.collectAsState()
    val level by recorder.level.collectAsState()
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
    Row(
        Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).graphicsLayer { alpha = pulse }.clip(CircleShape).background(Mami.colors.bad))
        Spacer(Modifier.width(10.dp))
        Text(Notifications.duration(elapsed), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .height(6.dp)
                .width((12 + level * 60).dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.graphicsLayer {
                translationX = -slide.coerceIn(0f, 1f) * 80.dp.toPx()
                alpha = 1f - slide.coerceIn(0f, 1f) * 0.6f
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (slide >= 1f) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = Mami.colors.bad)
                Text(" Let go to cancel", color = Mami.colors.bad, style = MaterialTheme.typography.labelLarge)
            } else {
                Text("‹ Slide to cancel", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ContextBar(icon: ImageVector, title: String, text: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(40.dp).clip(CircleShape).background(Mami.colors.brush))
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 10.dp).size(20.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(
    onDismiss: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onVideo: () -> Unit,
    onDocument: () -> Unit,
    onLetter: () -> Unit,
    onLocation: () -> Unit,
    onHomeSafe: () -> Unit,
    onSchedule: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                AttachOption(Icons.Filled.PhotoLibrary, "Gallery") { onDismiss(); onGallery() }
                AttachOption(Icons.Filled.PhotoCamera, "Camera") { onDismiss(); onCamera() }
                AttachOption(Icons.Filled.Videocam, "Video") { onDismiss(); onVideo() }
                AttachOption(Icons.AutoMirrored.Filled.InsertDriveFile, "Document") { onDismiss(); onDocument() }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                AttachOption(Icons.Filled.MailOutline, "Letter") { onDismiss(); onLetter() }
                AttachOption(Icons.Filled.LocationOn, "Location") { onDismiss(); onLocation() }
                AttachOption(Icons.Filled.Home, "Home safe") { onDismiss(); onHomeSafe() }
                AttachOption(Icons.Filled.Schedule, "Scheduled") { onDismiss(); onSchedule() }
            }
        }
    }
}

@Composable
private fun AttachOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.width(82.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(Mami.colors.brush), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Look before sending: the picked photos and videos, a caption, and "view once". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendMediaSheet(uris: List<Uri>, partnerName: String, initialCaption: String, onDismiss: () -> Unit, onSend: (String, Boolean) -> Unit) {
    var caption by remember { mutableStateOf(initialCaption) }
    var viewOnce by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                if (uris.size == 1) "Send to $partnerName" else "Send ${uris.size} to $partnerName",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            ) {
                items(uris) { uri -> UriPreview(uri, Modifier.height(if (uris.size == 1) 300.dp else 180.dp).aspectRatio(0.8f).clip(RoundedCornerShape(16.dp))) }
            }
            Spacer(Modifier.height(12.dp))
            TextField(
                value = caption,
                onValueChange = { caption = it },
                placeholder = { Text("Add a caption…") },
                maxLines = 3,
                shape = RoundedCornerShape(20.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            if (uris.size == 1) {
                Row(
                    Modifier.fillMaxWidth().clickable { viewOnce = !viewOnce }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Text("1", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("View once", style = MaterialTheme.typography.titleSmall)
                        Text("$partnerName can open it one time, then it's gone. Screenshots are blocked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = viewOnce, onCheckedChange = { viewOnce = it })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Mami.colors.brush).clickable { onSend(caption, viewOnce && uris.size == 1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                }
            }
        }
    }
}

/** A thumbnail of a picked photo or video, loaded off the main thread. */
@Composable
private fun UriPreview(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { loadThumbnail(context, uri)?.asImageBitmap() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        }
        if (context.contentResolver.getType(uri)?.startsWith("video/") == true) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Videocam, contentDescription = "Video", tint = Color.White)
            }
        }
    }
}

private fun loadThumbnail(context: Context, uri: Uri): Bitmap? = runCatching {
    val resolver = context.contentResolver
    val type = resolver.getType(uri).orEmpty()
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority != app.mami.media.MediaLibrary.authority(context) ->
            resolver.loadThumbnail(uri, Size(720, 720), null)
        type.startsWith("video/") -> MediaMetadataRetriever().run {
            try {
                setDataSource(context, uri)
                getFrameAtTime(0)?.let { frame ->
                    val scale = 720f / maxOf(frame.width, frame.height)
                    if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt(), (frame.height * scale).roundToInt(), true) else frame
                }
            } finally {
                release()
            }
        }
        else -> app.mami.media.MediaLibrary(context).decodeBounded(uri, 720)
    }
}.getOrNull()
