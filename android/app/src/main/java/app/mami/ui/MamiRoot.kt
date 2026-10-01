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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mami.BuildConfig
import app.mami.ui.call.CallOverlay
import app.mami.ui.chat.ChatScreen
import app.mami.ui.chat.MediaViewer
import app.mami.ui.chat.StarredScreen
import app.mami.ui.chat.viewable
import app.mami.ui.media.SharedMediaScreen
import app.mami.ui.debug.Playground
import app.mami.ui.debug.PlaygroundButton
import app.mami.ui.onboarding.ProfileScreen
import app.mami.ui.onboarding.SharingScreen
import app.mami.ui.onboarding.SignInFlow
import app.mami.ui.pairing.PairScreen
import app.mami.ui.settings.SafetyScreen
import app.mami.ui.settings.SettingsScreen
import app.mami.ui.together.LetterComposer
import app.mami.ui.together.LetterReader
import app.mami.ui.together.LocationSheet
import app.mami.ui.together.TogetherScreen
import app.mami.ui.theme.Mami
import app.mami.ui.theme.MamiTheme

@Composable
fun MamiRoot(vm: MainViewModel = viewModel()) {
    val controller = vm.controller
    MamiTheme(controller.appearance) {
        SystemBars(dark = Mami.colors.isDark)
        Box(Modifier.fillMaxSize()) {
            key(controller.demoMode) {
                MamiScreens(controller)
                CallOverlay(controller)
            }
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
    val partner by ui.backend.partner.collectAsStateWithLifecycle()
    val messages by ui.backend.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val partnerName = partner?.displayName?.ifBlank { null } ?: partner?.email?.substringBefore('@') ?: "Your partner"
    var viewing by remember { mutableStateOf<String?>(null) }
    val showInChat: (String) -> Unit = { id ->
        viewing = null
        ui.overlay = Overlay.None
        ui.jumpTo = id
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
            Overlay.Media -> SharedMediaScreen(
                ui,
                partnerName,
                onBack = { ui.overlay = Overlay.None },
                onOpen = { viewing = it.id },
                onShowInChat = showInChat,
            )
            Overlay.Starred -> StarredScreen(ui, partnerName, onBack = { ui.overlay = Overlay.None }, onShowInChat = showInChat)
            Overlay.Together -> TogetherScreen(ui, partnerName, onBack = { ui.overlay = Overlay.None })
        }
    }
    ui.reading?.let { id -> messages.firstOrNull { it.id == id } }?.let { letter ->
        LetterReader(
            letter,
            partnerName,
            now = System.currentTimeMillis(),
            onOpened = { ui.backend.openLetter(letter.id) },
            onDismiss = { ui.reading = null },
        )
    }
    if (ui.writingLetter) {
        LetterComposer(partnerName, System.currentTimeMillis(), onDismiss = { ui.writingLetter = false }) { title, body, paper, openAt ->
            ui.backend.sendLetter(title, body, paper, openAt)
            ui.writingLetter = false
        }
    }
    if (ui.locationOpen) {
        LocationSheet(ui.backend, partnerName, onDismiss = { ui.locationOpen = false })
    }
    viewing?.let { start ->
        MediaViewer(
            backend = ui.backend,
            items = remember(messages, start) { viewable(messages, ui.backend, start) },
            startId = start,
            partnerName = partnerName,
            onShowInChat = showInChat,
            onDismiss = { viewing = null },
        )
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
