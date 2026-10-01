package app.mami.ui

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mami.BuildConfig
import app.mami.ui.chat.ChatScreen
import app.mami.ui.debug.Playground
import app.mami.ui.debug.PlaygroundButton
import app.mami.ui.onboarding.ProfileScreen
import app.mami.ui.onboarding.SharingScreen
import app.mami.ui.onboarding.SignInFlow
import app.mami.ui.pairing.PairScreen
import app.mami.ui.settings.SafetyScreen
import app.mami.ui.settings.SettingsScreen
import app.mami.ui.theme.Mami
import app.mami.ui.theme.MamiTheme

@Composable
fun MamiRoot(vm: MainViewModel = viewModel()) {
    val controller = vm.controller
    MamiTheme(controller.appearance) {
        SystemBars(dark = Mami.colors.isDark)
        Box(Modifier.fillMaxSize()) {
            key(controller.demoMode) { MamiScreens(controller) }
            if (BuildConfig.DEBUG) {
                PlaygroundButton(onClick = { controller.playgroundOpen = true })
                if (controller.playgroundOpen) Playground(controller, onDismiss = { controller.playgroundOpen = false })
            }
        }
    }
}

/** Picks the screen from where the person is: signed in? named? sharing chosen? paired? */
@Composable
fun MamiScreens(ui: UiController) {
    val backend = ui.backend
    val signedIn by backend.signedIn.collectAsStateWithLifecycle()
    val name by backend.displayName.collectAsStateWithLifecycle()
    val sharingConfirmed by backend.sharingConfirmed.collectAsStateWithLifecycle()
    val partner by backend.partner.collectAsStateWithLifecycle()
    val stage = when {
        !signedIn -> Stage.SignIn
        name.isBlank() -> Stage.Profile
        !sharingConfirmed -> Stage.Sharing
        partner == null -> Stage.Pair
        else -> Stage.Chat
    }
    AnimatedContent(
        targetState = stage,
        transitionSpec = {
            (fadeIn(tween(380)) + scaleIn(initialScale = 0.96f, animationSpec = tween(380))) togetherWith fadeOut(tween(180))
        },
        label = "stage",
    ) { current ->
        when (current) {
            Stage.SignIn -> SignInFlow(ui)
            Stage.Profile -> ProfileScreen(ui)
            Stage.Sharing -> SharingScreen(ui)
            Stage.Pair -> PairScreen(ui)
            Stage.Chat -> PairedScreens(ui)
        }
    }
}

@Composable
private fun PairedScreens(ui: UiController) {
    BackHandler(enabled = ui.overlay != Overlay.None) {
        ui.overlay = if (ui.overlay == Overlay.Safety) Overlay.Settings else Overlay.None
    }
    AnimatedContent(
        targetState = ui.overlay,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            (slideInHorizontally { if (forward) it else -it / 3 } + fadeIn()) togetherWith
                (slideOutHorizontally { if (forward) -it / 3 else it } + fadeOut())
        },
        label = "overlay",
    ) { overlay ->
        when (overlay) {
            Overlay.None -> ChatScreen(ui)
            Overlay.Settings -> SettingsScreen(ui)
            Overlay.Safety -> SafetyScreen(ui)
        }
    }
}

/** Light or dark status-bar icons to match the app's theme, not just the system's. */
@Composable
private fun SystemBars(dark: Boolean) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    LaunchedEffect(dark) {
        val style = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
