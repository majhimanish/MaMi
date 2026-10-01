package app.mami.ui

import android.text.format.DateUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object Format {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun time(ms: Long): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(ms).atZone(zone))

    /** With seconds, for the "exactly when" details. */
    fun preciseDateTime(ms: Long): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM).format(Instant.ofEpochMilli(ms).atZone(zone))

    fun day(ms: Long, now: Long): String {
        val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date)
        }
    }

    fun relative(ms: Long, now: Long): String =
        if (now - ms < DateUtils.MINUTE_IN_MILLIS) {
            "just now"
        } else {
            DateUtils.getRelativeTimeSpanString(ms, now, DateUtils.MINUTE_IN_MILLIS).toString()
        }

    fun sameDay(a: Long, b: Long): Boolean =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}
