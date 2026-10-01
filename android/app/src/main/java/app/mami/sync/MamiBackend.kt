package app.mami.sync

import app.mami.core.DeviceStatus
import app.mami.core.NudgeKind
import app.mami.core.ShareKind
import app.mami.data.ConnectionState
import app.mami.data.InviteDto
import app.mami.data.PartnerDto
import app.mami.data.PresenceDto
import app.mami.data.SavedQuickStatus
import app.mami.data.db.MessageEntity
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

    val partnerName: String
    val email: String?
    var serverUrl: String
    var lowBatteryAlerts: Boolean
    var hideNotificationText: Boolean

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

    fun sendText(text: String, replyTo: String? = null)
    fun sendNudge(kind: NudgeKind)
    fun onComposerChanged(text: String)
    fun markChatRead()
    suspend fun refresh()
}
