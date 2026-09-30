package app.mami.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mami.MamiApp
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Every 15 minutes (the shortest period Android allows): fetch anything
 * waiting, retry unsent messages and refresh this phone's status for the
 * partner. Also checks for a nearly empty battery.
 */
class StatusWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        MamiApp.graph(applicationContext).messenger.backgroundTick()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StatusWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("status", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** Fallback when a push arrives but there was not enough time to sync right away. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        MamiApp.graph(applicationContext).messenger.onPush()
        Result.success()
    } catch (e: IOException) {
        Result.retry()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
