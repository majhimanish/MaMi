package app.mami.ui.together

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.core.CheckInKind
import app.mami.core.TogetherKey
import app.mami.data.Moods
import app.mami.data.PAUSED_INDEFINITELY
import app.mami.data.SharedLocation
import app.mami.data.TogetherInfo
import app.mami.data.db.MessageEntity
import app.mami.data.db.MessageKind
import app.mami.data.db.MoodEntity
import app.mami.data.pausedIndefinitely
import app.mami.sync.Notifications
import app.mami.ui.Format
import app.mami.ui.UiController
import app.mami.ui.chat.visiblePartnerStatus
import app.mami.ui.theme.Fredoka
import app.mami.ui.theme.Mami
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

/**
 * Everything that's just the two of you: days together, the next time
 * you'll meet, moods, letters, check-ins and live
 * location, scheduled messages and pausing sharing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TogetherScreen(ui: UiController, partnerName: String, onBack: () -> Unit) {
    val backend = ui.backend
    val together by backend.together.collectAsStateWithLifecycle()
    val moods by backend.moods.collectAsStateWithLifecycle(initialValue = emptyList())
    val messages by backend.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val statusPair by backend.partnerStatus.collectAsStateWithLifecycle()
    val myShares by backend.shares.collectAsStateWithLifecycle()
    val paused by backend.sharingPausedUntil.collectAsStateWithLifecycle()
    val theirLocation by backend.partnerLocation.collectAsStateWithLifecycle()
    val myLocationUntil by backend.myLocationUntil.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    val pausedNow = (paused ?: 0) > now
    val zone = partnerZone(visiblePartnerStatus(statusPair?.first, if (pausedNow) emptySet() else myShares))
    val letters = remember(messages) { messages.filter { it.kind == MessageKind.LETTER && !it.unsent }.asReversed() }
    val scheduled = remember(messages, now) { messages.filter { it.fromMe && it.hiddenUntil(now) && !it.unsent } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Together") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DaysCard(together, now, onSetSince = { backend.setTogether(TogetherKey.SINCE, it) })
            MeetingCard(together, now, onSet = { at, label ->
                backend.setTogether(TogetherKey.NEXT_MEETING, at?.toString())
                backend.setTogether(TogetherKey.NEXT_MEETING_LABEL, label?.trim()?.ifEmpty { null })
            })
            MoodCard(moods, partnerName, now, onMood = backend::setMood)
            LettersCard(letters, partnerName, now, onWrite = { ui.writingLetter = true }, onOpen = { ui.reading = it.id })
            SafeCard(partnerName, theirLocation, myLocationUntil, now, onCheckIn = backend::checkIn, onLocation = { ui.locationOpen = true })
            ScheduledCard(scheduled, partnerName, zone, now, onCancel = backend::unsend)
            PauseCard(paused, partnerName, now, onPause = backend::pauseSharing)
        }
    }
}

/** A card on the Together page: an emoji, a title, an optional action and the content. */
@Composable
fun TogetherCard(
    emoji: String,
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
                Text(emoji, fontSize = 20.sp)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
                action?.invoke()
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

// ---- days together ----------------------------------------------------------------------

private fun parseDate(text: String?): LocalDate? = text?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** "963 days together", how long that is, and the next anniversary. */
@Composable
fun DaysCard(together: TogetherInfo, now: Long, onSetSince: (String) -> Unit) {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val since = parseDate(together.since)?.takeIf { !it.isAfter(today) }
    var picking by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Mami.colors.brush)
            .clickable { picking = true }
            .padding(22.dp),
    ) {
        Column {
            Text("💞  Together", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
            if (since == null) {
                Spacer(Modifier.height(8.dp))
                Text("When did your story begin?", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pick the day you got together and MaMi counts the days for you both.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { picking = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Mami.colors.gradient.first()),
                ) { Text("Pick the date") }
            } else {
                val days = ChronoUnit.DAYS.between(since, today)
                val period = Period.between(since, today)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%,d".format(days), fontFamily = Fredoka, fontSize = 56.sp, color = Color.White, lineHeight = 60.sp)
                    Text(if (days == 1L) " day" else " days", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
                }
                Text(
                    "Since " + DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).format(since) + (howLong(period)?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.92f),
                )
                Spacer(Modifier.height(14.dp))
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.2f), contentColor = Color.White) {
                    Text(anniversary(since, today), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
        }
        if (since != null) {
            Icon(Icons.Filled.Edit, contentDescription = "Change the date", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.TopEnd).size(18.dp))
        }
    }
    if (picking) {
        DateTimePicker(
            zone = zone,
            now = now,
            title = "",
            pickTime = false,
            allowPast = true,
            initialMs = since?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            onDismiss = { picking = false },
            onPicked = { ms ->
                picking = false
                val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
                if (!date.isAfter(today)) onSetSince(date.toString())
            },
        )
    }
}

