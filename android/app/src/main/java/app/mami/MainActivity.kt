package app.mami

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.mami.calls.CallIntents
import app.mami.ui.MamiRoot
import app.mami.ui.call.CallUi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { MamiRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Leaving the app during a video call keeps the call in a small floating window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (CallUi.videoCallOnScreen && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build()) }
        }
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == CallIntents.ACTION_ANSWER) CallIntents.answerRequested.value = true
    }
}
