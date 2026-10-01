package app.mami.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.core.DeviceStatus
import app.mami.core.NetworkKind
import app.mami.core.NudgeKind
import app.mami.core.RingerMode
import app.mami.data.ConnectionState
import app.mami.data.PresenceDto
import app.mami.data.db.MessageEntity
import app.mami.data.db.MessageKind
import app.mami.ui.Format
import app.mami.ui.Overlay
import app.mami.ui.UiController
import app.mami.ui.components.Avatar
import app.mami.ui.components.HeartBurst
import app.mami.ui.components.SignalBars
import app.mami.ui.components.StatusPill
import app.mami.ui.components.TypingDots
import app.mami.ui.components.batteryColor
import app.mami.ui.components.heartWallpaper
import app.mami.ui.theme.Mami
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Gap after which a new run of bubbles starts. */
private const val GROUP_GAP_MS = 5 * 60_000L

@Composable
fun ChatScreen(ui: UiController) {
    val backend = ui.backend
    val messages by backend.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val partner by backend.partner.collectAsStateWithLifecycle()
    val presence by backend.presence.collectAsStateWithLifecycle()
    val statusPair by backend.partnerStatus.collectAsStateWithLifecycle()
    val typing by backend.partnerTyping.collectAsStateWithLifecycle()
    val secure by backend.secure.collectAsStateWithLifecycle()
    val keyChanged by backend.partnerKeyChanged.collectAsStateWithLifecycle()
    val myShares by backend.shares.collectAsStateWithLifecycle()
    val connection by backend.connection.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val name = partner?.displayName?.ifBlank { null } ?: partner?.email?.substringBefore('@') ?: "Your partner"
    val status = visiblePartnerStatus(statusPair?.first, myShares)
    val hints = partnerHints(status, presence, name, now)

    var details by remember { mutableStateOf<MessageEntity?>(null) }
    var burst by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current

    // While the chat is on screen, everything that arrives counts as read.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, backend) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    backend.chatVisible = true
                    backend.markChatRead()
                }
                Lifecycle.Event.ON_PAUSE -> backend.chatVisible = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            backend.chatVisible = false
        }
    }
    LaunchedEffect(messages.size) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) backend.markChatRead()
    }
    LaunchedEffect(backend) {
        backend.incomingNudges.collect {
            burst++
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatTopBar(
                name = name,
                status = status,
                presence = presence,
                typing = typing,
                hint = hints.firstOrNull(),
                now = now,
                onOpenPartner = { ui.partnerSheetOpen = true },
                onSettings = { ui.overlay = Overlay.Settings },
            )
        },
        bottomBar = {
            Composer(
                onChanged = backend::onComposerChanged,
                onSend = { backend.sendText(it) },
                onNudge = { kind ->
                    backend.sendNudge(kind)
                    burst++
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .heartWallpaper(MaterialTheme.colorScheme.primary.copy(alpha = if (Mami.colors.isDark) 0.06f else 0.045f), Mami.colors.wallpaper),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (connection != ConnectionState.Connected) {
                    Banner(Icons.Filled.CloudOff, if (connection == ConnectionState.Connecting) "Connecting…" else "Offline. Messages wait on your phone and go out by themselves.")
                }
                if (!secure) {
                    Banner(null, "Setting up end-to-end encryption with $name's phone. Messages go out as soon as it's ready.", progress = true)
                }
                if (keyChanged) {
                    Banner(
                        Icons.Filled.Warning,
                        "$name's security code changed, probably a new phone. Compare codes to be sure it's really them.",
                        actions = {
                            TextButton(onClick = backend::dismissKeyChanged) { Text("Dismiss") }
                            TextButton(onClick = { ui.overlay = Overlay.Safety }) { Text("Compare") }
                        },
                    )
                }
                Conversation(
                    messages = messages,
                    partnerName = name,
                    typing = typing,
                    now = now,
                    onDetails = { details = it },
                    modifier = Modifier.weight(1f),
                )
            }
            HeartBurst(burst)
        }
    }

    if (ui.partnerSheetOpen) {
        PartnerSheet(
            name = name,
            email = partner?.email.orEmpty(),
            status = status,
            rawStatus = statusPair?.first,
            statusReceivedAt = statusPair?.second,
            presence = presence,
            hints = hints,
            myShares = myShares,
            now = now,
            onNudge = { kind ->
                backend.sendNudge(kind)
                burst++
            },
            onDismiss = { ui.partnerSheetOpen = false },
        )
    }
    details?.let { message -> MessageDetailsSheet(message, name) { details = null } }
}

