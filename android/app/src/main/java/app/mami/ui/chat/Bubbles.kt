package app.mami.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mami.core.extractLinks
import app.mami.data.db.MediaType
import app.mami.data.db.MessageEntity
import app.mami.data.db.MessageKind
import app.mami.data.db.MessageState
import app.mami.sync.Notifications
import app.mami.ui.Format
import app.mami.ui.components.TypingDots
import app.mami.ui.rememberBase64Image
import app.mami.ui.theme.Mami
import java.io.File
import kotlinx.coroutines.launch

private val Big = 22.dp
private val Small = 6.dp

fun nudgeEmoji(kind: String): String = when (kind) {
    "HUG" -> "🤗"
    "KISS" -> "😘"
    "MISS_YOU" -> "🥺"
    else -> "💗"
}

fun nudgeWords(kind: String): String = when (kind) {
    "HUG" -> "a hug"
    "KISS" -> "a kiss"
    "MISS_YOU" -> "\"I miss you\""
    else -> "\"thinking of you\""
}

/** Messages of only a few emoji are shown big, without a bubble. */
fun isEmojiOnly(text: String): Boolean {
    val compact = text.filterNot(Char::isWhitespace)
    return compact.isNotEmpty() && compact.length <= 16 && compact.none(Char::isLetterOrDigit) && compact.any { it.code > 0x2000 }
}

