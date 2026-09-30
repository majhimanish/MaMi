package app.mami.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mami.MamiApp
import app.mami.data.ApiException
import app.mami.sync.Messenger
import app.mami.sync.NoSessionException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Stage { SignIn, Profile, Sharing, Pair, Chat }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val messenger: Messenger = MamiApp.graph(application).messenger

    val stage: StateFlow<Stage> = combine(
        messenger.signedIn,
        messenger.displayName,
        messenger.sharingConfirmed,
        messenger.partner,
    ) { signedIn, name, sharingConfirmed, partner ->
        stageFor(signedIn, name, sharingConfirmed, partner != null)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        stageFor(
            messenger.signedIn.value,
            messenger.displayName.value,
            messenger.sharingConfirmed.value,
            messenger.partner.value != null,
        ),
    )

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Runs a user action, showing progress and a friendly error if it fails. */
    fun run(action: suspend () -> Unit, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
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

    fun clearError() {
        error = null
    }

    private fun stageFor(signedIn: Boolean, name: String, sharingConfirmed: Boolean, paired: Boolean) = when {
        !signedIn -> Stage.SignIn
        name.isBlank() -> Stage.Profile
        !sharingConfirmed -> Stage.Sharing
        !paired -> Stage.Pair
        else -> Stage.Chat
    }

    private fun describe(e: Exception): String = when (e) {
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
            else -> "Something went wrong (${e.code}). Please try again."
        }
        is NoSessionException -> "The secure connection with your partner isn't ready yet."
        is IOException -> "Can't reach MaMi. Check your internet connection."
        else -> e.message ?: "Something went wrong."
    }
}
