package app.mami.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import app.mami.MainActivity
import app.mami.R
import app.mami.data.db.MediaType
import app.mami.data.db.MessageEntity
import app.mami.data.db.MessageKind

/** Notification channels and the notifications MaMi shows. */
class Notifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Messages from your partner"
                },
                NotificationChannel(CHANNEL_NUDGES, "Hugs and taps", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "\"Thinking of you\" taps, hugs and kisses"
                    vibrationPattern = longArrayOf(0, 120, 90, 120, 90, 240)
                    enableVibration(true)
                },
                NotificationChannel(CHANNEL_ALERTS, "Partner's phone", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When your partner's battery is about to run out"
                },
            ),
        )
    }

    /** Shows the unread conversation as one notification. */
    @SuppressLint("MissingPermission") // checked by allowed()
    fun showConversation(partnerName: String, unread: List<MessageEntity>, hideText: Boolean) {
        if (unread.isEmpty() || !allowed()) return
        val partner = Person.Builder().setName(partnerName.ifBlank { "Your partner" }).build()
        val me = Person.Builder().setName("You").build()
        val latest = unread.first()
        val channel = when (latest.kind) {
            MessageKind.NUDGE -> CHANNEL_NUDGES
            MessageKind.ALERT -> CHANNEL_ALERTS
            else -> CHANNEL_MESSAGES
        }
        val style = NotificationCompat.MessagingStyle(me)
        unread.reversed().forEach { message ->
            val text = if (hideText) "New message" else preview(message, partnerName)
            style.addMessage(text, message.sortAtMs, partner)
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openApp())
            .build()
        manager.notify(ID_CONVERSATION, notification)
    }

    fun clearConversation() = manager.cancel(ID_CONVERSATION)

    fun clearAll() = manager.cancelAll()

    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_NUDGES = "nudges"
        const val CHANNEL_ALERTS = "alerts"
        private const val ID_CONVERSATION = 1

        fun preview(message: MessageEntity, partnerName: String): String = when (message.kind) {
            MessageKind.NUDGE -> nudgeText(message.body)
            MessageKind.ALERT -> "🔋 ${partnerName.ifBlank { "Your partner" }}'s phone is at ${message.batteryPercent ?: "a few"}% and may switch off soon"
            MessageKind.MEDIA -> mediaLabel(message) + message.body.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            else -> message.body
        }

        /** "📷 Photo", "🎤 Voice message (0:12)" and so on. */
        fun mediaLabel(message: MessageEntity): String = when (message.mediaKind) {
            MediaType.PHOTO -> if (message.viewOnce) "📷 Photo · view once" else "📷 Photo"
            MediaType.VIDEO -> if (message.viewOnce) "🎥 Video · view once" else "🎥 Video"
            MediaType.VOICE -> "🎤 Voice message" + (message.mediaDurationMs?.let { " (${duration(it)})" }.orEmpty())
            else -> "📄 " + (message.mediaName ?: "File")
        }

        fun duration(ms: Long): String {
            val seconds = (ms + 500) / 1000
            return if (seconds >= 3600) {
                "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
            } else {
                "%d:%02d".format(seconds / 60, seconds % 60)
            }
        }

        fun nudgeText(kind: String): String = when (kind) {
            "HUG" -> "🤗 sent you a hug"
            "KISS" -> "😘 sent you a kiss"
            "MISS_YOU" -> "🥺 misses you"
            else -> "💗 is thinking of you"
        }
    }
}