@Composable
private fun ChatTopBar(
    name: String,
    status: DeviceStatus?,
    presence: PresenceDto?,
    typing: Boolean,
    hint: Hint?,
    now: Long,
    onOpenPartner: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPartner)
                    .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    name,
                    size = 50.dp,
                    battery = status?.batteryPercent,
                    charging = status?.charging == true,
                    online = presence?.online == true,
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AnimatedContent(typing, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "subtitle") { isTyping ->
                        if (isTyping) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(20.dp)) {
                                TypingDots(color = MaterialTheme.colorScheme.primary, dotSize = 5.dp)
                                Text("  typing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            Text(
                                hint?.text ?: "Tap to see how they're doing",
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    hint?.urgent == true -> MaterialTheme.colorScheme.error
                                    hint?.text == "Online now" -> Mami.colors.good
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.height(20.dp),
                            )
                        }
                    }
                }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            }
            StatusStrip(status, now, onOpenPartner)
        }
    }
}

fun batteryIcon(percent: Int?, charging: Boolean): ImageVector = when {
    percent == null -> Icons.Filled.BatteryFull
    charging -> Icons.Filled.BatteryChargingFull
    percent <= 15 -> Icons.Filled.BatteryAlert
    percent <= 40 -> Icons.Filled.Battery2Bar
    percent <= 70 -> Icons.Filled.Battery4Bar
    percent <= 90 -> Icons.Filled.Battery6Bar
    else -> Icons.Filled.BatteryFull
}

/** Everything about the partner's phone at a glance, in one swipeable row. */
@Composable
private fun StatusStrip(status: DeviceStatus?, now: Long, onClick: () -> Unit) {
    if (status == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        status.quickStatus?.takeIf { it.untilMs == null || it.untilMs!! > now }?.let { quick ->
            StatusPill(null, "${quick.emoji} ${quick.label}", tint = MaterialTheme.colorScheme.secondary, onClick = onClick)
        }
        status.batteryPercent?.let { percent ->
            val charging = status.charging == true
            StatusPill(
                batteryIcon(percent, charging),
                if (charging) "$percent% · charging" else "$percent%",
                tint = batteryColor(percent, charging),
                onClick = onClick,
            )
        }
        status.network?.let { network ->
            val (icon, label) = when (network) {
                NetworkKind.WIFI -> Icons.Filled.Wifi to "Wi-Fi"
                NetworkKind.CELLULAR -> Icons.Filled.SignalCellularAlt to "Mobile data"
                NetworkKind.ETHERNET -> Icons.Filled.Wifi to "Cable"
                NetworkKind.OFFLINE -> Icons.Filled.WifiOff to "Offline"
                NetworkKind.OTHER -> Icons.Filled.Wifi to "Online"
            }
            StatusPill(
                icon,
                label,
                tint = if (network == NetworkKind.OFFLINE) Mami.colors.bad else MaterialTheme.colorScheme.primary,
                trailing = status.signalLevel?.let { level -> @Composable { SignalBars(level) } },
                onClick = onClick,
            )
        }
        if (status.doNotDisturb == true || status.ringer != null) {
            val (icon, label) = when {
                status.doNotDisturb == true -> Icons.Filled.DoNotDisturbOn to "Do Not Disturb"
                status.ringer == RingerMode.SILENT -> Icons.Filled.NotificationsOff to "Silent"
                status.ringer == RingerMode.VIBRATE -> Icons.Filled.Vibration to "Vibrate"
                else -> Icons.AutoMirrored.Filled.VolumeUp to "Ringer on"
            }
            val quiet = status.doNotDisturb == true || status.ringer == RingerMode.SILENT || status.ringer == RingerMode.VIBRATE
            StatusPill(icon, label, tint = if (quiet) Mami.colors.warn else MaterialTheme.colorScheme.primary, onClick = onClick)
        }
        partnerLocalTime(status, now)?.let { time ->
            StatusPill(Icons.Filled.Schedule, "$time there", tint = MaterialTheme.colorScheme.tertiary, onClick = onClick)
        }
    }
}

