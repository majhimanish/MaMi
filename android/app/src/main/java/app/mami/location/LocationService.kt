package app.mami.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.mami.MainActivity
import app.mami.MamiApp
import app.mami.R
import app.mami.sync.Notifications
import app.mami.ui.Format

/**
 * Shares this phone's location with the partner until the chosen time, as a
 * foreground service so it keeps going with the screen off. Uses Android's
 * own location providers (no Google services), GPS and network.
 */
class LocationService : Service(), LocationListener {
    private var manager: LocationManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MamiApp.graph(this).messenger.stopSharingLocation()
            stopSelf()
            return START_NOT_STICKY
        }
        val graph = MamiApp.graph(this)
        val until = graph.settings.locationShareUntil
        if (until == null || until <= System.currentTimeMillis() || !allowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, Notifications.ID_LOCATION, notification(graph.messenger.partnerName, until), type)
        } catch (e: Exception) {
            Log.w(TAG, "can't share location in the background right now", e)
            stopSelf()
            return START_NOT_STICKY
        }
        startUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // checked by allowed()
    private fun startUpdates() {
        val manager = getSystemService(LocationManager::class.java) ?: return
        this.manager = manager
        manager.removeUpdates(this)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            .forEach { provider ->
                runCatching { manager.getLastKnownLocation(provider)?.let(::onLocationChanged) }
                manager.requestLocationUpdates(provider, INTERVAL_MS, MIN_DISTANCE_M, this, Looper.getMainLooper())
            }
    }

    override fun onLocationChanged(location: Location) {
        MamiApp.graph(this).messenger.onMyLocation(location)
    }

    @Deprecated("Needed below Android 10")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    override fun onDestroy() {
        manager?.removeUpdates(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(partnerName: String, until: Long): Notification {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_LOCATION)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.brand))
            .setContentTitle("Sharing your location with $partnerName")
            .setContentText("Until ${Format.time(until)} · end-to-end encrypted")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop sharing", stop)
            .build()
    }

    companion object {
        private const val TAG = "MaMi.Location"
        private const val ACTION_STOP = "app.mami.location.STOP"
        private const val INTERVAL_MS = 10_000L
        private const val MIN_DISTANCE_M = 5f

        fun allowed(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** The freshest position the phone already knows, without switching GPS on. */
        @SuppressLint("MissingPermission") // checked by allowed()
        fun lastKnown(context: Context): Location? {
            if (!allowed(context)) return null
            val manager = context.getSystemService(LocationManager::class.java) ?: return null
            return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not start location sharing", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationService::class.java))
        }
    }
}
