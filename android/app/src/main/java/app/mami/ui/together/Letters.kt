package app.mami.ui.together

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mami.data.db.MessageEntity
import app.mami.data.db.MessageState
import app.mami.ui.Format
import app.mami.ui.theme.Caveat
import app.mami.ui.theme.Mami
import java.time.ZoneId

/** The paper a letter is written on: its colour, ink and seal. */
enum class Paper(val key: String, val label: String, val color: Color, val ink: Color, val seal: Color, val lines: Color) {
    CREAM("cream", "Cream", Color(0xFFFFF6E3), Color(0xFF4A3428), Color(0xFFC0392B), Color(0x1A8A5A3C)),
    ROSE("rose", "Rose", Color(0xFFFFE6EE), Color(0xFF5A1F35), Color(0xFFD6336C), Color(0x1AD6336C)),
    LAVENDER("lavender", "Lavender", Color(0xFFEFE8FF), Color(0xFF35265E), Color(0xFF7048E8), Color(0x1A7048E8)),
    NIGHT("night", "Night", Color(0xFF1E2140), Color(0xFFF3ECFF), Color(0xFFF59F00), Color(0x22FFFFFF)),
    ;

    companion object {
        fun of(key: String?): Paper = entries.firstOrNull { it.key == key } ?: CREAM
    }
}

private fun letterStyle(paper: Paper, size: Int = 24) = TextStyle(fontFamily = Caveat, fontSize = size.sp, lineHeight = (size * 1.25f).sp, color = paper.ink)

/** Sealed until a date, and that date hasn't come. */
fun MessageEntity.isSealed(now: Long): Boolean = (unlockAtMs ?: 0) > now

/** A letter in the chat: an envelope with a wax seal. Tap to read. */
@Composable
fun LetterCard(message: MessageEntity, partnerName: String, now: Long, onOpen: () -> Unit) {
    val paper = Paper.of(message.paper)
    val sealed = message.isSealed(now)
    val status = when {
        message.unsent -> "Unsent"
        sealed -> "Opens ${whenIn(message.unlockAtMs!!, ZoneId.systemDefault(), now)}"
        message.fromMe && message.openedAtMs != null -> "Opened by $partnerName · ${Format.time(message.openedAtMs)}"
        message.fromMe && message.state >= MessageState.DELIVERED -> "Delivered · not opened yet"
        message.fromMe -> "Sending…"
        message.openedAtMs != null -> "Read · tap to read again"
        else -> "Tap to open"
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = if (message.fromMe) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start) {
            Envelope(paper, sealed = sealed, opened = message.openedAtMs != null, modifier = Modifier.width(250.dp).clickable(enabled = !message.unsent, onClick = onOpen)) {
                Text(
                    if (message.fromMe) "To $partnerName" else "From $partnerName",
                    style = MaterialTheme.typography.labelMedium,
                    color = paper.ink.copy(alpha = 0.7f),
                )
                Text(
                    message.title ?: "A love letter",
                    style = letterStyle(paper, 26),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
                if (sealed) Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp).padding(end = 2.dp))
                Text("💌 $status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** An envelope: the paper, a folded flap and a wax seal with a heart; the content sits below the flap. */
@Composable
fun Envelope(paper: Paper, sealed: Boolean, opened: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(paper.color),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val flapDepth = 64.dp.toPx()
            val flap = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width / 2, flapDepth)
                lineTo(size.width, 0f)
                close()
            }
            drawPath(flap, paper.ink.copy(alpha = 0.07f))
            drawLine(paper.ink.copy(alpha = 0.12f), Offset(0f, 0f), Offset(size.width / 2, flapDepth), strokeWidth = 2f)
            drawLine(paper.ink.copy(alpha = 0.12f), Offset(size.width, 0f), Offset(size.width / 2, flapDepth), strokeWidth = 2f)
        }
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 94.dp, bottom = 16.dp)) { content() }
        // The seal sits on the tip of the flap.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 44.dp)
                .size(40.dp)
                .graphicsLayer { alpha = if (opened && !sealed) 0.55f else 1f }
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(paper.seal),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (sealed) Icons.Filled.Lock else Icons.Filled.Favorite, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Reading a letter: the envelope opens and the letter slides out. A sealed
 * letter stays closed and counts down to its day.
 */
@Composable
fun LetterReader(message: MessageEntity, partnerName: String, now: Long, onOpened: () -> Unit, onDismiss: () -> Unit) {
    val paper = Paper.of(message.paper)
    val sealed = message.isSealed(now)
    val rise = remember { Animatable(0f) }
    LaunchedEffect(sealed) {
        if (sealed) return@LaunchedEffect
        if (!message.fromMe) onOpened()
        rise.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow))
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            if (sealed) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Envelope(paper, sealed = true, opened = false, modifier = Modifier.width(300.dp)) {
                        Text(message.title ?: "A love letter", style = letterStyle(paper, 30), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Sealed until ${whenIn(message.unlockAtMs!!, ZoneId.systemDefault(), now)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = paper.ink.copy(alpha = 0.75f),
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                        Text(
                            "🔒 ${countdown(message.unlockAtMs!!, now)} to go",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 48.dp)
                        .graphicsLayer {
                            translationY = (1f - rise.value) * 260f
                            alpha = 0.25f + 0.75f * rise.value
                            scaleX = 0.9f + 0.1f * rise.value
                            scaleY = 0.9f + 0.1f * rise.value
                        },
                ) {
                    LetterPaper(message, paper, partnerName, onClose = onDismiss)
                }
            }
        }
    }
}