/** Clock → one tick → two ticks → two bright ticks. */
@Composable
fun StateIcon(state: Int, tint: Color = LocalContentColor.current.copy(alpha = 0.8f)) {
    AnimatedContent(state, transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() }, label = "state") { s ->
        val (icon, description) = when (s) {
            MessageState.PENDING -> Icons.Filled.AccessTime to "Waiting to send"
            MessageState.SENT -> Icons.Filled.Check to "Sent"
            MessageState.DELIVERED -> Icons.Filled.DoneAll to "Delivered to their phone"
            else -> Icons.Filled.DoneAll to "Seen"
        }
        Icon(
            icon,
            contentDescription = description,
            tint = if (s == MessageState.READ) Color(0xFF7CF4FF) else tint,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** Everything a bubble can ask the chat to do. */
class BubbleActions(
    /** Open a photo or video full screen, a file in another app, or a view-once. */
    val onOpen: (MessageEntity) -> Unit,
    val onLongPress: (MessageEntity) -> Unit,
    /** Swiped to reply. */
    val onReply: (MessageEntity) -> Unit,
    /** Tapped a quoted message: scroll to it. */
    val onJumpTo: (String) -> Unit,
    /** Download, or retry a failed upload or download. */
    val onRetry: (String) -> Unit,
    val onOpenLink: (String) -> Unit,
)

/** One short line describing a message, for quotes, search results and notifications. */
fun snippet(message: MessageEntity, partnerName: String): String = when {
    message.unsent -> "Unsent message"
    message.kind == MessageKind.MEDIA -> Notifications.mediaLabel(message) + message.body.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    message.kind == MessageKind.NUDGE -> "${nudgeEmoji(message.body)} ${nudgeWords(message.body)}"
    message.kind == MessageKind.ALERT -> "🔋 Battery alert"
    message.kind == MessageKind.CALL -> callLabel(message)
    else -> message.body
}

fun callLabel(message: MessageEntity): String {
    val video = message.mediaKind == "VIDEO"
    val what = if (video) "Video call" else "Voice call"
    return when (message.callOutcome) {
        "MISSED" -> if (message.fromMe) "$what · no answer" else "Missed ${what.lowercase()}"
        "DECLINED" -> "$what · declined"
        else -> "$what · ${message.mediaDurationMs?.let { Notifications.duration(it) } ?: ""}"
    }
}

/**
 * A message with everything around it: swipe right to reply, long-press for
 * reactions and actions, the quoted message on top, reactions underneath.
 * [first]/[last] say where it sits in a run of messages from the same person.
 */
@Composable
fun MessageRow(
    message: MessageEntity,
    first: Boolean,
    last: Boolean,
    partnerName: String,
    quoted: MessageEntity?,
    file: File?,
    progress: Float?,
    highlighted: Boolean,
    actions: BubbleActions,
) {
    val mine = message.fromMe
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    var armed by remember { mutableStateOf(false) }
    val flash by animateFloatAsState(if (highlighted) 1f else 0f, animationSpec = tween(if (highlighted) 200 else 1400), label = "flash")

    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f * flash), RoundedCornerShape(12.dp))
            .pointerInput(message.id, message.unsent) {
                if (message.unsent) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offset.value >= threshold) actions.onReply(message)
                        armed = false
                        scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.6f)) }
                    },
                    onDragCancel = {
                        armed = false
                        scope.launch { offset.animateTo(0f) }
                    },
                ) { change, amount ->
                    val next = (offset.value + amount).coerceIn(0f, threshold * 1.3f)
                    if (next >= threshold && !armed) {
                        armed = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    change.consume()
                    scope.launch { offset.snapTo(next) }
                }
            },
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
                .size(24.dp)
                .graphicsLayer {
                    val p = (offset.value / threshold).coerceIn(0f, 1f)
                    alpha = p
                    scaleX = 0.6f + 0.4f * p
                    scaleY = 0.6f + 0.4f * p
                },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset.value },
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            when {
                message.unsent -> UnsentBubble(message, partnerName)
                message.kind == MessageKind.TEXT && isEmojiOnly(message.body) && quoted == null && message.linkUrl == null ->
                    BigEmoji(message, actions)
                else -> Bubble(message, first, partnerName, quoted, file, progress, actions)
            }
            Reactions(message, actions)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BigEmoji(message: MessageEntity, actions: BubbleActions) {
    Column(
        Modifier.combinedClickable(onClick = {}, onLongClick = { actions.onLongPress(message) }),
        horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start,
    ) {
        Text(message.body, fontSize = 46.sp, lineHeight = 54.sp)
        Footer(message, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    message: MessageEntity,
    first: Boolean,
    partnerName: String,
    quoted: MessageEntity?,
    file: File?,
    progress: Float?,
    actions: BubbleActions,
) {
    val mine = message.fromMe
    val shape = if (mine) {
        RoundedCornerShape(topStart = Big, topEnd = if (first) Big else Small, bottomEnd = Small, bottomStart = Big)
    } else {
        RoundedCornerShape(topStart = if (first) Big else Small, topEnd = Big, bottomEnd = Big, bottomStart = Small)
    }
    val content = if (mine) Mami.colors.onGradient else MaterialTheme.colorScheme.onSurface
    val visual = message.isMedia && !message.viewOnce && (message.mediaKind == MediaType.PHOTO || message.mediaKind == MediaType.VIDEO)
    val inner = RoundedCornerShape(Big - 4.dp)
    Box(
        Modifier
            .widthIn(max = 300.dp)
            .clip(shape)
            .then(if (mine) Modifier.background(Mami.colors.brush) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest))
            .combinedClickable(
                onClick = { if (message.isMedia || message.kind == MessageKind.CALL) actions.onOpen(message) },
                onLongClick = { actions.onLongPress(message) },
            )
            .padding(if (visual) 4.dp else 0.dp),
    ) {
        Column(Modifier.width(IntrinsicSize.Max)) {
            if (quoted != null || message.replyTo != null) {
                ReplyQuote(quoted, partnerName, mine, Modifier.padding(if (visual) 0.dp else 6.dp).padding(bottom = if (visual) 4.dp else 0.dp)) {
                    message.replyTo?.let(actions.onJumpTo)
                }
            }
            // Without a caption, the time sits on the photo itself.
            val overlayFooter: (@Composable BoxScope.() -> Unit)? = if (message.body.isBlank()) {
                { Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { OverlayFooter(message) } }
            } else {
                null
            }
            when {
                visual -> VisualContent(
                    message, file, progress, inner,
                    onOpen = { actions.onOpen(message) },
                    onRetry = { actions.onRetry(message.id) },
                    footer = overlayFooter,
                )
                message.isMedia && message.viewOnce -> Box(Modifier.padding(6.dp)) {
                    ViewOnceContent(message, file, progress, partnerName, content, onOpen = { actions.onOpen(message) }, onRetry = { actions.onRetry(message.id) })
                }
                message.isMedia && message.mediaKind == MediaType.VOICE -> Box(Modifier.padding(start = 6.dp, end = 10.dp, top = 8.dp)) {
                    VoiceContent(message, file, progress, mine, content, onRetry = { actions.onRetry(message.id) })
                }
                message.isMedia -> Box(Modifier.padding(6.dp)) {
                    FileContent(message, file, progress, content, onOpen = { actions.onOpen(message) }, onRetry = { actions.onRetry(message.id) })
                }
            }
            val url = message.linkUrl
            if (url != null) {
                LinkPreviewCard(
                    url, message.linkTitle, message.linkDescription, message.linkImage, content,
                    onClick = { actions.onOpenLink(url) },
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp),
                )
            }
            val showText = message.body.isNotBlank() && message.kind != MessageKind.CALL
            if (showText || !visual) {
                Column(Modifier.padding(start = 14.dp, end = 12.dp, top = if (showText) 7.dp else 0.dp, bottom = 7.dp)) {
                    if (showText) {
                        Text(linkified(message.body, content), style = MaterialTheme.typography.bodyLarge, color = content)
                    }
                    Footer(message, content.copy(alpha = 0.75f), Modifier.align(Alignment.End))
                }
            }
        }
    }
}

