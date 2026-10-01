package app.mami.ui.together

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mami.core.DeviceStatus
import app.mami.data.db.MessageEntity
import app.mami.ui.theme.Mami
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** The partner's time zone, if they share their local time with me (and I with them). */
fun partnerZone(status: DeviceStatus?): ZoneId? {
    if (status == null) return null
    status.timezone?.let { name -> runCatching { ZoneId.of(name) }.getOrNull() }?.let { return it }
    return status.utcOffsetMinutes?.let { ZoneOffset.ofTotalSeconds(it * 60) }
}

/** Whether two zones show a different clock right now. */
fun differentClock(a: ZoneId, b: ZoneId, now: Long): Boolean {
    val instant = Instant.ofEpochMilli(now)
    return a.rules.getOffset(instant) != b.rules.getOffset(instant)
}

/** "Kathmandu" for Asia/Kathmandu, "UTC+05:45" for a bare offset. */
fun zoneName(zone: ZoneId): String = when (zone) {
    is ZoneOffset -> "UTC$zone"
    else -> zone.id.substringAfterLast('/').replace('_', ' ')
}

/** "7:12 AM" on the clock of [zone]. */
fun timeIn(ms: Long, zone: ZoneId): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(ms).atZone(zone))

/** "9:00 PM", "Tomorrow, 8:00 AM" or "Sat 4 Oct, 8:00 AM", on the clock of [zone]. */
fun whenIn(ms: Long, zone: ZoneId, now: Long): String {
    val at = Instant.ofEpochMilli(ms).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at)
    return when (at.toLocalDate()) {
        today -> "Today, $time"
        today.plusDays(1) -> "Tomorrow, $time"
        else -> DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(at) + ", $time"
    }
}

/** "In 2 days 3 hrs", "in 45 min". */
fun countdown(ms: Long, now: Long): String {
    val minutes = ((ms - now).coerceAtLeast(0) + 59_999) / 60_000
    val days = minutes / (24 * 60)
    val hours = (minutes / 60) % 24
    return when {
        days > 0 -> "$days ${if (days == 1L) "day" else "days"}" + if (hours > 0) " $hours hr${if (hours == 1L) "" else "s"}" else ""
        hours > 0 -> "$hours hr${if (hours == 1L) "" else "s"} ${minutes % 60} min"
        else -> "$minutes min"
    }
}

data class SchedulePreset(val label: String, val atMs: Long)