@Composable
private fun Banner(
    icon: ImageVector?,
    text: String,
    progress: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (progress) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                } else if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp))
            }
            if (actions != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
            }
        }
    }
}

@Composable
private fun Conversation(
    messages: List<MessageEntity>,
    partnerName: String,
    typing: Boolean,
    now: Long,
    onDetails: (MessageEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val newestFirst = remember(messages) { messages.asReversed() }
    val newestMine = remember(messages) { newestFirst.firstOrNull { it.fromMe && it.kind == MessageKind.TEXT }?.id }
    LaunchedEffect(messages.size, typing) {
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }
    val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }

    Box(modifier.fillMaxSize()) {
        if (messages.isEmpty() && !typing) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("💌", fontSize = 64.sp)
                Spacer(Modifier.height(12.dp))
                Text("Say hi to $partnerName", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Everything here is end-to-end encrypted.\nTap ❤️ to send a little \"thinking of you\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (typing) {
                item(key = "typing") { TypingBubble(Modifier.animateItem().padding(top = 8.dp)) }
            }
            itemsIndexed(newestFirst, key = { _, m -> m.id }) { index, message ->
                val older = newestFirst.getOrNull(index + 1)
                val newer = newestFirst.getOrNull(index - 1)
                val first = older == null || !sameRun(older, message)
                val last = newer == null || !sameRun(message, newer)
                Column(Modifier.animateItem().padding(top = if (first) 10.dp else 2.dp)) {
                    if (older == null || !Format.sameDay(older.sortAtMs, message.sortAtMs)) {
                        DaySeparator(Format.day(message.sortAtMs, now))
                    }
                    when (message.kind) {
                        MessageKind.TEXT -> TextBubble(message, first, last) { onDetails(message) }
                        MessageKind.NUDGE -> NudgeSticker(message, partnerName) { onDetails(message) }
                        else -> BatteryAlertCard(message, partnerName) { onDetails(message) }
                    }
                    if (message.id == newestMine) ReceiptLine(message, partnerName)
                }
            }
        }
        AnimatedVisibility(
            visible = showJump,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Latest messages")
            }
        }
    }
}

private fun sameRun(a: MessageEntity, b: MessageEntity) =
    a.fromMe == b.fromMe && a.kind == MessageKind.TEXT && b.kind == MessageKind.TEXT && kotlin.math.abs(b.sortAtMs - a.sortAtMs) < GROUP_GAP_MS

private val nudges = listOf(
    NudgeKind.THINKING_OF_YOU to "💗  Thinking of you",
    NudgeKind.HUG to "🤗  Hug",
    NudgeKind.KISS to "😘  Kiss",
    NudgeKind.MISS_YOU to "🥺  Miss you",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Composer(onChanged: (String) -> Unit, onSend: (String) -> Unit, onNudge: (NudgeKind) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                Box(
                    Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = { onNudge(NudgeKind.THINKING_OF_YOU) },
                            onLongClick = { menu = true },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Favorite, contentDescription = "Send a heart (hold for more)", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    nudges.forEach { (kind, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            menu = false
                            onNudge(kind)
                        })
                    }
                }
            }
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    onChanged(it)
                },
                placeholder = { Text("Say something sweet…") },
                maxLines = 5,
                shape = RoundedCornerShape(26.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(visible = text.isNotBlank(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                Box(
                    Modifier
                        .padding(start = 6.dp)
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Mami.colors.brush)
                        .clickable {
                            onSend(text)
                            text = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                }
            }
        }
    }
}
