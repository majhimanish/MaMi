package app.mami.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mami.core.DeviceStatus
import app.mami.core.NetworkKind
import app.mami.core.NudgeKind
import app.mami.core.RingerMode
import app.mami.core.ShareKind
import app.mami.data.PresenceDto
import app.mami.ui.Format
import app.mami.ui.components.Avatar
import app.mami.ui.components.BatteryGauge
import app.mami.ui.components.MiniClock
import app.mami.ui.components.SignalBars
import app.mami.ui.theme.Mami

/** Everything MaMi knows about how the partner is doing, so nobody has to guess. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartnerSheet(
    name: String,
    email: String,
    status: DeviceStatus?,
    rawStatus: DeviceStatus?,
    statusReceivedAt: Long?,
    presence: PresenceDto?,
    hints: List<Hint>,
    myShares: Set<ShareKind>,
    now: Long,
    onNudge: (NudgeKind) -> Unit,
    onDismiss: () -> Unit,
    onCall: ((video: Boolean) -> Unit)? = null,
    onMedia: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Hero(name, email, status, presence, now)
            Column(Modifier.padding(horizontal = 18.dp)) {
                if (onCall != null || onMedia != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        onCall?.let { call ->
                            QuickAction(Icons.Filled.Call, "Call", Modifier.weight(1f)) { call(false) }
                            QuickAction(Icons.Filled.Videocam, "Video", Modifier.weight(1f)) { call(true) }
                        }
                        onMedia?.let { QuickAction(Icons.Filled.PermMedia, "Media", Modifier.weight(1f), it) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                hints.filter { it.text != "Online now" }.forEach { hint -> HintCard(hint) }

                Spacer(Modifier.height(8.dp))
                if (status == null) {
                    Text(
                        "$name's phone hasn't shared anything yet. It will once MaMi has run on their phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BatteryTile(status, Modifier.weight(1f))
                        NetworkTile(status, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SoundTile(status, Modifier.weight(1f))
                        TimeTile(status, now, Modifier.weight(1f))
                    }
                    if (statusReceivedAt != null && statusReceivedAt > 0) {
                        Text(
                            "Updated ${Format.relative(status.capturedAtMs.takeIf { it > 0 } ?: statusReceivedAt, now)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    val hidden = hiddenBecauseNotShared(rawStatus, myShares)
                    if (hidden.isNotEmpty()) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) {
                            Text(
                                "🔁 $name shares their ${hidden.joinToString(", ")}. Share yours in Settings to see it — sharing is always two-way.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                Text("Send a little something", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        NudgeKind.THINKING_OF_YOU to ("💗" to "Thinking"),
                        NudgeKind.HUG to ("🤗" to "Hug"),
                        NudgeKind.KISS to ("😘" to "Kiss"),
                        NudgeKind.MISS_YOU to ("🥺" to "Miss you"),
                    ).forEach { (kind, look) -> NudgeButton(look.first, look.second) { onNudge(kind) } }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun Hero(name: String, email: String, status: DeviceStatus?, presence: PresenceDto?, now: Long) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Mami.colors.brush)
            .padding(top = 14.dp, bottom = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.6f)),
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.92f))
                    .padding(4.dp),
            ) {
                Avatar(name, size = 92.dp, battery = status?.batteryPercent, charging = status?.charging == true, online = presence?.online == true)
            }
            Spacer(Modifier.height(10.dp))
            Text(name, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(
                when {
                    presence?.online == true -> "● Online now"
                    presence?.lastSeenMs != null -> "Last seen ${Format.relative(presence.lastSeenMs, now)}"
                    else -> email
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

@Composable
private fun HintCard(hint: Hint) {
    val tint = if (hint.urgent) Mami.colors.bad else MaterialTheme.colorScheme.primary
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = tint.copy(alpha = 0.1f),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(hint.icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(hint.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun Tile(title: String, value: String, detail: String?, modifier: Modifier, visual: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(76.dp), contentAlignment = Alignment.Center) { visual() }
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun BatteryTile(status: DeviceStatus, modifier: Modifier) {
    val level = status.batteryPercent
    val charging = status.charging == true
    Tile(
        "Battery",
        when {
            level == null -> "Not shared"
            charging -> "Charging"
            level <= 15 -> "Running low"
            else -> "Not charging"
        },
        null,
        modifier,
    ) { BatteryGauge(level, charging) }
}

@Composable
private fun NetworkTile(status: DeviceStatus, modifier: Modifier) {
    val value = when (status.network) {
        NetworkKind.WIFI -> "Wi-Fi"
        NetworkKind.CELLULAR -> "Mobile data"
        NetworkKind.ETHERNET -> "Cable"
        NetworkKind.OFFLINE -> "Offline"
        NetworkKind.OTHER -> "Online"
        null -> "Not shared"
    }
    val signal = when (status.signalLevel) {
        null -> null
        0, 1 -> "Weak signal"
        2 -> "Okay signal"
        3 -> "Good signal"
        else -> "Great signal"
    }
    Tile("Connection", value, signal, modifier) {
        SignalBars(status.signalLevel, modifier = Modifier.graphicsLayer { scaleX = 2.4f; scaleY = 2.4f })
    }
}

@Composable
private fun SoundTile(status: DeviceStatus, modifier: Modifier) {
    val dnd = status.doNotDisturb == true
    val (icon, value) = when {
        dnd -> Icons.Filled.DoNotDisturbOn to "Do Not Disturb"
        status.ringer == RingerMode.SILENT -> Icons.Filled.NotificationsOff to "Silent"
        status.ringer == RingerMode.VIBRATE -> Icons.Filled.Vibration to "Vibrate"
        status.ringer == RingerMode.NORMAL -> Icons.AutoMirrored.Filled.VolumeUp to "Ringer on"
        else -> Icons.AutoMirrored.Filled.VolumeUp to "Not shared"
    }
    val quiet = dnd || status.ringer == RingerMode.SILENT || status.ringer == RingerMode.VIBRATE
    val tint = if (quiet) Mami.colors.warn else MaterialTheme.colorScheme.primary
    Tile("Sound", value, if (quiet) "Calls may go unnoticed" else null, modifier) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun TimeTile(status: DeviceStatus, now: Long, modifier: Modifier) {
    val hm = partnerLocalHourMinute(status, now)
    Tile(
        "Their time",
        hm?.let { localTime(it.first, it.second) } ?: "Not shared",
        status.timezone?.substringAfterLast('/')?.replace('_', ' '),
        modifier,
    ) {
        if (hm != null) MiniClock(hm.first, hm.second) else Text("🌍", fontSize = 40.sp)
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun NudgeButton(emoji: String, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 1.25f else 1f, spring(dampingRatio = 0.35f), label = "nudge")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(62.dp).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        ) {
            Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 28.sp) }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Things the partner shares that I can't see because I don't share them myself. */
private fun hiddenBecauseNotShared(raw: DeviceStatus?, myShares: Set<ShareKind>): List<String> {
    if (raw == null) return emptyList()
    return listOfNotNull(
        "battery".takeIf { ShareKind.BATTERY in raw.shares && ShareKind.BATTERY !in myShares },
        "connection".takeIf { ShareKind.NETWORK in raw.shares && ShareKind.NETWORK !in myShares },
        "sound settings".takeIf { ShareKind.RINGER in raw.shares && ShareKind.RINGER !in myShares },
        "local time".takeIf { ShareKind.LOCAL_TIME in raw.shares && ShareKind.LOCAL_TIME !in myShares },
    )
}
