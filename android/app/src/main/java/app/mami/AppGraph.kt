package app.mami

import android.content.Context
import androidx.room.Room
import app.mami.data.Api
import app.mami.data.CryptoStore
import app.mami.data.Realtime
import app.mami.data.Settings
import app.mami.data.db.MamiDatabase
import app.mami.device.DeviceStatusCollector
import app.mami.sync.Messenger
import app.mami.sync.Notifications
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** Wires the app together. One instance per process, owned by [MamiApp]. */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val api = Api(settings, http)
    val realtime = Realtime(settings, http, scope)
    val crypto = CryptoStore(File(context.filesDir, "crypto"), settings)
    val database = Room.databaseBuilder(context, MamiDatabase::class.java, "mami.db").build()
    val notifications = Notifications(context)
    val messenger = Messenger(
        context = context,
        settings = settings,
        api = api,
        realtime = realtime,
        crypto = crypto,
        db = database,
        collector = DeviceStatusCollector(context),
        notifications = notifications,
        scope = scope,
    )
}