/** The letter itself, on ruled paper in handwriting. */
@Composable
private fun LetterPaper(message: MessageEntity, paper: Paper, partnerName: String, onClose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = paper.color,
        shadowElevation = 16.dp,
        // Taps on the letter don't close it; taps around it do.
        modifier = Modifier.fillMaxWidth().heightIn(min = 420.dp).clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Box {
            RuledLines(paper)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (message.fromMe) "To $partnerName" else "From $partnerName") + " · " + Format.day(message.sentAtMs, System.currentTimeMillis()),
                        style = MaterialTheme.typography.labelMedium,
                        color = paper.ink.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = paper.ink.copy(alpha = 0.6f)) }
                }
                message.title?.let {
                    Text(it, style = letterStyle(paper, 34))
                    Spacer(Modifier.height(8.dp))
                }
                Text(message.body, style = letterStyle(paper, 25))
                Spacer(Modifier.height(18.dp))
                Text("💗", fontSize = 28.sp, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun RuledLines(paper: Paper) {
    Canvas(Modifier.fillMaxSize()) {
        val gap = 31.dp.toPx()
        var y = 92.dp.toPx()
        while (y < size.height) {
            drawLine(paper.lines, Offset(18.dp.toPx(), y), Offset(size.width - 18.dp.toPx(), y), strokeWidth = 1.5f)
            y += gap
        }
        drawLine(paper.seal.copy(alpha = 0.18f), Offset(14.dp.toPx(), 0f), Offset(14.dp.toPx(), size.height), strokeWidth = 2f)
    }
}

/** Writing a letter: the paper, a title, the letter, and when it may be opened. */
@Composable
fun LetterComposer(partnerName: String, now: Long, onDismiss: () -> Unit, onSend: (title: String, body: String, paper: String, openAt: Long?) -> Unit) {
    var paper by remember { mutableStateOf(Paper.CREAM) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var openAt by remember { mutableStateOf<Long?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    Text("Letter to $partnerName", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onSend(title, body, paper.key, openAt) }, enabled = body.isNotBlank()) {
                        Text(if (openAt != null) "Seal & send" else "Send")
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Paper.entries.forEach { option ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { paper = option }.padding(4.dp)) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(option.color)
                                    .border(
                                        if (paper == option) BorderStroke(3.dp, Mami.colors.brush) else BorderStroke(1.dp, SolidColor(MaterialTheme.colorScheme.outlineVariant)),
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (paper == option) Icon(Icons.Filled.Check, contentDescription = null, tint = option.ink, modifier = Modifier.size(18.dp))
                            }
                            Text(option.label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = paper.color,
                    shadowElevation = 6.dp,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Box {
                        RuledLines(paper)
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 22.dp)) {
                            LetterField(title, { title = it }, "A title (optional)", letterStyle(paper, 34), paper, singleLine = true)
                            Spacer(Modifier.height(8.dp))
                            LetterField(body, { body = it }, "My love,…", letterStyle(paper, 25), paper, modifier = Modifier.heightIn(min = 280.dp))
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("When can $partnerName open it?", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = openAt == null, onClick = { openAt = null }, label = { Text("Right away") })
                        FilterChip(
                            selected = openAt != null,
                            onClick = { pickingDate = true },
                            label = { Text(openAt?.let { "On ${whenIn(it, ZoneId.systemDefault(), now).substringBefore(',')}" } ?: "On a special day…") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                    Text(
                        if (openAt != null) {
                            "$partnerName sees the envelope now, but it stays sealed until then."
                        } else {
                            "End-to-end encrypted, like everything in MaMi."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (pickingDate) {
        DateTimePicker(
            zone = ZoneId.systemDefault(),
            now = now,
            title = "Opens at",
            onDismiss = { pickingDate = false },
            onPicked = {
                openAt = it
                pickingDate = false
            },
            initialMs = openAt ?: (now + 24 * 60 * 60_000L),
        )
    }
}

@Composable
private fun LetterField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    style: TextStyle,
    paper: Paper,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValue,
        textStyle = style,
        singleLine = singleLine,
        cursorBrush = SolidColor(paper.seal),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = paper.ink.copy(alpha = 0.35f)))
                inner()
            }
        },
    )
}

/** A letter in the Together list. */
@Composable
fun LetterRow(message: MessageEntity, partnerName: String, now: Long, onClick: () -> Unit) {
    val paper = Paper.of(message.paper)
    val sealed = message.isSealed(now)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 52.dp, height = 38.dp).shadow(2.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)).background(paper.color),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.matchParentSize()) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width / 2, size.height * 0.55f)
                    lineTo(size.width, 0f)
                }
                drawPath(path, paper.ink.copy(alpha = 0.25f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            }
            Box(Modifier.padding(top = 10.dp).size(14.dp).clip(CircleShape).background(paper.seal))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(message.title ?: "A love letter", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (message.fromMe) "To $partnerName" else "From $partnerName") + " · " + when {
                    sealed -> "opens in ${countdown(message.unlockAtMs!!, now)}"
                    message.fromMe && message.openedAtMs != null -> "opened"
                    message.fromMe -> "not opened yet"
                    message.openedAtMs == null -> "new"
                    else -> "read"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (!message.fromMe && message.openedAtMs == null && !sealed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sealed) Icon(Icons.Filled.Lock, contentDescription = "Sealed", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
