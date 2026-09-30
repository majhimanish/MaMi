package app.mami.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
import app.mami.core.NudgeKind
import app.mami.data.db.MessageEntity
import app.mami.data.db.MessageKind
import app.mami.data.db.MessageState
import app.mami.sync.Notifications
import app.mami.ui.Format
import app.mami.ui.MainViewModel
import app.mami.ui.theme.BrandGradient
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: MainViewModel, onOpenSettings: () -> Unit, onVerify: () -> Unit) {
    val messenger = vm.messenger
    val messages by messenger.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val partner by messenger.partner.collectAsStateWithLifecycle()
    val presence by messenger.presence.collectAsStateWithLifecycle()
    val statusPair by messenger.partnerStatus.collectAsStateWithLifecycle()
    val typing by messenger.partnerTyping.collectAsStateWithLifecycle()
    val secure by messenger.secure.collectAsStateWithLifecycle()
    val keyChanged by messenger.partnerKeyChanged.collectAsStateWithLifecycle()
    val myShares by messenger.shares.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val name = messenger.partnerName
    val status = visiblePartnerStatus(statusPair?.first, myShares)
    val hints = partnerHints(status, presence, name, now)

    var showPartner by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<MessageEntity?>(null) }
    var nudgeShown by remember { mutableStateOf<NudgeKind?>(null) }
    val haptics = LocalHapticFeedback.current

    // While the chat is on screen, everything that arrives counts as read.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    messenger.chatVisible = true
                    messenger.markChatRead()
                }
                Lifecycle.Event.ON_PAUSE -> messenger.chatVisible = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            messenger.chatVisible = false
        }
    }
    LaunchedEffect(Unit) {
        messenger.incomingNudges.collect {
            nudgeShown = it
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(2200)
            nudgeShown = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showPartner = true },
                    ) {
                        Avatar(name, online = presence?.online == true)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val subtitle = when {
                                typing -> "typing…"
                                hints.isNotEmpty() -> hints.first().text
                                else -> "Tap to see how they're doing"
                            }
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hints.firstOrNull()?.urgent == true && !typing) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        bottomBar = {
            Composer(
                onChanged = messenger::onComposerChanged,
                onSend = { messenger.sendText(it) },
                onNudge = { messenger.sendNudge(it) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                if (!secure) {
                    Banner(Icons.Filled.Lock, "Setting up end-to-end encryption with $name's phone. Messages will be sent as soon as it's ready.")
                }
                if (keyChanged) {
                    Banner(
                        Icons.Filled.Warning,
                        "$name's security code changed, probably because of a new phone. Compare codes to be sure it's really them.",
                        action = "Compare" to onVerify,
                        dismiss = messenger::dismissKeyChanged,
                    )
                }
                Conversation(messages, name, now, onDetails = { details = it }, modifier = Modifier.weight(1f))
            }
            NudgeOverlay(nudgeShown)
        }
    }

    if (showPartner) {
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
            onNudge = { messenger.sendNudge(it) },
            onDismiss = { showPartner = false },
        )
    }
    details?.let { message -> MessageDetails(message, name) { details = null } }
}

@Composable
fun Avatar(name: String, online: Boolean, size: Int = 40) {
    Box {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(BrandGradient),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.firstOrNull()?.uppercase() ?: "♥",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontSize = (size * 0.42f).sp,
            )
        }
        if (online) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size((size / 3.6f).dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2EC27E))
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

@Composable
private fun Banner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    action: Pair<String, () -> Unit>? = null,
    dismiss: (() -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp))
            }
            if (action != null || dismiss != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    dismiss?.let { TextButton(onClick = it) { Text("Dismiss") } }
                    action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
                }
            }
        }
    }
}

@Composable
private fun Conversation(
    messages: List<MessageEntity>,
    partnerName: String,
    now: Long,
    onDetails: (MessageEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val newestFirst = remember(messages) { messages.asReversed() }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    if (messages.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Say hi to $partnerName 💗\nEverything here is end-to-end encrypted.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(newestFirst.size, key = { newestFirst[it].id }) { index ->
            val message = newestFirst[index]
            val older = newestFirst.getOrNull(index + 1)
            Column {
                if (older == null || !Format.sameDay(older.sortAtMs, message.sortAtMs)) {
                    DaySeparator(Format.day(message.sortAtMs, now))
                }
                when (message.kind) {
                    MessageKind.TEXT -> Bubble(message, onClick = { onDetails(message) })
                    else -> EventRow(message, partnerName, onClick = { onDetails(message) })
                }
            }
        }
    }
}

@Composable
private fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun Bubble(message: MessageEntity, onClick: () -> Unit) {
    val mine = message.fromMe
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (mine) 20.dp else 6.dp,
                bottomEnd = if (mine) 6.dp else 20.dp,
            ),
            onClick = onClick,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 6.dp)) {
                Text(message.body, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Format.time(message.sentAtMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColorWithAlpha(0.75f),
                    )
                    if (mine) {
                        Spacer(Modifier.size(4.dp))
                        StateIcon(message.state)
                    }
                }
            }
        }
    }
}

