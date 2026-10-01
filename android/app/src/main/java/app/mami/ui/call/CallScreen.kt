package app.mami.ui.call

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.calls.AudioRoute
import app.mami.calls.CallIntents
import app.mami.calls.CallPhase
import app.mami.calls.CallState
import app.mami.calls.Calls
import app.mami.core.NetworkKind
import app.mami.sync.Notifications
import app.mami.ui.UiController
import app.mami.ui.chat.visiblePartnerStatus
import app.mami.ui.components.Avatar
import app.mami.ui.components.FloatingHearts
import app.mami.ui.theme.Mami
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Whether a video call is on screen; MainActivity uses it to go picture-in-picture. */
object CallUi {
    @Volatile
    var videoCallOnScreen = false
}

/** Starts a call after asking for the microphone (and camera, for video). */
@Composable
fun rememberCallStarter(calls: Calls): (Boolean) -> Unit {
    val context = LocalContext.current
    var wantVideo by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            calls.start(wantVideo)
        } else {
            Toast.makeText(context, if (wantVideo) "Video calls need the microphone and camera." else "Calls need the microphone.", Toast.LENGTH_LONG).show()
        }
    }
    return { video ->
        wantVideo = video
        val needed = callPermissions(video).filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) calls.start(video) else launcher.launch(needed.toTypedArray())
    }
}

private fun callPermissions(video: Boolean) =
    if (video) listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else listOf(Manifest.permission.RECORD_AUDIO)

/** Shown over everything while there's a call. */
@Composable
fun CallOverlay(ui: UiController) {
    val calls = ui.backend.calls
    val call by calls.call.collectAsStateWithLifecycle()
    val current = call ?: return
    CallScreen(ui, calls, current)
}

@Composable
fun CallScreen(ui: UiController, calls: Calls, call: CallState) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val partner by ui.backend.partner.collectAsStateWithLifecycle()
    val statusPair by ui.backend.partnerStatus.collectAsStateWithLifecycle()
    val myShares by ui.backend.shares.collectAsStateWithLifecycle()
    val answerRequested by CallIntents.answerRequested.collectAsStateWithLifecycle()
    val name = partner?.displayName?.ifBlank { null } ?: "Your partner"
    val status = visiblePartnerStatus(statusPair?.first, myShares)
    var controls by remember { mutableStateOf(true) }
    val inPip = rememberInPictureInPicture(activity)
    val showVideo = call.video && calls.hasVideo
    val active = call.phase == CallPhase.Connected || call.phase == CallPhase.Reconnecting || call.phase == CallPhase.Connecting

    val answer = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) calls.accept() else Toast.makeText(context, "Answering needs the microphone${if (call.video) " and camera" else ""}.", Toast.LENGTH_LONG).show()
    }
    fun accept() {
        val needed = callPermissions(call.video).filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) calls.accept() else answer.launch(needed.toTypedArray())
    }
    LaunchedEffect(answerRequested, call.phase) {
        if (answerRequested && call.phase == CallPhase.Incoming) {
            CallIntents.answerRequested.value = false
            accept()
        }
    }

    // Show over the lock screen, turn the screen on for an incoming call, keep it on during the call.
    DisposableEffect(activity) {
        activity?.showOverLockScreen(true)
        onDispose { activity?.showOverLockScreen(false) }
    }
    DisposableEffect(showVideo, call.phase) {
        CallUi.videoCallOnScreen = showVideo && active
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activity != null) {
            runCatching {
                activity.setPictureInPictureParams(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).setAutoEnterEnabled(showVideo && active).build(),
                )
            }
        }
        onDispose { CallUi.videoCallOnScreen = false }
    }
    BackHandler(enabled = call.phase != CallPhase.Ended) {
        // Back doesn't end a call; it just goes back to the app if there's a video, like other call apps.
        if (showVideo && active && activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build()) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1030), Mami.colors.gradient.last().copy(alpha = 0.85f), Color(0xFF120A1F))))
            .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }) },
    ) {
        val partnerVideo = showVideo && active && call.partnerCameraOn
        val ringing = call.phase == CallPhase.Incoming || call.phase == CallPhase.Ringing || call.phase == CallPhase.Calling
        if (partnerVideo) {
            VideoSurface(calls, remote = true, Modifier.fillMaxSize())
        } else if (ringing) {
            FloatingHearts()
        }
        if (inPip) {
            if (!partnerVideo) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Avatar(name, size = 64.dp) }
            }
            return@Box
        }

        // Who and what's happening, at the top.
        AnimatedVisibility(visible = controls || !partnerVideo, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (partnerVideo) Modifier.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))) else Modifier)
                    .statusBarsPadding()
                    .padding(top = 18.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
                    Text(" End-to-end encrypted", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                }
                if (partnerVideo) {
                    Spacer(Modifier.height(6.dp))
                } else {
                    Spacer(Modifier.height(64.dp))
                    Pulse(enabled = ringing) {
                        Avatar(name, size = 132.dp, battery = status?.batteryPercent, charging = status?.charging == true)
                    }
                    Spacer(Modifier.height(28.dp))
                }
                Text(name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                AnimatedContent(phaseText(call, name), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "phase") { text ->
                    Text(text, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
                partnerConnection(status)?.let { line ->
                    Spacer(Modifier.height(8.dp))
                    Text(line, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                if (call.video && active && !call.partnerCameraOn) {
                    Spacer(Modifier.height(6.dp))
                    Text("$name's camera is off", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                }
                if (call.partnerMuted && active) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.MicOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text(" $name is muted", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // My camera, small and draggable.
        if (showVideo && call.cameraOn && call.phase != CallPhase.Ended) {
            var moved by remember { mutableStateOf(Offset.Zero) }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 64.dp, end = 16.dp)
                    .offset { IntOffset(moved.x.roundToInt(), moved.y.roundToInt()) }
                    .size(width = 108.dp, height = 172.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            moved += drag
                        }
                    },
            ) {
                VideoSurface(calls, remote = false, Modifier.fillMaxSize())
            }
        }

        // Controls at the bottom.
        AnimatedVisibility(visible = controls || !partnerVideo, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 36.dp)) {
                when (call.phase) {
                    CallPhase.Incoming -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundButton(Icons.Filled.CallEnd, "Decline", Mami.colors.bad, size = 72.dp) { calls.decline() }
                        Pulse(enabled = true) {
                            RoundButton(if (call.video) Icons.Filled.Videocam else Icons.Filled.Call, "Answer", Mami.colors.good, size = 72.dp) { accept() }
                        }
                    }
                    CallPhase.Ended -> Unit
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            ToggleButton(if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (call.muted) "Unmute" else "Mute", call.muted) { calls.setMuted(!call.muted) }
                            RouteButton(call, calls)
                            if (call.video) {
                                ToggleButton(if (call.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, if (call.cameraOn) "Camera off" else "Camera on", !call.cameraOn) { calls.setCameraOn(!call.cameraOn) }
                                ToggleButton(Icons.Filled.Cameraswitch, "Flip", false) { calls.switchCamera() }
                            }
                        }
                        Spacer(Modifier.height(26.dp))
                        RoundButton(Icons.Filled.CallEnd, "End", Mami.colors.bad, size = 72.dp) { calls.hangUp() }
                    }
                }
            }
        }
    }
}

