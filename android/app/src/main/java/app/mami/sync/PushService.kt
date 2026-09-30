package app.mami.sync

import app.mami.MamiApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the content-free "you have something waiting" pushes. The message
 * itself is downloaded and decrypted on the phone.
 */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val messenger = MamiApp.graph(this).messenger
        // A high-priority push gives the app a short window to run; use it directly.
        val done = runBlocking {
            withTimeoutOrNull(15_000) {
                runCatching { messenger.onPush() }.isSuccess
            }
        }
        if (done != true) SyncWorker.enqueue(this)
    }

    override fun onNewToken(token: String) {
        MamiApp.graph(this).messenger.onNewPushToken(token)
    }
}
