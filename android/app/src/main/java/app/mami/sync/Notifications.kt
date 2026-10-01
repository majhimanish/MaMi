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
import app.mami.calls.CallActionReceiver
import app.mami.calls.CallIntents
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
                NotificationChannel(CHANNEL_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Voice and video calls from your partner"
                    // MaMi plays the ringtone itself, so it can loop and stop the moment you answer.
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(CHANNEL_ONGOING_CALL, "Ongoing call", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while you're on a call"
                    setSound(null, null)
                },
            ),
        )
    }

    // ---- calls ----

    /** Rings on the lock screen (full screen) or as a heads-up, with Answer and Decline. */
    @SuppressLint("MissingPermission") // checked by allowed()
    fun showIncomingCall(partnerName: String, video: Boolean) {
        if (!allowed()) return
        val person = Person.Builder().setName(partnerName.ifBlank { "Your partner" }).setImportant(true).build()
        val show = callIntent(CallIntents.ACTION_SHOW, 10)
        val answer = callIntent(CallIntents.ACTION_ANSWER, 11)
        val decline = PendingIntent.getBroadcast(
            context,
            12,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(partnerName)
            .setContentText(if (video) "Incoming video call" else "Incoming voice call")
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer).setIsVideo(video))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(show)
            .setFullScreenIntent(show, true)
            .build()
        manager.notify(ID_INCOMING_CALL, notification)
    }

    fun cancelIncomingCall() = manager.cancel(ID_INCOMING_CALL)

    @SuppressLint("MissingPermission") // checked by allowed()
    fun showMissedCall(partnerName: String, video: Boolean) {
        if (!allowed()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(if (video) "Missed video call" else "Missed voice call")
            .setContentText("${partnerName.ifBlank { "Your partner" }} tried to call you")
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager.notify(ID_MISSED_CALL, notification)
    }

    /** The notification of the call foreground service: who, how long, and Hang up. */
    fun ongoingCall(context: Context, partnerName: String, video: Boolean, connectedAtMs: Long?): android.app.Notification {
        val person = Person.Builder().setName(partnerName.ifBlank { "Your partner" }).setImportant(true).build()
        val hangUp = PendingIntent.getBroadcast(
            context,
            13,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ONGOING_CALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(partnerName)
            .setContentText(if (video) "Video call" else "Voice call")
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp).setIsVideo(video))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setUsesChronometer(connectedAtMs != null)
            .setWhen(connectedAtMs ?: System.currentTimeMillis())
            .setShowWhen(connectedAtMs != null)
            .setContentIntent(callIntent(CallIntents.ACTION_SHOW, 14))
            .build()
    }

    /** Starts the call timer in the notification once the call connects. */
    @SuppressLint("MissingPermission") // checked by allowed()
    fun updateOngoingCall(context: Context, partnerName: String, video: Boolean, connectedAtMs: Long?) {
        if (!allowed()) return
        manager.notify(ID_ONGOING_CALL, ongoingCall(context, partnerName, video, connectedAtMs))
    }

    private fun callIntent(action: String, code: Int): PendingIntent = PendingIntent.getActivity(
        context,
        code,
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

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
        const val CHANNEL_CALLS = "calls"
        const val CHANNEL_ONGOING_CALL = "ongoing_call"
        private const val ID_CONVERSATION = 1
        private const val ID_INCOMING_CALL = 2
        private const val ID_MISSED_CALL = 3
        const val ID_ONGOING_CALL = 4

        fun preview(message: MessageEntity, partnerName: String): String = when (message.kind) {
            MessageKind.NUDGE -> nudgeText(message.body)
            MessageKind.ALERT -> "🔋 ${partnerName.ifBlank { "Your partner" }}'s phone is at ${message.batteryPercent ?: "a few"}% and may switch off soon"
            MessageKind.MEDIA -> mediaLabel(message) + message.body.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            MessageKind.CALL -> if (message.mediaKind == MediaType.VIDEO) "📹 Missed video call" else "📞 Missed voice call"
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