/** "Calling…", "Ringing on Maya's phone", "12:04", "Reconnecting…" and so on. */
@Composable
private fun phaseText(call: CallState, name: String): String {
    val now by produceState(System.currentTimeMillis(), call.connectedAtMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(500)
        }
    }
    val kind = if (call.video) "video call" else "voice call"
    return when (call.phase) {
        CallPhase.Calling -> "Calling…"
        CallPhase.Ringing -> "Ringing on $name's phone"
        CallPhase.Incoming -> "Incoming $kind"
        CallPhase.Connecting -> "Connecting…"
        CallPhase.Connected -> call.connectedAtMs?.let { Notifications.duration(now - it) } ?: "Connected"
        CallPhase.Reconnecting -> "Reconnecting…"
        CallPhase.Ended -> call.endReason ?: "Call ended"
    }
}

/**
 * Their connection, for "why is it not connecting?": Wi-Fi or mobile data and
 * how strong the signal is. (Their battery is already the ring round the picture.)
 */
private fun partnerConnection(status: app.mami.core.DeviceStatus?): String? {
    if (status == null) return null
    val network = when (status.network) {
        NetworkKind.WIFI -> "Wi-Fi"
        NetworkKind.CELLULAR -> "Mobile data"
        NetworkKind.ETHERNET -> "Cable"
        NetworkKind.OFFLINE -> "Offline"
        else -> null
    }
    val signal = when (status.signalLevel) {
        null -> null
        0, 1 -> "weak signal"
        2 -> "okay signal"
        3 -> "good signal"
        else -> "great signal"
    }
    val parts = listOfNotNull(network, signal.takeIf { network != "Offline" }) + listOfNotNull("Do Not Disturb".takeIf { status.doNotDisturb == true })
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun Pulse(enabled: Boolean, content: @Composable () -> Unit) {
    val scale by rememberInfiniteTransition(label = "pulse").animateFloat(1f, if (enabled) 1.08f else 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "scale")
    Box(Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }) { content() }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, color: Color, size: androidx.compose.ui.unit.Dp = 60.dp, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(CircleShape).background(color).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(size * 0.45f))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ToggleButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(
            Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(if (on) Color.White else Color.White.copy(alpha = 0.18f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (on) Color(0xFF1B1030) else Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** Speaker on/off, or a choice when Bluetooth or headphones are connected. */
@Composable
private fun RouteButton(call: CallState, calls: Calls) {
    var menu by remember { mutableStateOf(false) }
    val icon = when (call.route) {
        AudioRoute.Speaker -> Icons.AutoMirrored.Filled.VolumeUp
        AudioRoute.Bluetooth -> Icons.Filled.Bluetooth
        AudioRoute.Headset -> Icons.Filled.Headphones
        AudioRoute.Earpiece -> Icons.Filled.PhoneInTalk
    }
    Box {
        ToggleButton(icon, call.route.label, call.route != AudioRoute.Earpiece && call.route != AudioRoute.Headset) {
            if (call.routes.size > 2) {
                menu = true
            } else {
                calls.setRoute(if (call.route == AudioRoute.Speaker) call.routes.first() else AudioRoute.Speaker)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            call.routes.forEach { route ->
                DropdownMenuItem(text = { Text(route.label + if (route == call.route) "  ✓" else "") }, onClick = {
                    menu = false
                    calls.setRoute(route)
                })
            }
        }
    }
}

/** A camera picture from the call engine (nothing in the demo). */
@Composable
private fun VideoSurface(calls: Calls, remote: Boolean, modifier: Modifier) {
    if (!calls.hasVideo) return
    AndroidView(
        factory = { context -> calls.createVideoView(context, remote) ?: android.view.View(context) },
        onRelease = { calls.releaseVideoView(it) },
        modifier = modifier,
    )
}

@Composable
private fun rememberInPictureInPicture(activity: ComponentActivity?): Boolean {
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

private fun Activity.showOverLockScreen(on: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(on)
        setTurnScreenOn(on)
    } else {
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        if (on) window.addFlags(flags) else window.clearFlags(flags)
    }
    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