/** Time, "edited", star and ticks. */
@Composable
private fun Footer(message: MessageEntity, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (message.pinnedAtMs != null) {
            Icon(Icons.Filled.PushPin, contentDescription = "Pinned", tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
        }
        if (message.starred) {
            Icon(Icons.Filled.Star, contentDescription = "Starred", tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
        }
        if (message.editedAtMs != null) {
            Text("edited · ", style = MaterialTheme.typography.labelSmall, color = color)
        }
        Text(Format.time(message.sentAtMs), style = MaterialTheme.typography.labelSmall, color = color)
        if (message.fromMe) {
            Spacer(Modifier.width(4.dp))
            StateIcon(message.state, tint = color)
        }
    }
}

/** The footer over a photo without a caption. */
@Composable
private fun OverlayFooter(message: MessageEntity) {
    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White) {
        Footer(message, Color.White, Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** The message being replied to, inside the reply. */
@Composable
fun ReplyQuote(quoted: MessageEntity?, partnerName: String, onGradient: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = if (onGradient) Color.White else MaterialTheme.colorScheme.primary
    val background = if (onGradient) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    val text = if (onGradient) Mami.colors.onGradient else MaterialTheme.colorScheme.onSurface
    val thumb = rememberBase64Image(quoted?.takeIf { !it.viewOnce }?.mediaThumb)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                when {
                    quoted == null -> "Message"
                    quoted.fromMe -> "You"
                    else -> partnerName
                },
                style = MaterialTheme.typography.labelLarge,
                color = accent,
            )
            Text(
                quoted?.let { snippet(it, partnerName) } ?: "No longer on this phone",
                style = MaterialTheme.typography.bodySmall,
                color = text.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (thumb != null) {
            Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
        }
    }
}

/** "You unsent a message" in place of what was there. */
@Composable
private fun UnsentBubble(message: MessageEntity, partnerName: String) {
    Surface(
        shape = RoundedCornerShape(Big),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Block, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (message.fromMe) "You unsent a message" else "$partnerName unsent a message",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
            )
            Spacer(Modifier.width(8.dp))
            Text(Format.time(message.sentAtMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Both reactions, in a little pill tucked under the bubble. */
@Composable
private fun Reactions(message: MessageEntity, actions: BubbleActions) {
    val emojis = listOfNotNull(message.theirReaction, message.myReaction)
    AnimatedVisibility(visible = emojis.isNotEmpty(), enter = scaleIn(spring(dampingRatio = 0.45f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
        Surface(
            onClick = { actions.onLongPress(message) },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.background),
            shadowElevation = 1.dp,
            modifier = Modifier.padding(horizontal = 10.dp).offset(y = (-6).dp),
        ) {
            Text(
                if (emojis.size == 2 && emojis[0] == emojis[1]) "${emojis[0]} 2" else emojis.joinToString(" "),
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Underlines the links in a message and makes them tappable. */
@Composable
fun linkified(text: String, color: Color): AnnotatedString {
    val links = remember(text) { extractLinks(text) }
    if (links.isEmpty()) return AnnotatedString(text)
    val style = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold))
    return remember(text, color) {
        buildAnnotatedString {
            append(text)
            var from = 0
            links.forEach { link ->
                val at = text.indexOf(link, from)
                if (at >= 0) {
                    val url = if (link.startsWith("http", ignoreCase = true)) link else "https://$link"
                    addLink(LinkAnnotation.Url(url, style), at, at + link.length)
                    from = at + link.length
                }
            }
        }
    }
}

/** "Seen 9:41 PM" under the newest message I sent, so nobody has to wonder. */
@Composable
fun ReceiptLine(message: MessageEntity, partnerName: String) {
    val text = when (message.state) {
        MessageState.READ -> "Seen ${message.readAtMs?.let(Format::time).orEmpty()}"
        MessageState.DELIVERED -> "Delivered to $partnerName's phone ${message.deliveredAtMs?.let(Format::time).orEmpty()}"
        MessageState.SENT -> "Sent · waiting for $partnerName's phone"
        else -> "Waiting to send…"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (message.state == MessageState.READ) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp, end = 6.dp),
    )
}

/** A hug, kiss or "thinking of you", shown as a little sticker. */
@Composable
fun NudgeSticker(message: MessageEntity, partnerName: String, onClick: () -> Unit) {
    val words = nudgeWords(message.body)
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = if (message.fromMe) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.5.dp, Mami.colors.gradient.first().copy(alpha = 0.45f)),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(nudgeEmoji(message.body), fontSize = 40.sp)
                Text(
                    if (message.fromMe) "You sent $words" else "$partnerName sent you $words",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Format.time(message.sentAtMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (message.fromMe) {
                        Spacer(Modifier.width(4.dp))
                        StateIcon(message.state, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** "Their phone is at 4%" — sent automatically by the phone itself. */
@Composable
fun BatteryAlertCard(message: MessageEntity, partnerName: String, onClick: () -> Unit) {
    val percent = message.batteryPercent?.toString() ?: "a few "
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = Mami.colors.bad.copy(alpha = 0.12f)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Mami.colors.bad.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.BatteryAlert, contentDescription = null, tint = Mami.colors.bad)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        if (message.fromMe) "Your phone told $partnerName it was at $percent%" else "$partnerName's phone is at $percent%",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (message.fromMe) "Sent automatically · ${Format.time(message.sentAtMs)}" else "It may switch off soon · ${Format.time(message.sentAtMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun TypingBubble(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .clip(RoundedCornerShape(topStart = Big, topEnd = Big, bottomEnd = Big, bottomStart = Small))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 18.dp, vertical = 15.dp),
        ) {
            TypingDots()
        }
    }
}

@Composable
fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
        }
    }
}

private data class Step(val icon: ImageVector, val label: String, val at: Long?)

/** The journey of one message, with the exact time of every step. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailsSheet(message: MessageEntity, partnerName: String, onDismiss: () -> Unit) {
    val steps = if (message.fromMe) {
        listOf(
            Step(Icons.Filled.Edit, "Written on your phone", message.sentAtMs),
            Step(Icons.Filled.CloudDone, "Reached MaMi", message.serverAtMs),
            Step(Icons.Filled.PhoneAndroid, "Reached $partnerName's phone", message.deliveredAtMs),
            Step(Icons.Filled.Visibility, "Seen by $partnerName", message.readAtMs),
        )
    } else {
        listOf(
            Step(Icons.Filled.Edit, "Written on $partnerName's phone", message.sentAtMs),
            Step(Icons.Filled.CloudDone, "Reached MaMi", message.sortAtMs),
            Step(Icons.Filled.PhoneAndroid, "Arrived on your phone", message.deliveredAtMs),
            Step(Icons.Filled.Visibility, "You saw it", message.readAtMs),
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Message journey", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                if (message.kind == MessageKind.TEXT && !message.unsent) "“${message.body}”" else snippet(message, partnerName),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
            )
            Spacer(Modifier.height(20.dp))
            var previous: Long? = null
            steps.forEachIndexed { index, step ->
                val done = step.at != null
                Row {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .then(if (done) Modifier.background(Mami.colors.brush) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                step.icon,
                                contentDescription = null,
                                tint = if (done) Color.White else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        if (index < steps.lastIndex) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(30.dp)
                                    .background(
                                        if (steps[index + 1].at != null) Mami.colors.gradient.last() else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                            )
                        }
                    }
                    Column(Modifier.padding(start = 16.dp, top = 2.dp)) {
                        Text(step.label, style = MaterialTheme.typography.titleMedium, color = if (done) Color.Unspecified else MaterialTheme.colorScheme.outline)
                        val at = step.at
                        Text(
                            when {
                                at == null -> "Not yet"
                                previous != null -> "${Format.preciseDateTime(at)} · ${gap(at - previous!!)} later"
                                else -> Format.preciseDateTime(at)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (step.at != null) previous = step.at
            }
            if (message.fromMe && message.state == MessageState.PENDING) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Still on your phone. It goes out by itself as soon as there's a connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun gap(ms: Long): String = when {
    ms < 1000 -> "${ms.coerceAtLeast(0)} ms"
    ms < 60_000 -> String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
    ms < 3_600_000 -> "${ms / 60_000} min"
    else -> "${ms / 3_600_000} h ${(ms / 60_000) % 60} min"
}
