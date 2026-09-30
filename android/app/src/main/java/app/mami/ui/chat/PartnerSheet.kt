package app.mami.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.mami.core.DeviceStatus
import app.mami.core.NetworkKind
import app.mami.core.NudgeKind
import app.mami.core.RingerMode
import app.mami.core.ShareKind
import app.mami.data.PresenceDto
import app.mami.ui.Format

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
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, online = presence?.online == true, size = 56)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall)
                    Text(email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            hints.forEach { hint ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        hint.icon,
                        contentDescription = null,
                        tint = if (hint.urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(hint.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            if (status == null) {
                Text(
                    "$name's phone hasn't shared anything yet. It will once the app has run on their phone.",
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
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Updated ${Format.relative(status.capturedAtMs.takeIf { it > 0 } ?: statusReceivedAt, now)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val hidden = hiddenBecauseNotShared(rawStatus, myShares)
                if (hidden.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "$name shares their ${hidden.joinToString(", ")}. Turn on sharing yours in Settings to see it — sharing is always two-way.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Send a little something", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    NudgeKind.THINKING_OF_YOU to "💗",
                    NudgeKind.HUG to "🤗",
                    NudgeKind.KISS to "😘",
                    NudgeKind.MISS_YOU to "🥺",
                ).forEach { (kind, emoji) ->
                    AssistChip(onClick = { onNudge(kind) }, label = { Text(emoji, style = MaterialTheme.typography.titleLarge) })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, title: String, value: String, detail: String?, modifier: Modifier) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BatteryTile(status: DeviceStatus, modifier: Modifier) {
    val level = status.batteryPercent
    val charging = status.charging == true
    val icon = when {
        level == null -> BatteryHidden
        charging -> Icons.Filled.BatteryChargingFull
        level <= 15 -> Icons.Filled.BatteryAlert
        level <= 40 -> Icons.Filled.Battery2Bar
        level <= 80 -> Icons.Filled.Battery5Bar
        else -> Icons.Filled.BatteryFull
    }
    Tile(
        icon,
        "Battery",
        level?.let { "$it%" } ?: "—",
        when {
            level == null -> "Not shared"
            charging -> "Charging"
            status.charging == false -> "Not charging"
            else -> null
        },
        modifier,
    )
}

private val BatteryHidden = Icons.Filled.BatteryFull

@Composable
private fun NetworkTile(status: DeviceStatus, modifier: Modifier) {
    val (icon, value) = when (status.network) {
        NetworkKind.WIFI -> Icons.Filled.Wifi to "Wi-Fi"
        NetworkKind.CELLULAR -> Icons.Filled.SignalCellular4Bar to "Mobile data"
        NetworkKind.ETHERNET -> Icons.Filled.SettingsEthernet to "Cable"
        NetworkKind.OFFLINE -> Icons.Filled.WifiOff to "Offline"
        NetworkKind.OTHER -> Icons.Filled.Wifi to "Connected"
        null -> Icons.Filled.Wifi to "—"
    }
    val bars = status.signalLevel?.let { level -> "Signal " + "▮".repeat(level.coerceIn(0, 4)) + "▯".repeat(4 - level.coerceIn(0, 4)) }
    Tile(icon, "Connection", value, if (status.network == null) "Not shared" else bars, modifier)
}

@Composable
private fun SoundTile(status: DeviceStatus, modifier: Modifier) {
    val dnd = status.doNotDisturb == true
    val (icon, value) = when {
        dnd -> Icons.Filled.DoNotDisturbOn to "Do Not Disturb"
        status.ringer == RingerMode.SILENT -> Icons.AutoMirrored.Filled.VolumeOff to "Silent"
        status.ringer == RingerMode.VIBRATE -> Icons.Filled.Vibration to "Vibrate"
        status.ringer == RingerMode.NORMAL -> Icons.AutoMirrored.Filled.VolumeUp to "Ringer on"
        else -> Icons.AutoMirrored.Filled.VolumeUp to "—"
    }
    Tile(icon, "Sound", value, if (status.ringer == null && status.doNotDisturb == null) "Not shared" else null, modifier)
}

@Composable
private fun TimeTile(status: DeviceStatus, now: Long, modifier: Modifier) {
    val time = partnerLocalTime(status, now)
    Tile(
        Icons.Filled.Public,
        "Their time",
        time ?: "—",
        status.timezone?.substringAfterLast('/')?.replace('_', ' ') ?: "Not shared",
        modifier,
    )
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
