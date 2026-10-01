package app.mami

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.mami.device.DeviceWatcher
import app.mami.sync.StatusWorker
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

class MamiApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        initFirebase()
        graph = AppGraph(this)
        graph.notifications.createChannels()

        val messenger = graph.messenger
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = messenger.onForeground()
                override fun onStop(owner: LifecycleOwner) = messenger.onBackground()
            },
        )
        DeviceWatcher(this, messenger::onDeviceChanged, messenger::onNetworkAvailable, messenger::onScreenOff, messenger::onUnlocked).start()
        StatusWorker.schedule(this)
    }

    /** Push notifications only work when the build includes a google-services.json. */
    private fun initFirebase() {
        if (BuildConfig.FIREBASE_APP_ID.isEmpty() || FirebaseApp.getApps(this).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build()
        FirebaseApp.initializeApp(this, options)
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
    }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as MamiApp).graph
    }
}
