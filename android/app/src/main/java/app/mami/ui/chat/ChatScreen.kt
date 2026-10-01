package app.mami.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.core.DeviceStatus
import app.mami.core.NetworkKind
import app.mami.core.RingerMode
import app.mami.data.ConnectionState
import app.mami.data.PresenceDto
import app.mami.data.db.MediaType
import app.mami.data.db.MessageEntity
import app.mami.data.db.MessageKind
import app.mami.ui.Files
import app.mami.ui.Format
import app.mami.ui.Overlay
import app.mami.ui.UiController
import app.mami.ui.components.Avatar
import app.mami.ui.components.HeartBurst
import app.mami.ui.components.SignalBars
import app.mami.ui.components.StatusPill
import app.mami.ui.components.batteryColor
import app.mami.ui.call.rememberCallStarter
import app.mami.ui.components.heartWallpaper
import app.mami.ui.theme.Mami
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Gap after which a new run of bubbles starts. */
private const val GROUP_GAP_MS = 5 * 60_000L

@Composable
fun ChatScreen(ui: UiController) {
    val backend = ui.backend
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by backend.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val partner by backend.partner.collectAsStateWithLifecycle()
    val presence by backend.presence.collectAsStateWithLifecycle()
    val statusPair by backend.partnerStatus.collectAsStateWithLifecycle()
    val typing by backend.partnerTyping.collectAsStateWithLifecycle()
    val secure by backend.secure.collectAsStateWithLifecycle()
    val keyChanged by backend.partnerKeyChanged.collectAsStateWithLifecycle()
    val myShares by backend.shares.collectAsStateWithLifecycle()
    val connection by backend.connection.collectAsStateWithLifecycle()
    val progress by backend.transferProgress.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val name = partner?.displayName?.ifBlank { null } ?: partner?.email?.substringBefore('@') ?: "Your partner"
    val status = visiblePartnerStatus(statusPair?.first, myShares)
    val hints = partnerHints(status, presence, name, now)

    val composer = remember { ComposerState() }
    val startCall = rememberCallStarter(backend.calls)
    var details by remember { mutableStateOf<MessageEntity?>(null) }
    var actionsFor by remember { mutableStateOf<MessageEntity?>(null) }
    var viewer by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var burst by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
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
    LaunchedEffect(ui.error) {
        val error = ui.error ?: return@LaunchedEffect
        snackbar.showSnackbar(error)
        ui.clearError()
    }
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(context, if (granted) "Now tap Save again" else "MaMi needs storage access to save on this Android version.", Toast.LENGTH_SHORT).show()
    }
    fun save(message: MessageEntity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        scope.launch {
            val saved = backend.saveToDevice(message)
            Toast.makeText(context, if (saved) "Saved to your phone" else "Couldn't save it", Toast.LENGTH_SHORT).show()
        }
    }
    fun open(message: MessageEntity) {
        val file = backend.fileFor(message)
        when {
            message.unsent -> Unit
            message.mediaKind == MediaType.PHOTO || message.mediaKind == MediaType.VIDEO -> when {
                message.viewOnce && (message.fromMe || message.openedAtMs != null) -> Unit
                file != null -> viewer = message.id
                else -> backend.retryTransfer(message.id)
            }
            message.mediaKind == MediaType.FILE -> if (file != null) Files.open(context, file, message.mediaMime) else backend.retryTransfer(message.id)
        }
    }
    val bubbleActions = remember(backend) {
        BubbleActions(
            onOpen = { open(it) },
            onLongPress = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                actionsFor = it
            },
            onReply = { composer.reply(it) },
            onJumpTo = { ui.jumpTo = it },
            onRetry = backend::retryTransfer,
            onOpenLink = { Files.openUrl(context, it) },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                AnimatedContent(searching, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "topbar") { isSearching ->
                    if (isSearching) {
                        SearchTopBar(query, onQuery = { query = it }, onClose = {
                            searching = false
                            query = ""
                        })
                    } else {
                        ChatTopBar(
                            name = name,
                            status = status,
                            presence = presence,
                            typing = typing,
                            hint = hints.firstOrNull(),
                            now = now,
                            onOpenPartner = { ui.partnerSheetOpen = true },
                            onCall = startCall,
                            onSearch = { searching = true },
                            onMedia = { ui.overlay = Overlay.Media },
                            onStarred = { ui.overlay = Overlay.Starred },
                            onSettings = { ui.overlay = Overlay.Settings },
                        )
                    }
                }
            },
            bottomBar = {
                if (!searching) {
                    Composer(ui, composer, name, onNudge = { kind ->
                        backend.sendNudge(kind)
                        burst++
                    })
                }
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
                    PinnedBar(messages, name) { ui.jumpTo = it }
                    Conversation(
                        ui = ui,
                        messages = messages,
                        partnerName = name,
                        typing = typing,
                        now = now,
                        progress = progress,
                        actions = bubbleActions,
                        onDetails = { details = it },
                        onCall = startCall,
                        modifier = Modifier.weight(1f),
                    )
                }
                HeartBurst(burst)
                if (searching) {
                    SearchResults(backend, query, name, now, onOpen = { message ->
                        searching = false
                        query = ""
                        ui.jumpTo = message.id
                    })
                }
            }
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
            onCall = { video ->
                ui.partnerSheetOpen = false
                startCall(video)
            },
            onMedia = {
                ui.partnerSheetOpen = false
                ui.overlay = Overlay.Media
            },
        )
    }
    details?.let { message -> MessageDetailsSheet(message, name) { details = null } }
    actionsFor?.let { message ->
        val file = backend.fileFor(message)
        val shareable = file != null && !message.viewOnce
        MessageActionsSheet(
            message = message,
            partnerName = name,
            menu = MessageMenu(
                onReact = { backend.react(message.id, it) },
                onReply = { composer.reply(message) },
                onCopy = if (message.body.isNotBlank() && message.kind != MessageKind.NUDGE && message.kind != MessageKind.ALERT) {
                    {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Message", message.body))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    null
                },
                onEdit = if (canEdit(message)) { { composer.edit(message) } } else null,
                onPin = { backend.setPinned(message.id, it) },
                onStar = { backend.setStarred(message.id, it) },
                onInfo = { details = message },
                onSave = if (shareable) { { save(message) } } else null,
                onShare = if (shareable && file != null) { { Files.share(context, file, message.mediaMime) } } else null,
                onUnsend = if (message.fromMe && !message.unsent) { { backend.unsend(message.id) } } else null,
                onDelete = { backend.deleteForMe(message.id) },
            ),
            onDismiss = { actionsFor = null },
        )
    }
    viewer?.let { start ->
        MediaViewer(
            backend = backend,
            items = remember(messages, start) { viewable(messages, backend, start) },
            startId = start,
            partnerName = name,
            onShowInChat = {
                viewer = null
                ui.jumpTo = it
            },
            onDismiss = { viewer = null },
        )
    }
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
    onCall: (video: Boolean) -> Unit,
    onSearch: () -> Unit,
    onMedia: () -> Unit,
    onStarred: () -> Unit,
    onSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
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
                            // The dots already bounce in the chat; up here, words are enough.
                            Text(
                                "typing",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.height(20.dp),
                            )
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
                IconButton(onClick = { onCall(true) }) { Icon(Icons.Filled.Videocam, contentDescription = "Video call") }
                IconButton(onClick = { onCall(false) }) { Icon(Icons.Filled.Call, contentDescription = "Voice call") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Search") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            onClick = {
                                menu = false
                                onSearch()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Media, links and files") },
                            leadingIcon = { Icon(Icons.Filled.PermMedia, contentDescription = null) },
                            onClick = {
                                menu = false
                                onMedia()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Starred messages") },
                            leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                            onClick = {
                                menu = false
                                onStarred()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = {
                                menu = false
                                onSettings()
                            },
                        )
                    }
                }
            }
            StatusStrip(status, now, onOpenPartner)
        }
    }
}