/** Good moments to arrive, on the partner's clock. */
fun schedulePresets(now: Long, zone: ZoneId): List<SchedulePreset> {
    val local = Instant.ofEpochMilli(now).atZone(zone)
    fun at(date: LocalDate, hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    val today = local.toLocalDate()
    val soon = local.plusMinutes(20)
    return buildList {
        add(SchedulePreset("In an hour", now + 60 * 60_000))
        if (local.hour < 5) add(SchedulePreset("When they wake up", at(today, 7, 30)))
        if (ZonedDateTime.of(today, LocalTime.of(21, 0), zone).isAfter(soon)) add(SchedulePreset("Tonight", at(today, 21)))
        if (local.hour >= 5) add(SchedulePreset("Tomorrow morning", at(today.plusDays(1), 7, 30)))
        add(SchedulePreset("Tomorrow at lunch", at(today.plusDays(1), 12, 30)))
    }
}

/**
 * Picks when a message should appear on the partner's phone. Times are on
 * their clock when they share it, with mine alongside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSheet(
    text: String,
    partnerName: String,
    partnerZone: ZoneId?,
    now: Long,
    onDismiss: () -> Unit,
    onSchedule: (Long) -> Unit,
) {
    val mine = ZoneId.systemDefault()
    val zone = partnerZone ?: mine
    val twoClocks = partnerZone != null && differentClock(partnerZone, mine, now)
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Schedule message", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "It appears on $partnerName's phone at the time you pick. Until then it's a surprise.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Surface(shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    if (twoClocks) {
                        "On $partnerName's clock · ${zoneName(zone)}, ${DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(now).atZone(zone))} there now"
                    } else {
                        "On your clock"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            schedulePresets(now, zone).forEach { preset ->
                ScheduleRow(
                    title = preset.label,
                    time = whenIn(preset.atMs, zone, now),
                    yours = if (twoClocks) whenIn(preset.atMs, mine, now) + " for you" else null,
                    onClick = { onSchedule(preset.atMs) },
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            Row(
                Modifier.fillMaxWidth().clickable { picking = true }.padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.EditCalendar, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Pick a date and time", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 14.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (picking) {
        DateTimePicker(
            zone = zone,
            now = now,
            title = if (twoClocks) "Time for $partnerName (${zoneName(zone)})" else "Time",
            onDismiss = { picking = false },
            onPicked = {
                picking = false
                onSchedule(it)
            },
        )
    }
}

@Composable
private fun ScheduleRow(title: String, time: String, yours: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (yours != null) {
            Text(yours, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/** A date, then a time, on the clock of [zone]. Only future moments are allowed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePicker(
    zone: ZoneId,
    now: Long,
    title: String,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit,
    initialMs: Long? = null,
    pickTime: Boolean = true,
    allowPast: Boolean = false,
) {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val initial = initialMs?.let { Instant.ofEpochMilli(it).atZone(zone) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = (initial?.toLocalDate() ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                allowPast || !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
        },
    )
    val picked = date
    if (picked == null) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    val chosen = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: return@TextButton
                    if (pickTime) date = chosen else onPicked(chosen.atStartOfDay(zone).toInstant().toEpochMilli())
                }) { Text(if (pickTime) "Next" else "OK") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) {
            DatePicker(dateState)
        }
    } else {
        val start = initial?.toLocalTime() ?: Instant.ofEpochMilli(now).atZone(zone).toLocalTime().plusHours(1).withMinute(0)
        val timeState = rememberTimePickerState(start.hour, start.minute)
        val chosen = picked.atTime(timeState.hour, timeState.minute).atZone(zone).toInstant().toEpochMilli()
        val valid = allowPast || chosen > now + 60_000
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(timeState)
                    if (!valid) {
                        Text("Pick a time in the future", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onPicked(chosen) }, enabled = valid) { Text("OK") } },
            dismissButton = { TextButton(onClick = { date = null }) { Text("Back") } },
        )
    }
}

/** "1 scheduled message · Tomorrow, 8:00 AM" above the chat; tap to see or cancel them. */
@Composable
fun ScheduledBar(scheduled: List<MessageEntity>, zone: ZoneId, now: Long, onClick: () -> Unit) {
    val next = scheduled.minOfOrNull { it.scheduledAtMs ?: Long.MAX_VALUE } ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
            Text(
                (if (scheduled.size == 1) "1 scheduled message" else "${scheduled.size} scheduled messages") + " · next ${whenIn(next, zone, now)}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** My messages waiting for their time, each with when it appears and a way to cancel it. */
@Composable
fun ScheduledList(scheduled: List<MessageEntity>, partnerName: String, partnerZone: ZoneId?, now: Long, onCancel: (String) -> Unit) {
    val mine = ZoneId.systemDefault()
    val twoClocks = partnerZone != null && differentClock(partnerZone, mine, now)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        scheduled.sortedBy { it.scheduledAtMs }.forEach { message ->
            val at = message.scheduledAtMs ?: return@forEach
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(message.body, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (twoClocks) {
                                "${whenIn(at, partnerZone!!, now)} for $partnerName · ${whenIn(at, mine, now)} for you"
                            } else {
                                whenIn(at, mine, now)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = Mami.colors.gradient.last(),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { onCancel(message.id) }) { Icon(Icons.Filled.Close, contentDescription = "Cancel scheduled message") }
                }
            }
        }
    }
}

/** The scheduled list in a sheet, from the bar above the chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledSheet(scheduled: List<MessageEntity>, partnerName: String, partnerZone: ZoneId?, now: Long, onCancel: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("Scheduled", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "$partnerName won't see these until their time. Cancel one and it never arrives.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (scheduled.isEmpty()) {
                Text("Nothing scheduled.", style = MaterialTheme.typography.bodyMedium)
            } else {
                ScheduledList(scheduled, partnerName, partnerZone, now, onCancel)
            }
        }
    }
}

/** Day name for the week strip: "Mon". */
fun shortDay(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
