package app.mami.calls

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.mami.MamiApp
import app.mami.sync.Notifications

/**
 * Keeps a call alive (microphone and camera) while MaMi isn't on screen,
 * with the ongoing-call notification Android requires.
 */
class CallService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        val graph = MamiApp.graph(this)
        val notification = graph.notifications.ongoingCall(this, graph.messenger.partnerName, video, connectedAtMs = null)
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_ONGOING_CALL, notification, types)
        } catch (e: Exception) {
            // Android refuses when the app wasn't allowed to start it right now; the call still works while on screen.
            Log.w("MaMi.Call", "could not keep the call running in the background", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val EXTRA_VIDEO = "video"

        fun start(context: Context, video: Boolean) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).putExtra(EXTRA_VIDEO, video))
            } catch (e: Exception) {
                Log.w("MaMi.Call", "could not start the call service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}

/** "Decline" and "Hang up" from the call notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calls = MamiApp.graph(context).calls
        when (intent.action) {
            ACTION_DECLINE -> calls.decline()
            ACTION_HANG_UP -> calls.hangUp()
        }
    }

    companion object {
        const val ACTION_DECLINE = "app.mami.call.DECLINE"
        const val ACTION_HANG_UP = "app.mami.call.HANG_UP"
    }
}

/** Intents that open MaMi for a call. */
object CallIntents {
    /** Open the incoming-call screen. */
    const val ACTION_SHOW = "app.mami.call.SHOW"

    /** "Answer" was tapped on the notification. */
    const val ACTION_ANSWER = "app.mami.call.ANSWER"

    /** Set when "Answer" opened the app; the call screen answers once it has the permissions it needs. */
    val answerRequested = kotlinx.coroutines.flow.MutableStateFlow(false)
}