/** The newest pinned message, under the top bar. With several, a tap cycles through them. */
@Composable
private fun PinnedBar(messages: List<MessageEntity>, partnerName: String, onJump: (String) -> Unit) {
    val pinned = remember(messages) { messages.filter { it.pinnedAtMs != null && !it.unsent }.sortedByDescending { it.pinnedAtMs } }
    var index by remember { mutableIntStateOf(0) }
    AnimatedVisibility(visible = pinned.isNotEmpty()) {
        val shown = pinned.getOrNull(index % pinned.size.coerceAtLeast(1)) ?: return@AnimatedVisibility
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 1.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        onJump(shown.id)
                        if (pinned.size > 1) index = (index + 1) % pinned.size
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        if (pinned.size > 1) "Pinned · ${index % pinned.size + 1} of ${pinned.size}" else "Pinned",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        (if (shown.fromMe) "You: " else "$partnerName: ") + snippet(shown, partnerName),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
    ui: UiController,
    messages: List<MessageEntity>,
    partnerName: String,
    typing: Boolean,
    now: Long,
    progress: Map<String, Float>,
    actions: BubbleActions,
    onDetails: (MessageEntity) -> Unit,
    onCall: (video: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val backend = ui.backend
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val newestFirst = remember(messages) { messages.asReversed() }
    val byId = remember(messages) { messages.associateBy { it.id } }
    val newestMine = remember(messages) {
        newestFirst.firstOrNull { it.fromMe && !it.unsent && (it.kind == MessageKind.TEXT || it.kind == MessageKind.MEDIA) }?.id
    }
    var highlighted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(messages.size, typing) {
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }
    // Scroll to a message from search, a quote, the pinned bar or "show in chat", and flash it.
    LaunchedEffect(ui.jumpTo, newestFirst) {
        val target = ui.jumpTo ?: return@LaunchedEffect
        val index = newestFirst.indexOfFirst { it.id == target }
        ui.jumpTo = null
        if (index < 0) return@LaunchedEffect
        listState.animateScrollToItem(index + if (typing) 1 else 0, scrollOffset = -200)
        highlighted = target
        delay(1600)
        if (highlighted == target) highlighted = null
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
                    "Everything here is end-to-end encrypted.\nTap ❤️ to send a little \"thinking of you\", or hold the mic for a voice message.",
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
                        MessageKind.NUDGE -> NudgeSticker(message, partnerName) { onDetails(message) }
                        MessageKind.ALERT -> BatteryAlertCard(message, partnerName) { onDetails(message) }
                        MessageKind.CALL -> CallBubble(message, partnerName) { onCall(message.mediaKind == MediaType.VIDEO) }
                        else -> MessageRow(
                            message = message,
                            first = first,
                            last = last,
                            partnerName = partnerName,
                            quoted = message.replyTo?.let(byId::get),
                            file = if (message.isMedia) backend.fileFor(message) else null,
                            progress = progress[message.id],
                            highlighted = highlighted == message.id,
                            actions = actions,
                        )
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
    a.fromMe == b.fromMe && a.kind in grouped && b.kind in grouped && kotlin.math.abs(b.sortAtMs - a.sortAtMs) < GROUP_GAP_MS

private val grouped = setOf(MessageKind.TEXT, MessageKind.MEDIA)
