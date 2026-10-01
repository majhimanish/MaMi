package app.mami.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import app.mami.MamiApp
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Notices when the phone starts and stops travelling in a vehicle, using the
 * phone's own low-power activity recognition, so the partner can see
 * "🚗 Driving" without anyone typing it.
 */
object Driving {
    fun permitted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Returns false if it couldn't be switched on (no permission, no Google Play services). */
    @SuppressLint("MissingPermission") // checked by permitted()
    fun enable(context: Context): Boolean {
        if (!permitted(context)) return false
        val transitions = listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
            ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(it).build()
        }
        return try {
            ActivityRecognition.getClient(context).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), intent(context))
            true
        } catch (e: Exception) {
            Log.w("MaMi.Driving", "activity recognition unavailable", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disable(context: Context) {
        runCatching { ActivityRecognition.getClient(context).removeActivityTransitionUpdates(intent(context)) }
    }

    // Google Play services fills in the result, so the intent has to be mutable.
    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        7,
        Intent(context, DrivingReceiver::class.java).setAction("app.mami.DRIVING"),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
    )
}

class DrivingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val last = ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull { it.activityType == DetectedActivity.IN_VEHICLE } ?: return
        MamiApp.graph(context).messenger.onDriving(last.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER)
    }
}
