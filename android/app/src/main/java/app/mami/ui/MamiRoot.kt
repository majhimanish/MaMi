package app.mami.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mami.ui.chat.ChatScreen
import app.mami.ui.onboarding.ProfileScreen
import app.mami.ui.onboarding.SharingScreen
import app.mami.ui.onboarding.SignInScreen
import app.mami.ui.pairing.PairScreen
import app.mami.ui.settings.SafetyScreen
import app.mami.ui.settings.SettingsScreen

private enum class Overlay { None, Settings, Safety }

@Composable
fun MamiRoot(vm: MainViewModel = viewModel()) {
    val stage by vm.stage.collectAsStateWithLifecycle()
    AnimatedContent(targetState = stage, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stage") { current ->
        when (current) {
            Stage.SignIn -> SignInScreen(vm)
            Stage.Profile -> ProfileScreen(vm)
            Stage.Sharing -> SharingScreen(vm)
            Stage.Pair -> PairScreen(vm)
            Stage.Chat -> PairedApp(vm)
        }
    }
}

@Composable
private fun PairedApp(vm: MainViewModel) {
    var overlay by rememberSaveable { mutableStateOf(Overlay.None) }
    BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }
    when (overlay) {
        Overlay.None -> ChatScreen(vm, onOpenSettings = { overlay = Overlay.Settings }, onVerify = { overlay = Overlay.Safety })
        Overlay.Settings -> SettingsScreen(vm, onBack = { overlay = Overlay.None }, onVerify = { overlay = Overlay.Safety })
        Overlay.Safety -> SafetyScreen(vm, onBack = { overlay = Overlay.Settings })
    }
}