private fun howLong(period: Period): String? {
    val parts = listOfNotNull(
        period.years.takeIf { it > 0 }?.let { "$it ${if (it == 1) "year" else "years"}" },
        period.months.takeIf { it > 0 }?.let { "$it ${if (it == 1) "month" else "months"}" },
    )
    return parts.joinToString(", ").ifEmpty { null }
}

private fun anniversary(since: LocalDate, today: LocalDate): String {
    var next = since.withYear(today.year)
    if (next.isBefore(today)) next = since.withYear(today.year + 1)
    val years = next.year - since.year
    val days = ChronoUnit.DAYS.between(today, next)
    return when {
        years == 0 -> "🌱 Your story starts today"
        days == 0L -> "🎉 Happy anniversary! $years ${if (years == 1) "year" else "years"} today"
        days == 1L -> "🎉 Anniversary tomorrow"
        else -> "🎉 Anniversary in $days days"
    }
}

// ---- next meeting -----------------------------------------------------------------------

/** The next time you'll be together, counting down. */
@Composable
fun MeetingCard(together: TogetherInfo, now: Long, onSet: (Long?, String?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val meeting = together.nextMeetingMs
    TogetherCard(
        "✈️",
        "Next time together",
        action = if (meeting != null) {
            @Composable { IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = "Change", modifier = Modifier.size(20.dp)) } }
        } else {
            null
        },
    ) {
        when {
            meeting == null || meeting < now - 12 * HOUR -> {
                Text(
                    if (meeting == null) "When will you see each other next? Count down together." else "Hope it was lovely 💗 When's the next one?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { editing = true }) { Text("Add a date") }
            }
            meeting <= now -> {
                Text("Together now 💗", style = MaterialTheme.typography.headlineSmall)
                together.nextMeetingLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            else -> {
                Text(together.nextMeetingLabel ?: "See you soon", style = MaterialTheme.typography.titleLarge)
                Text(whenIn(meeting, ZoneId.systemDefault(), now), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                val minutes = (meeting - now) / 60_000
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val days = minutes / (24 * 60)
                    val hours = (minutes / 60) % 24
                    CountdownBox(days, if (days == 1L) "day" else "days", Modifier.weight(1f))
                    CountdownBox(hours, if (hours == 1L) "hour" else "hours", Modifier.weight(1f))
                    CountdownBox(minutes % 60, "min", Modifier.weight(1f))
                }
            }
        }
    }
    if (editing) {
        MeetingDialog(together, now, onDismiss = { editing = false }, onSave = { at, label ->
            editing = false
            onSet(at, label)
        })
    }
}

@Composable
private fun CountdownBox(value: Long, unit: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value.toString(), fontFamily = Fredoka, fontSize = 30.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
    }
}

