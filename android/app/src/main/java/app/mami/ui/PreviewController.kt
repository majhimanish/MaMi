package app.mami.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.mami.sync.MamiBackend
import app.mami.ui.theme.Appearance
import kotlinx.coroutines.CoroutineScope

/** A [UiController] without the app around it, for previews and screenshot tests. */
class PreviewController(
    override val backend: MamiBackend,
    appearance: Appearance,
    scope: CoroutineScope,
) : BaseUiController(scope) {
    override val canSkip: Boolean = true
    override var appearance by mutableStateOf(appearance)
        private set

    override fun setAppearance(appearance: Appearance) {
        this.appearance = appearance
    }

    override fun skip(from: Stage) = Unit
}
