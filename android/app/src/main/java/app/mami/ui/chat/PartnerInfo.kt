package app.mami.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.ui.graphics.vector.ImageVector
import app.mami.core.DeviceStatus
import app.mami.core.Insight
import app.mami.core.NetworkKind
import app.mami.core.Presence
import app.mami.core.RingerMode
import app.mami.core.ShareKind
import app.mami.core.partnerInsights
import app.mami.core.visibleStatus
import app.mami.data.PresenceDto
import app.mami.ui.Format
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One plain-language hint about the partner, ready to show. */
data class Hint(val icon: ImageVector, val text: String, val urgent: Boolean = false)

/** The partner's status with everything I don't share back removed. */
fun visiblePartnerStatus(status: DeviceStatus?, myShares: Set<ShareKind>): DeviceStatus? =
    status?.let { visibleStatus(it, myShares.toList()) }

/** Why might they be quiet? Most important first. */
fun partnerHints(status: DeviceStatus?, presence: PresenceDto?, name: String, now: Long): List<Hint> {
    val insights = partnerInsights(status, Presence(presence?.online == true, presence?.lastSeenMs), now)
    return insights.map { hint(it, name, now) }
}

private fun hint(insight: Insight, name: String, now: Long): Hint = when (insight) {
    is Insight.OnlineNow -> Hint(Icons.Filled.Circle, "Online now")
    is Insight.Busy -> Hint(
        Icons.Filled.EmojiEmotions,
        "${insight.emoji} ${insight.label}" + (insight.untilMs?.let { " until ${Format.time(it)}" } ?: ""),
    )
    is Insight.PhoneMayHaveDied -> Hint(
        Icons.Filled.PowerSettingsNew,
        "Phone may have switched off — battery was ${insight.batteryPercent}% at ${Format.time(insight.sinceMs)}",
        urgent = true,
    )
    is Insight.BatteryLow -> Hint(
        Icons.Filled.BatteryAlert,
        "Battery low: ${insight.batteryPercent}%" + if (insight.charging) " (charging)" else "",
        urgent = !insight.charging,
    )
    is Insight.ProbablyAsleep -> Hint(
        Icons.Filled.Bedtime,
        "It's ${localTime(insight.localHour, insight.localMinute)} for $name — probably asleep",
    )
    is Insight.Silenced -> Hint(
        if (insight.doNotDisturb) Icons.Filled.DoNotDisturbOn else Icons.Filled.Vibration,
        when {
            insight.doNotDisturb -> "Do Not Disturb is on"
            insight.ringer == RingerMode.SILENT -> "Phone is on silent"
            else -> "Phone is on vibrate"
        },
    )
    is Insight.WeakSignal -> Hint(
        Icons.Filled.SignalCellularAlt,
        if (insight.network == NetworkKind.WIFI) "Weak Wi-Fi signal" else "Weak mobile signal",
    )
    is Insight.Offline -> Hint(Icons.Filled.WifiOff, "No internet connection", urgent = true)
    is Insight.LastSeen -> Hint(Icons.Filled.Schedule, "Last seen ${Format.relative(insight.atMs, now)}")
}

fun localTime(hour: Int, minute: Int): String =
    LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** The partner's local wall-clock time right now, if they share it. */
fun partnerLocalTime(status: DeviceStatus?, now: Long): String? {
    val offset = status?.utcOffsetMinutes ?: return null
    val minutes = Math.floorMod(now / 60_000 + offset, 24L * 60).toInt()
    return localTime(minutes / 60, minutes % 60)
}
