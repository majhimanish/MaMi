package app.mami.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mami.data.db.MessageEntity
import app.mami.data.db.MessageKind
import app.mami.data.db.MessageState
import app.mami.ui.Format
import app.mami.ui.components.TypingDots
import app.mami.ui.theme.Mami

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

/** A text message. [first]/[last] say where it sits in a run of messages from the same person. */
@Composable
fun TextBubble(message: MessageEntity, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val mine = message.fromMe
    if (isEmojiOnly(message.body)) {
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onClick),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            Text(message.body, fontSize = 46.sp, lineHeight = 54.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Format.time(message.sentAtMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (mine) {
                    Spacer(Modifier.width(4.dp))
                    StateIcon(message.state, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    val shape = if (mine) {
        RoundedCornerShape(topStart = Big, topEnd = if (first) Big else Small, bottomEnd = Small, bottomStart = Big)
    } else {
        RoundedCornerShape(topStart = if (first) Big else Small, topEnd = Big, bottomEnd = Big, bottomStart = Small)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .then(
                    if (mine) {
                        Modifier.background(Mami.colors.brush)
                    } else {
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    },
                )
                .clickable(onClick = onClick)
                .padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 7.dp),
        ) {
            val content = if (mine) Mami.colors.onGradient else MaterialTheme.colorScheme.onSurface
            Column {
                Text(message.body, style = MaterialTheme.typography.bodyLarge, color = content)
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Text(Format.time(message.sentAtMs), style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.75f))
                    if (mine) {
                        Spacer(Modifier.width(4.dp))
                        StateIcon(message.state, tint = content.copy(alpha = 0.8f))
                    }
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
                when (message.kind) {
                    MessageKind.TEXT -> "“${message.body}”"
                    MessageKind.NUDGE -> "${nudgeEmoji(message.body)} ${nudgeWords(message.body)}"
                    else -> "🔋 Battery alert"
                },
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
