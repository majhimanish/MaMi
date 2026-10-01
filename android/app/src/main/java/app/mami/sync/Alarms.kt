package app.mami.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mami.MamiApp
import kotlinx.coroutines.launch

/**
 * Wakes MaMi at a set time: a scheduled message becomes visible, a pause in
 * sharing ends, live location sharing runs out. Inexact by up to a few
 * minutes when the phone is dozing, which is fine for all three.
 */
object Alarms {
    const val SCHEDULED = 1
    const val PAUSE_ENDS = 2
    const val LOCATION_ENDS = 3

    fun at(context: Context, timeMs: Long, which: Int) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMs, intent(context, which))
    }

    fun cancel(context: Context, which: Int) {
        context.getSystemService(AlarmManager::class.java)?.cancel(intent(context, which))
    }

    private fun intent(context: Context, which: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        which,
        Intent(context, AlarmReceiver::class.java).setAction("app.mami.alarm.$which"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val graph = MamiApp.graph(context)
        graph.scope.launch {
            try {
                graph.messenger.onAlarm()
            } finally {
                pending.finish()
            }
        }
    }
}
