package app.mami.sync

import android.net.Uri
import app.mami.core.DeviceStatus
import app.mami.core.LinkPreview
import app.mami.core.NudgeKind
import app.mami.core.ShareKind
import app.mami.data.ConnectionState
import app.mami.data.InviteDto
import app.mami.data.PartnerDto
import app.mami.data.PresenceDto
import app.mami.data.SavedQuickStatus
import app.mami.data.db.MessageEntity
import app.mami.media.VoiceRecording
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the screens need. [Messenger] talks to the real server; the debug
 * build also has a [app.mami.demo.DemoBackend] with a simulated partner, so
 * every screen can be reached and played with offline.
 */
interface MamiBackend {
    val signedIn: StateFlow<Boolean>
    val displayName: StateFlow<String>
    val sharingConfirmed: StateFlow<Boolean>
    val invite: StateFlow<InviteDto?>
    val partner: StateFlow<PartnerDto?>
    val presence: StateFlow<PresenceDto?>

    /** The partner's last status and when it arrived. */
    val partnerStatus: StateFlow<Pair<DeviceStatus, Long>?>
    val partnerTyping: StateFlow<Boolean>

    /** An end-to-end encrypted session with the partner's current phone exists. */
    val secure: StateFlow<Boolean>
    val partnerKeyChanged: StateFlow<Boolean>
    val verifiedPartnerKey: StateFlow<String?>
    val incomingNudges: SharedFlow<NudgeKind>
    val messages: Flow<List<MessageEntity>>
    val connection: StateFlow<ConnectionState>
    val shares: StateFlow<Set<ShareKind>>
    val quickStatus: StateFlow<SavedQuickStatus?>

    /** Voice and video calls. */
    val calls: app.mami.calls.Calls

    /** Upload and download progress of attachments, 0..1, by message id. */
    val transferProgress: StateFlow<Map<String, Float>>

    val partnerName: String
    val email: String?
    var serverUrl: String
    var lowBatteryAlerts: Boolean
    var hideNotificationText: Boolean

    /** Make link previews on this phone before sending. */
    var linkPreviews: Boolean

    /** Set by the chat screen while it is visible. */
    var chatVisible: Boolean

    suspend fun requestCode(email: String)
    suspend fun verifyCode(email: String, code: String)
    suspend fun setDisplayName(name: String)
    fun confirmSharing(shares: Set<ShareKind>, lowBatteryAlerts: Boolean)
    fun setShares(shares: Set<ShareKind>)
    fun setQuickStatus(status: SavedQuickStatus?)

    suspend fun createInvite(partnerEmail: String?): InviteDto
    suspend fun cancelInvite()
    suspend fun acceptInvite(code: String)
    suspend fun unpair()
    suspend fun signOut()
    suspend fun deleteAccount()

    suspend fun safetyCode(): String?
    fun markPartnerVerified()
    fun dismissKeyChanged()

    fun sendText(text: String, replyTo: String? = null, link: LinkPreview? = null)
    fun sendNudge(kind: NudgeKind)

    /**
     * Photos and videos from the picker or camera, or any file as a document.
     * Throws [app.mami.media.MediaException] with a friendly message if one
     * can't be sent (too big, unreadable).
     */
    suspend fun sendMedia(uris: List<Uri>, caption: String?, viewOnce: Boolean, asDocument: Boolean, replyTo: String?)
    fun sendVoice(recording: VoiceRecording, replyTo: String?)

    /** A new file in the media folder to record a voice note into. */
    fun newVoiceFile(): File?

    /** The attachment of [message] on this phone, if it's here. */
    fun fileFor(message: MessageEntity): File?

    /** Downloads (or retries) an attachment that's waiting or failed. */
    fun retryTransfer(id: String)

    /** Copies an attachment to the gallery or Downloads. */
    suspend fun saveToDevice(message: MessageEntity): Boolean

    fun react(id: String, emoji: String?)
    fun edit(id: String, text: String)

    /** Removes my message from both phones. */
    fun unsend(id: String)
    fun deleteForMe(id: String)
    fun setPinned(id: String, pinned: Boolean)
    fun setStarred(id: String, starred: Boolean)

    /** A view-once photo or video was looked at; it's gone now. */
    fun viewOnceOpened(id: String)

    suspend fun search(query: String): List<MessageEntity>
    suspend fun linkPreview(url: String): LinkPreview?

    fun onComposerChanged(text: String)
    fun markChatRead()
    suspend fun refresh()
}