@Composable
private fun MeetingDialog(together: TogetherInfo, now: Long, onDismiss: () -> Unit, onSave: (Long?, String?) -> Unit) {
    var plan by remember { mutableStateOf(together.nextMeetingLabel.orEmpty()) }
    var at by remember { mutableStateOf(together.nextMeetingMs?.takeIf { it > now }) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Next time together") },
        text = {
            Column {
                OutlinedTextField(plan, { plan = it }, label = { Text("What's the plan?") }, placeholder = { Text("Weekend in the mountains") }, singleLine = true)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(at?.let { whenIn(it, ZoneId.systemDefault(), now) } ?: "Pick the date and time")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(at, plan) }, enabled = at != null) { Text("Save") } },
        dismissButton = {
            Row {
                if (together.nextMeetingMs != null) TextButton(onClick = { onSave(null, null) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (picking) {
        DateTimePicker(
            zone = ZoneId.systemDefault(),
            now = now,
            title = "Meeting at",
            initialMs = at,
            onDismiss = { picking = false },
            onPicked = {
                at = it
                picking = false
            },
        )
    }
}

// ---- moods ---------------------------------------------------------------------------

/** How each of you feels, and the last week at a glance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoodCard(moods: List<MoodEntity>, partnerName: String, now: Long, onMood: (String, String?) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    val theirs = moods.firstOrNull { !it.fromMe && now - it.atMs < DAY }
    val mine = moods.firstOrNull { it.fromMe && now - it.atMs < DAY }
    TogetherCard("🌤️", "Moods") {
        if (theirs != null) MoodLine(theirs, Moods.sentence(partnerName, theirs.mood), now)
        if (mine != null) MoodLine(mine, Moods.mySentence(mine.mood), now)
        if (theirs != null || mine != null) Spacer(Modifier.height(10.dp))
        Text("How are you feeling?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Moods.all.forEach { mood ->
                FilterChip(
                    selected = selected == mood.emoji,
                    onClick = { selected = if (selected == mood.emoji) null else mood.emoji },
                    label = { Text("${mood.emoji} ${mood.label}") },
                )
            }
        }
        selected?.let { emoji ->
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(120) },
                placeholder = { Text("Add a few words (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                onMood(emoji, note)
                selected = null
                note = ""
            }) { Text("Share with $partnerName") }
        }
        Spacer(Modifier.height(14.dp))
        WeekStrip(moods, partnerName, now)
    }
}

@Composable
private fun MoodLine(mood: MoodEntity, sentence: String, now: Long) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(mood.mood, fontSize = 30.sp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(sentence.removeSuffix(" ${mood.mood}"), style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(mood.note?.let { "“$it”" }, Format.relative(mood.atMs, now)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = if (mood.note != null) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The last seven days: their mood on top, mine underneath. */
@Composable
private fun WeekStrip(moods: List<MoodEntity>, partnerName: String, now: Long) {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    fun on(day: LocalDate, fromMe: Boolean) =
        moods.firstOrNull { it.fromMe == fromMe && Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() == day }?.mood
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(" ", style = MaterialTheme.typography.labelSmall)
                Text(partnerName.take(8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.height(24.dp).padding(top = 4.dp))
                Text("You", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.height(24.dp).padding(top = 4.dp))
            }
            days.forEach { day ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (day == today) "Today" else shortDay(day),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    MoodDot(on(day, false))
                    MoodDot(on(day, true))
                }
            }
        }
    }
}

@Composable
private fun MoodDot(emoji: String?) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (emoji != null) {
            Text(emoji, fontSize = 18.sp)
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

// ---- letters -------------------------------------------------------------------------

@Composable
fun LettersCard(letters: List<MessageEntity>, partnerName: String, now: Long, onWrite: () -> Unit, onOpen: (MessageEntity) -> Unit) {
    val write: (@Composable () -> Unit)? = if (letters.isNotEmpty()) {
        @Composable { TextButton(onClick = onWrite) { Text("Write") } }
    } else {
        null
    }
    TogetherCard("💌", "Love letters", action = write) {
        if (letters.isEmpty()) {
            Text(
                "Write $partnerName something to keep, in your own words, on paper you pick. Seal it for a special day if you like.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onWrite) { Text("Write a letter") }
        } else {
            letters.take(5).forEach { LetterRow(it, partnerName, now) { onOpen(it) } }
        }
    }
}

// ---- safe and sound ------------------------------------------------------------------

private val checkIns = listOf(
    CheckInKind.HOME_SAFE to ("🏠" to "Home safe"),
    CheckInKind.LEAVING to ("🚶" to "Leaving now"),
    CheckInKind.ARRIVED to ("📍" to "Arrived"),
)

/** One tap to say you're home, and live location for the way there. */
@Composable
fun SafeCard(
    partnerName: String,
    theirs: SharedLocation?,
    myUntil: Long?,
    now: Long,
    onCheckIn: (CheckInKind) -> Unit,
    onLocation: () -> Unit,
) {
    val context = LocalContext.current
    TogetherCard("🏡", "Safe and sound") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            checkIns.forEach { (kind, look) ->
                Surface(
                    onClick = {
                        onCheckIn(kind)
                        Toast.makeText(context, "Sent “${look.second}” to $partnerName", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(look.first, fontSize = 26.sp)
                        Text(look.second, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val theirLive = theirs?.live(now) == true
        val mine = myUntil?.takeIf { it > now }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onLocation).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background((if (theirLive || mine != null) Mami.colors.good else MaterialTheme.colorScheme.primary).copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = if (theirLive || mine != null) Mami.colors.good else MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    when {
                        theirLive -> "$partnerName is sharing live location"
                        mine != null -> "You're sharing your live location"
                        else -> "Share live location"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when {
                        theirLive && mine != null -> "Both of you, until ${Format.time(minOf(theirs!!.untilMs, mine))}"
                        theirLive -> "Until ${Format.time(theirs!!.untilMs)} · tap to see where"
                        mine != null -> "Until ${Format.time(mine)}"
                        else -> "For 15 minutes, an hour or 8 hours"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- scheduled -----------------------------------------------------------------------

@Composable
fun ScheduledCard(scheduled: List<MessageEntity>, partnerName: String, partnerZone: ZoneId?, now: Long, onCancel: (String) -> Unit) {
    TogetherCard("🕒", "Scheduled messages") {
        if (scheduled.isEmpty()) {
            Text(
                "Write it now, have it arrive at the perfect moment on $partnerName's clock. Tap 🕒 while typing, or hold the send button.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ScheduledList(scheduled, partnerName, partnerZone, now, onCancel)
        }
    }
}

// ---- pause sharing ---------------------------------------------------------------------

/** A little privacy, said honestly: the partner sees "sharing paused", not a dead phone. */
@Composable
fun PauseCard(paused: Long?, partnerName: String, now: Long, onPause: (Long?) -> Unit) {
    val active = paused?.takeIf { it > now }
    TogetherCard("🌙", "Pause sharing") {
        if (active != null) {
            Surface(shape = RoundedCornerShape(16.dp), color = Mami.colors.warn.copy(alpha = 0.14f), border = BorderStroke(1.dp, Mami.colors.warn.copy(alpha = 0.3f))) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Mami.colors.warn)
                    Text(
                        if (pausedIndefinitely(active)) "Paused until you turn it back on" else "Paused until ${untilIn(active, ZoneId.systemDefault(), now)}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "$partnerName sees “Sharing paused” instead of your battery, network and local time, and you don't see theirs. Live location is off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { onPause(null) }) { Text("Resume sharing") }
        } else {
            Text(
                "Need a little privacy? $partnerName will see that sharing is paused, so silence never looks like a dead phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            val zone = ZoneId.systemDefault()
            val tomorrow = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = false, onClick = { onPause(now + HOUR) }, label = { Text("1 hour") })
                FilterChip(selected = false, onClick = { onPause(tomorrow) }, label = { Text("Until 8 AM") })
                FilterChip(selected = false, onClick = { onPause(PAUSED_INDEFINITELY) }, label = { Text("Until I resume") })
            }
        }
    }
}

// ---- in the chat ---------------------------------------------------------------------

/** "Maya is home safe" in the chat. */
@Composable
fun CheckInCard(message: MessageEntity, partnerName: String, onClick: () -> Unit) {
    val emoji = Notifications.checkInText(message.body).substringBefore(' ')
    val text = when (message.body) {
        CheckInKind.LEAVING.name -> if (message.fromMe) "You told $partnerName you're leaving" else "$partnerName is leaving now"
        CheckInKind.ARRIVED.name -> if (message.fromMe) "You told $partnerName you arrived" else "$partnerName has arrived"
        else -> if (message.fromMe) "You told $partnerName you're home safe" else "$partnerName is home safe"
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(onClick = onClick, shape = CircleShape, color = Mami.colors.good.copy(alpha = 0.12f), border = BorderStroke(1.dp, Mami.colors.good.copy(alpha = 0.3f))) {
            Row(Modifier.padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Mami.colors.good.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text(emoji, fontSize = 20.sp)
                }
                Column(Modifier.padding(start = 10.dp)) {
                    Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Format.time(message.sentAtMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

private const val HOUR = 60 * 60_000L
private const val DAY = 24 * HOUR