@Composable
private fun contentColorWithAlpha(alpha: Float): Color =
    androidx.compose.material3.LocalContentColor.current.copy(alpha = alpha)

/** Clock → one tick → two ticks → two bright ticks. */
@Composable
fun StateIcon(state: Int) {
    val (icon, description) = when (state) {
        MessageState.PENDING -> Icons.Filled.AccessTime to "Waiting to send"
        MessageState.SENT -> Icons.Filled.Check to "Sent"
        MessageState.DELIVERED -> Icons.Filled.DoneAll to "Delivered to their phone"
        else -> Icons.Filled.DoneAll to "Seen"
    }
    val tint = if (state == MessageState.READ) Color(0xFF7CF7FF) else contentColorWithAlpha(0.75f)
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(15.dp))
}

@Composable
private fun EventRow(message: MessageEntity, partnerName: String, onClick: () -> Unit) {
    val text = when (message.kind) {
        MessageKind.NUDGE -> if (message.fromMe) "You: ${Notifications.nudgeText(message.body)}" else "$partnerName ${Notifications.nudgeText(message.body)}"
        MessageKind.ALERT -> if (message.fromMe) {
            "🔋 Your phone told $partnerName it was at ${message.batteryPercent ?: "a few"}%"
        } else {
            Notifications.preview(message, partnerName)
        }
        else -> message.body
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(50),
            color = if (message.kind == MessageKind.ALERT) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            onClick = onClick,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = MaterialTheme.typography.bodySmall)
                if (message.fromMe) {
                    Spacer(Modifier.size(6.dp))
                    StateIcon(message.state)
                }
            }
        }
    }
}

@Composable
private fun Composer(onChanged: (String) -> Unit, onSend: (String) -> Unit, onNudge: (NudgeKind) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.Favorite, contentDescription = "Send a hug or kiss", tint = MaterialTheme.colorScheme.primary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(
                        NudgeKind.THINKING_OF_YOU to "💗 Thinking of you",
                        NudgeKind.HUG to "🤗 Hug",
                        NudgeKind.KISS to "😘 Kiss",
                        NudgeKind.MISS_YOU to "🥺 Miss you",
                    ).forEach { (kind, label) ->
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
                placeholder = { Text("Message") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    onSend(text)
                    text = ""
                },
                enabled = text.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun NudgeOverlay(kind: NudgeKind?) {
    AnimatedVisibility(
        visible = kind != null,
        enter = fadeIn() + scaleIn(initialScale = 0.4f),
        exit = fadeOut(tween(600)) + scaleOut(targetScale = 1.6f),
        modifier = Modifier.fillMaxSize(),
    ) {
        val pulse by animateFloatAsState(if (kind != null) 1.1f else 1f, label = "pulse")
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                when (kind) {
                    NudgeKind.HUG -> "🤗"
                    NudgeKind.KISS -> "😘"
                    NudgeKind.MISS_YOU -> "🥺"
                    else -> "💗"
                },
                fontSize = 120.sp,
                modifier = Modifier.scale(pulse),
            )
        }
    }
}

/** Exactly when a message was written, reached the server, reached the phone and was seen. */
@Composable
private fun MessageDetails(message: MessageEntity, partnerName: String, onDismiss: () -> Unit) {
    val rows = if (message.fromMe) {
        listOf(
            "Written on your phone" to message.sentAtMs,
            "Reached MaMi" to message.serverAtMs,
            "Reached $partnerName's phone" to message.deliveredAtMs,
            "Seen by $partnerName" to message.readAtMs,
        )
    } else {
        listOf(
            "Written on $partnerName's phone" to message.sentAtMs,
            "Reached MaMi" to message.sortAtMs,
            "Arrived on your phone" to message.deliveredAtMs,
            "You saw it" to message.readAtMs,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { (label, at) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        Text(
                            at?.let(Format::preciseDateTime) ?: "Not yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (message.fromMe && message.state == MessageState.PENDING) {
                    Text(
                        "Still on your phone. It will be sent as soon as there's a connection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
