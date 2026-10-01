package app.mami.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mami.BuildConfig
import app.mami.AppGraph
import app.mami.MamiApp
import app.mami.demo.DemoBackend
import app.mami.sync.MamiBackend
import app.mami.ui.theme.Appearance
import app.mami.ui.theme.DarkMode
import app.mami.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Screens of the app the debug playground can jump to. */
enum class Destination(val label: String) {
    Welcome("Welcome"),
    Email("Email"),
    Code("Code"),
    Profile("Name"),
    Sharing("Sharing"),
    Pair("Pairing"),
    PairWaiting("Invite code"),
    Chat("Chat"),
    Partner("Partner sheet"),
    Settings("Settings"),
    Safety("Safety code"),
    Together("Together"),
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val controller = AppController(MamiApp.graph(application), viewModelScope)
}

/** The app's [UiController]: real or simulated backend, appearance, and the debug playground. */
class AppController(private val graph: AppGraph, scope: CoroutineScope) : BaseUiController(scope) {
    private val settings = graph.settings
    val demo: DemoBackend get() = graph.demo

    var demoMode by mutableStateOf(BuildConfig.DEBUG && settings.demoMode)
        private set

    override val backend: MamiBackend get() = if (demoMode) graph.demo else graph.messenger

    override val canSkip: Boolean = BuildConfig.DEBUG

    override var appearance by mutableStateOf(
        Appearance(
            palette = Palette.entries.firstOrNull { it.name == settings.palette } ?: Palette.ROSE,
            darkMode = DarkMode.entries.firstOrNull { it.name == settings.darkMode } ?: DarkMode.SYSTEM,
            chatWallpaper = settings.chatWallpaper,
        ),
    )
        private set

    var playgroundOpen by mutableStateOf(false)

    override fun changeAppearance(appearance: Appearance) {
        this.appearance = appearance
        settings.palette = appearance.palette.name
        settings.darkMode = appearance.darkMode.name
        settings.chatWallpaper = appearance.chatWallpaper
    }

    fun setDemo(on: Boolean) {
        if (on && !BuildConfig.DEBUG) return
        demoMode = on
        settings.demoMode = on
        clearError()
        overlay = Overlay.None
        partnerSheetOpen = false
    }

    override fun skip(from: Stage) {
        if (!BuildConfig.DEBUG) return
        setDemo(true)
        when (from) {
            Stage.SignIn -> demo.skipSignIn()
            Stage.Profile -> demo.skipProfile()
            Stage.Sharing -> demo.skipSharing()
            Stage.Pair -> demo.skipPairing()
            Stage.Chat -> Unit
        }
    }

    /** Debug: show any screen, with the simulated partner providing the data. */
    fun goTo(destination: Destination) {
        setDemo(true)
        val d = demo
        when (destination) {
            Destination.Welcome, Destination.Email, Destination.Code -> {
                d.reset()
                signInEmail = "you@example.com"
                signInStep = when (destination) {
                    Destination.Welcome -> SignInStep.Welcome
                    Destination.Email -> SignInStep.Email
                    else -> SignInStep.Code
                }
            }
            Destination.Profile -> {
                d.reset()
                d.skipSignIn()
            }
            Destination.Sharing -> {
                d.reset()
                d.skipProfile()
            }
            Destination.Pair -> {
                d.reset()
                d.skipSharing()
            }
            Destination.PairWaiting -> {
                d.reset()
                d.skipSharing()
                scope.launch { d.createInvite("maya@example.com") }
            }
            Destination.Chat -> d.skipPairing()
            Destination.Partner -> {
                d.skipPairing()
                partnerSheetOpen = true
            }
            Destination.Settings -> {
                d.skipPairing()
                overlay = Overlay.Settings
            }
            Destination.Safety -> {
                d.skipPairing()
                overlay = Overlay.Safety
            }
            Destination.Together -> {
                d.skipPairing()
                overlay = Overlay.Together
            }
        }
    }
}
