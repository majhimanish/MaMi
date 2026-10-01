package app.mami.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.mami.data.ApiException
import app.mami.sync.MamiBackend
import app.mami.sync.NoSessionException
import app.mami.ui.theme.Appearance
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Stage { SignIn, Profile, Sharing, Pair, Chat }

enum class SignInStep { Welcome, Email, Code }

enum class Overlay { None, Settings, Safety, Media, Starred, Together }

/** What every screen needs: the backend, navigation state and a way to run actions. */
interface UiController {
    val backend: MamiBackend
    val busy: Boolean
    val error: String?

    /** Debug builds: every step can be skipped. */
    val canSkip: Boolean
    var signInStep: SignInStep
    var signInEmail: String
    var overlay: Overlay
    var partnerSheetOpen: Boolean

    /** A message the chat should scroll to and flash (from search, media, starred). */
    var jumpTo: String?

    /** The love letter being read, by message id. */
    var reading: String?
    var writingLetter: Boolean

    /** The live location sheet is open. */
    var locationOpen: Boolean
    val appearance: Appearance

    fun changeAppearance(appearance: Appearance)

    /** Runs a user action, showing progress and a friendly error if it fails. */
    fun run(action: suspend () -> Unit, onSuccess: () -> Unit = {})
    fun clearError()

    /** Debug: jump past [from] using the simulated partner. */
    fun skip(from: Stage)
}

/** Shared state and error handling for [UiController] implementations. */
abstract class BaseUiController(protected val scope: CoroutineScope) : UiController {
    override var busy by mutableStateOf(false)
        protected set
    override var error by mutableStateOf<String?>(null)
        protected set
    override var signInStep by mutableStateOf(SignInStep.Welcome)
    override var signInEmail by mutableStateOf("")
    override var overlay by mutableStateOf(Overlay.None)
    override var partnerSheetOpen by mutableStateOf(false)
    override var jumpTo by mutableStateOf<String?>(null)
    override var reading by mutableStateOf<String?>(null)
    override var writingLetter by mutableStateOf(false)
    override var locationOpen by mutableStateOf(false)

    override fun run(action: suspend () -> Unit, onSuccess: () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                action()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                busy = false
            }
        }
    }

    override fun clearError() {
        error = null
    }
}

fun describe(e: Exception): String = when (e) {
    is ApiException -> when (e.code) {
        "wrong_code" -> "That code isn't right. Check the email and try again."
        "too_many_requests" -> "Too many tries. Please wait a minute and try again."
        "bad_email" -> "That doesn't look like an email address."
        "own_email" -> "Enter your partner's email address, not yours."
        "email_failed" -> "We couldn't send the email. Please try again."
        "invite_not_found" -> "There's no invite with that code. Check it and try again."
        "invite_expired" -> "That invite has expired. Ask your partner for a new one."
        "invite_for_someone_else" -> "This invite was made for a different email address."
        "own_invite" -> "That's your own invite. Your partner needs to enter it on their phone."
        "already_paired" -> "One of you is already linked with someone."
        "not_paired" -> "You're not linked with a partner."
        "name_too_long" -> "Please use a shorter name (40 characters at most)."
        "bad_server_address" -> "The server address isn't valid."
        "unauthorized" -> "Please sign in again."
        "too_large" -> "That's too big to send (100 MB at most)."
        "quota_full" -> "Too much is waiting for your partner to download. Try again once they've opened the chat."
        else -> "Something went wrong (${e.code}). Please try again."
    }
    is NoSessionException -> "The secure connection with your partner isn't ready yet."
    is app.mami.media.MediaException -> e.message ?: "That file couldn't be sent."
    is IOException -> "Can't reach MaMi. Check your internet connection (or use the 🐞 demo mode)."
    else -> e.message ?: "Something went wrong."
}
