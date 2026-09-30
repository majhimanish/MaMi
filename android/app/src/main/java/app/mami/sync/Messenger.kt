package app.mami.sync

import android.content.Context
import android.util.Log
import app.mami.core.AlertKind
import app.mami.core.Ciphertext
import app.mami.core.CoreException
import app.mami.core.DeviceStatus
import app.mami.core.NudgeKind
import app.mami.core.Payload
import app.mami.core.ShareKind
import app.mami.core.payloadFromJson
import app.mami.core.payloadToJson
import app.mami.core.verifyIdentity
import app.mami.data.AcceptedDto
import app.mami.data.Api
import app.mami.data.ApiException
import app.mami.data.ConnectionState
import app.mami.data.CryptoStore
import app.mami.data.EnvelopeDto
import app.mami.data.IdentityDto
import app.mami.data.InviteDto
import app.mami.data.MamiJson
import app.mami.data.MeDto
import app.mami.data.PartnerDto
import app.mami.data.PresenceDto
import app.mami.data.Realtime
import app.mami.data.SavedQuickStatus
import app.mami.data.SendRequestDto
import app.mami.data.ServerFrame
import app.mami.data.Settings
import app.mami.data.db.MamiDatabase
import app.mami.data.db.MessageEntity
import app.mami.data.db.MessageKind
import app.mami.data.db.MessageState
import app.mami.device.DeviceStatusCollector
import app.mami.device.RawStatus
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Thrown when there is no encrypted session with the partner yet. */
class NoSessionException : IOException("no secure session with the partner yet")

/**
 * The heart of the app: signs in, pairs, keeps the encrypted session alive,
 * sends and receives everything, and publishes this phone's status.
 *
 * All message contents are encrypted and decrypted here, on the phone.
 */
class Messenger(
    private val context: Context,
    private val settings: Settings,
    private val api: Api,
    private val realtime: Realtime,
    private val crypto: CryptoStore,
    db: MamiDatabase,
    private val collector: DeviceStatusCollector,
    private val notifications: Notifications,
    private val scope: CoroutineScope,
) {
    private val dao = db.messages()

    private val _signedIn = MutableStateFlow(settings.token != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _displayName = MutableStateFlow(settings.displayName)
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _sharingConfirmed = MutableStateFlow(settings.sharingConfirmed)
    val sharingConfirmed: StateFlow<Boolean> = _sharingConfirmed.asStateFlow()

    private val _me = MutableStateFlow<MeDto?>(null)
    val me: StateFlow<MeDto?> = _me.asStateFlow()

    private val _invite = MutableStateFlow<InviteDto?>(null)
    val invite: StateFlow<InviteDto?> = _invite.asStateFlow()

    private val _partner = MutableStateFlow(loadCachedPartner())
    val partner: StateFlow<PartnerDto?> = _partner.asStateFlow()

    private val _presence = MutableStateFlow(_partner.value?.presence)
    val presence: StateFlow<PresenceDto?> = _presence.asStateFlow()

    private val _partnerStatus = MutableStateFlow(loadCachedStatus())
    /** The partner's last status and when it arrived. */
    val partnerStatus: StateFlow<Pair<DeviceStatus, Long>?> = _partnerStatus.asStateFlow()

    private val _typing = MutableStateFlow(false)
    val partnerTyping: StateFlow<Boolean> = _typing.asStateFlow()

    private val _secure = MutableStateFlow(false)
    /** An end-to-end encrypted session with the partner's current phone exists. */
    val secure: StateFlow<Boolean> = _secure.asStateFlow()

    private val _keyChanged = MutableStateFlow(settings.partnerKeyChanged)
    val partnerKeyChanged: StateFlow<Boolean> = _keyChanged.asStateFlow()

    private val _verifiedKey = MutableStateFlow(settings.verifiedPartnerKey)
    val verifiedPartnerKey: StateFlow<String?> = _verifiedKey.asStateFlow()

    private val _incomingNudges = MutableSharedFlow<NudgeKind>(extraBufferCapacity = 8)
    val incomingNudges: SharedFlow<NudgeKind> = _incomingNudges

    val messages: Flow<List<MessageEntity>> = dao.observeAll()
    val connection: StateFlow<ConnectionState> = realtime.state
    val shares: StateFlow<Set<ShareKind>> = settings.sharesFlow
    val quickStatus: StateFlow<SavedQuickStatus?> = settings.quickStatusFlow

    /** Set by the chat screen while it is visible. */
    @Volatile
    var chatVisible = false

    @Volatile
    private var foreground = false

    private val bootstrapLock = Mutex()
    private val syncLock = Mutex()
    private val outboxLock = Mutex()
    private val processedSeqs = LinkedHashSet<Long>()
    private var typingReset: Job? = null
    private var lastTypingSentAt = 0L
    private var typingSent = false

    init {
        scope.launch {
            realtime.frames.collect { frame ->
                try {
                    handleFrame(frame)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "could not handle frame", e)
                }
            }
        }
        scope.launch {
            realtime.state.collect { state ->
                if (state == ConnectionState.Connected) launchSafely { flushOutbox() }
            }
        }
    }

    // ---- app lifecycle ------------------------------------------------------------

    fun onForeground() {
        foreground = true
        if (settings.token == null) return
        realtime.start()
        launchSafely {
            refresh()
            publishStatus(force = false)
        }
    }

    fun onBackground() {
        foreground = false
        realtime.stop()
        launchSafely { publishStatus(force = false) }
    }

    fun onNetworkAvailable() {
        if (settings.token == null) return
        realtime.kick()
        launchSafely {
            flushOutbox()
            sync()
        }
    }

    /** Something about the phone changed (battery, charging, ringer, network). */
    fun onDeviceChanged() {
        if (settings.token == null) return
        launchSafely { publishStatus(force = false) }
    }

    /** Periodic background work: catch up, retry, refresh our status. */
    suspend fun backgroundTick() {
        if (settings.token == null) return
        quietly { refresh() }
        quietly { sync() }
        quietly { flushOutbox() }
        quietly { publishStatus(force = false) }
    }

    /** A push arrived: fetch what's waiting and refresh our status for the partner. */
    suspend fun onPush() {
        if (settings.token == null) return
        sync()
        quietly { flushOutbox() }
        quietly { publishStatus(force = false) }
    }

    fun onNewPushToken(token: String) {
        if (settings.token == null) return
        launchSafely { api.setPushToken(token) }
    }

    // ---- signing in -----------------------------------------------------------------

    suspend fun requestCode(email: String) = api.authStart(email.trim())

    suspend fun verifyCode(email: String, code: String) {
        val verified = api.authVerify(email.trim(), code.filter(Char::isDigit))
        // A new sign-in always gets fresh keys on this phone.
        wipeConversation()
        crypto.reset()
        settings.token = verified.token
        settings.email = email.trim().lowercase()
        _signedIn.value = true
        refresh()
        if (foreground) realtime.start()
    }

    suspend fun setDisplayName(name: String) {
        val clean = name.trim()
        api.setDisplayName(clean)
        settings.displayName = clean
        _displayName.value = clean
    }

    fun confirmSharing(shares: Set<ShareKind>, lowBatteryAlerts: Boolean) {
        settings.shares = shares
        settings.lowBatteryAlerts = lowBatteryAlerts
        settings.sharingConfirmed = true
        _sharingConfirmed.value = true
    }

    fun setShares(shares: Set<ShareKind>) {
        settings.shares = shares
        launchSafely { publishStatus(force = true) }
    }

    fun setQuickStatus(status: SavedQuickStatus?) {
        settings.quickStatus = status
        launchSafely { publishStatus(force = true) }
    }

    var lowBatteryAlerts: Boolean
        get() = settings.lowBatteryAlerts
        set(value) {
            settings.lowBatteryAlerts = value
        }

    var hideNotificationText: Boolean
        get() = settings.hideNotificationText
        set(value) {
            settings.hideNotificationText = value
        }

    var serverUrl: String
        get() = settings.serverUrl
        set(value) {
            settings.serverUrl = value
        }

    val email: String? get() = settings.email

    // ---- pairing ------------------------------------------------------------------

    suspend fun createInvite(partnerEmail: String?): InviteDto =
        api.createInvite(partnerEmail?.trim()?.ifBlank { null }).also { _invite.value = it }

    suspend fun cancelInvite() {
        api.cancelInvite()
        _invite.value = null
    }

    suspend fun acceptInvite(code: String) {
        api.acceptInvite(code)
        refresh()
    }

    /** Either partner can leave at any time. The conversation is deleted on both phones. */
    suspend fun unpair() {
        api.unpair()
        refresh()
    }

    suspend fun signOut() {
        try {
            api.signOut()
        } catch (e: IOException) {
            Log.w(TAG, "sign-out request failed; signing out locally", e)
        }
        wipeEverything()
    }

    suspend fun deleteAccount() {
        api.deleteAccount()
        wipeEverything()
    }

    /** The code both partners compare in person, or null before the partner has keys. */
    suspend fun safetyCode(): String? {
        val partnerKey = _partner.value?.identity?.ed25519 ?: return null
        return app.mami.core.safetyCode(crypto.identity().ed25519, partnerKey)
    }

    fun markPartnerVerified() {
        val key = _partner.value?.identity?.ed25519 ?: return
        settings.verifiedPartnerKey = key
        settings.partnerKeyChanged = false
        _verifiedKey.value = key
        _keyChanged.value = false
    }

    fun dismissKeyChanged() {
        settings.partnerKeyChanged = false
        _keyChanged.value = false
    }

    val partnerName: String
        get() = _partner.value?.let { p -> p.displayName.ifBlank { p.email.substringBefore('@') } } ?: "Your partner"

    // ---- conversation ---------------------------------------------------------------

    fun sendText(text: String, replyTo: String? = null) {
        val body = text.trim()
        if (body.isEmpty()) return
        queue(MessageKind.TEXT, body, replyTo = replyTo)
        stopTyping()
    }

    fun sendNudge(kind: NudgeKind) = queue(MessageKind.NUDGE, kind.name)

    fun onComposerChanged(text: String) {
        if (text.isBlank()) {
            stopTyping()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAt < TYPING_INTERVAL_MS || _presence.value?.online != true) return
        lastTypingSentAt = now
        typingSent = true
        launchSafely { sendEphemeral(Payload.Typing(true)) }
    }

    /** The chat is on screen: everything incoming counts as read. */
    fun markChatRead() {
        launchSafely {
            notifications.clearConversation()
            if (dao.markIncomingRead(System.currentTimeMillis()) > 0) flushReadReceipts()
        }
    }

    /** Fetches the latest from the server now (pull to refresh, app start). */
    suspend fun refresh() {
        bootstrapLock.withLock {
            if (settings.token == null) return
            val me = try {
                api.me()
            } catch (e: ApiException) {
                if (e.status == 401) {
                    wipeEverything()
                    return
                }
                throw e
            }
            _me.value = me
            _invite.value = me.invite
            if (me.displayName.isNotBlank()) {
                settings.displayName = me.displayName
                _displayName.value = me.displayName
            }
            uploadKeysIfNeeded(me)
            applyPartner(me.partner)
            ensureSession()
        }
    }

    // ---- internals: keys and sessions --------------------------------------------------

    private suspend fun uploadKeysIfNeeded(me: MeDto) {
        val identity = if (!me.hasIdentity) crypto.identity() else null
        if (identity != null || me.oneTimeKeyCount < MIN_ONE_TIME_KEYS) {
            val keys = crypto.oneTimeKeysToUpload(ONE_TIME_KEY_BATCH)
            api.uploadKeys(identity, keys)
            crypto.markKeysPublished()
        }
        quietly { uploadPushToken() }
    }

    private suspend fun uploadPushToken() {
        if (FirebaseApp.getApps(context).isEmpty()) return
        val token = FirebaseMessaging.getInstance().token.await()
        api.setPushToken(token)
    }

    private suspend fun applyPartner(partner: PartnerDto?) {
        val previous = _partner.value
        if (partner == null) {
            if (previous != null) wipeConversation()
            _partner.value = null
            _secure.value = false
            return
        }
        if (previous != null && previous.userId != partner.userId) wipeConversation()
        val before = _partner.value
        val identity = partner.identity?.takeIf(::isValidIdentity)
        val oldKey = before?.identity?.ed25519
        if (oldKey != null && identity != null && oldKey != identity.ed25519) {
            // Their phone changed: start over with the new keys and resend what they missed.
            crypto.forgetSessions(keepPartnerCurve = identity.curve25519)
            settings.partnerKeyChanged = true
            settings.verifiedPartnerKey = null
            _keyChanged.value = true
            _verifiedKey.value = null
            dao.requeueUndelivered()
        }
        val stored = partner.copy(identity = identity)
        _partner.value = stored
        _presence.value = partner.presence
        settings.partnerJson = MamiJson.encodeToString(PartnerDto.serializer(), stored)
        _secure.value = identity != null && crypto.hasSessionWith(identity.curve25519)
    }

    private suspend fun ensureSession() {
        val identity = _partner.value?.identity?.toCore() ?: return
        if (crypto.hasSessionWith(identity.curve25519)) {
            _secure.value = true
            return
        }
        val claimed = try {
            api.claimPartnerKey()
        } catch (e: ApiException) {
            if (e.status == 409) return // the partner's phone hasn't uploaded keys yet
            throw e
        }
        if (claimed.identity.toCore() != identity) return // keys moved on; the next refresh catches up
        crypto.startSession(identity, claimed.oneTimeKey.toCore())
        _secure.value = true
        sendPayload(Payload.Hello(settings.displayName), KIND_MESSAGE, newId(), push = true)
        flushOutbox()
    }

    private fun isValidIdentity(identity: IdentityDto): Boolean = try {
        verifyIdentity(identity.toCore())
        true
    } catch (e: CoreException) {
        Log.e(TAG, "partner keys failed the signature check; ignoring them")
        false
    }

    // ---- internals: sending ------------------------------------------------------------

    private fun queue(kind: String, body: String, battery: Int? = null, replyTo: String? = null) {
        val now = System.currentTimeMillis()
        val message = MessageEntity(
            id = newId(),
            fromMe = true,
            kind = kind,
            body = body,
            batteryPercent = battery,
            sentAtMs = now,
            sortAtMs = now,
            state = MessageState.PENDING,
            replyTo = replyTo,
        )
        launchSafely {
            dao.insert(message)
            flushOutbox()
        }
    }

    private suspend fun sendPayload(payload: Payload, kind: String, id: String, push: Boolean): AcceptedDto {
        val partnerCurve = _partner.value?.identity?.curve25519 ?: throw NoSessionException()
        val ciphertext = crypto.encrypt(partnerCurve, payload) ?: throw NoSessionException()
        return api.send(SendRequestDto(id, kind, ciphertext.messageType, ciphertext.body, push))
    }

    private suspend fun sendEphemeral(payload: Payload) {
        if (realtime.state.value != ConnectionState.Connected) return
        val partnerCurve = _partner.value?.identity?.curve25519 ?: return
        val ciphertext = crypto.encrypt(partnerCurve, payload) ?: return
        realtime.sendEphemeral(SendRequestDto(newId(), KIND_EPHEMERAL, ciphertext.messageType, ciphertext.body, false))
    }

    private fun stopTyping() {
        if (!typingSent) return
        typingSent = false
        lastTypingSentAt = 0
        launchSafely { sendEphemeral(Payload.Typing(false)) }
    }

    /** Sends everything waiting on this phone, oldest first. */
    suspend fun flushOutbox() {
        outboxLock.withLock {
            if (settings.token == null || _partner.value == null) return
            for (message in dao.pendingOutgoing()) {
                val payload = payloadFor(message) ?: continue
                val accepted = try {
                    sendPayload(payload, KIND_MESSAGE, message.id, push = true)
                } catch (e: IOException) {
                    Log.d(TAG, "message ${message.id} waits: ${e.message}")
                    return
                }
                dao.markSent(message.id, accepted.atMs)
            }
            flushReadReceipts()
        }
    }

    private suspend fun flushReadReceipts() {
        val ids = dao.unsentReadReceipts()
        if (ids.isEmpty()) return
        try {
            sendPayload(Payload.Read(ids, System.currentTimeMillis()), KIND_MESSAGE, newId(), push = false)
            dao.markReadReceiptsSent(ids)
        } catch (e: IOException) {
            Log.d(TAG, "read receipts wait: ${e.message}")
        }
    }

    private fun payloadFor(message: MessageEntity): Payload? = when (message.kind) {
        MessageKind.TEXT -> Payload.Text(message.id, message.body, message.sentAtMs, message.replyTo)
        MessageKind.NUDGE -> Payload.Nudge(
            message.id,
            NudgeKind.entries.firstOrNull { it.name == message.body } ?: NudgeKind.THINKING_OF_YOU,
            message.sentAtMs,
        )
        MessageKind.ALERT -> Payload.Alert(message.id, AlertKind.BATTERY_CRITICAL, message.batteryPercent, message.sentAtMs)
        else -> null
    }

    // ---- internals: this phone's status ------------------------------------------------

    suspend fun publishStatus(force: Boolean) {
        val partner = _partner.value ?: return
        if (partner.identity == null || settings.token == null) return
        val raw = collector.read()
        checkLowBattery(raw)
        val status = collector.toShared(raw, settings.shares, settings.quickStatus)
        val fingerprint = listOf(
            status.batteryPercent?.div(5),
            status.charging,
            status.network,
            status.signalLevel,
            status.ringer,
            status.doNotDisturb,
            status.utcOffsetMinutes,
            status.quickStatus,
            status.shares.sortedBy { it.name },
        ).joinToString("|")
        val now = System.currentTimeMillis()
        val sinceLast = now - settings.lastStatusSentAt
        if (!force) {
            if (sinceLast < STATUS_MIN_GAP_MS) return
            if (fingerprint == settings.lastStatusFingerprint && sinceLast < STATUS_REFRESH_MS) return
        }
        try {
            sendPayload(Payload.Status(status), KIND_STATUS, newId(), push = false)
        } catch (e: IOException) {
            Log.d(TAG, "status not sent: ${e.message}")
            return
        }
        settings.lastStatusFingerprint = fingerprint
        settings.lastStatusSentAt = now
    }

    /** Tells the partner automatically, once per discharge, when the battery is nearly empty. */
    private suspend fun checkLowBattery(raw: RawStatus) {
        val level = raw.batteryPercent ?: return
        if (raw.charging == true || level > LOW_BATTERY_RESET) {
            if (settings.lowBatteryAlertSent) settings.lowBatteryAlertSent = false
            return
        }
        if (level <= CRITICAL_BATTERY && settings.lowBatteryAlerts && !settings.lowBatteryAlertSent) {
            settings.lowBatteryAlertSent = true
            val now = System.currentTimeMillis()
            dao.insert(
                MessageEntity(
                    id = newId(),
                    fromMe = true,
                    kind = MessageKind.ALERT,
                    body = AlertKind.BATTERY_CRITICAL.name,
                    batteryPercent = level,
                    sentAtMs = now,
                    sortAtMs = now,
                    state = MessageState.PENDING,
                ),
            )
            flushOutbox()
        }
    }

    // ---- internals: receiving ----------------------------------------------------------

    /** Downloads and handles everything waiting on the server. */
    suspend fun sync() {
        if (settings.token == null) return
        var after = 0L
        repeat(MAX_SYNC_PAGES) {
            val batch = api.envelopes(after)
            if (batch.isEmpty()) return
            process(batch, viaSocket = false)
            after = batch.last().seq ?: return
            if (batch.size < SYNC_PAGE_SIZE) return
        }
    }

    private suspend fun handleFrame(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.Ready -> frame.partner?.let { _presence.value = it }
            is ServerFrame.Envelope -> process(listOf(frame.toDto()), viaSocket = true)
            is ServerFrame.Presence -> {
                _presence.value = PresenceDto(frame.online, frame.lastSeenMs)
                if (!frame.online) _typing.value = false
            }
            is ServerFrame.Pairing -> refresh()
            is ServerFrame.Accepted, is ServerFrame.Rejected -> Unit
        }
    }

    private suspend fun process(envelopes: List<EnvelopeDto>, viaSocket: Boolean) {
        var gotSomethingNew = false
        val acks = mutableListOf<Long>()
        syncLock.withLock {
            for (envelope in envelopes) {
                val seq = envelope.seq
                if (seq != null && seq in processedSeqs) {
                    acks += seq
                    continue
                }
                val result = try {
                    handleEnvelope(envelope)
                } catch (e: IOException) {
                    Log.d(TAG, "stopping at envelope $seq: ${e.message}")
                    break
                }
                if (result == Handled.NEW_MESSAGE) gotSomethingNew = true
                if (seq != null) {
                    remember(seq)
                    acks += seq
                }
            }
        }
        if (acks.isNotEmpty() && !(viaSocket && realtime.sendAck(acks))) {
            quietly { api.ack(acks) }
        }
        if (gotSomethingNew) {
            if (chatVisible && foreground) {
                markChatRead()
            } else {
                notifications.showConversation(partnerName, dao.unreadIncoming(8), settings.hideNotificationText)
            }
        }
    }

    private enum class Handled { DONE, NEW_MESSAGE }

    private suspend fun handleEnvelope(envelope: EnvelopeDto): Handled {
        when (envelope.kind) {
            KIND_DELIVERED -> {
                dao.markDelivered(envelope.id, envelope.atMs)
                return Handled.DONE
            }
            KIND_MESSAGE, KIND_STATUS, KIND_EPHEMERAL -> Unit
            else -> return Handled.DONE
        }
        val body = envelope.body ?: return Handled.DONE
        val payload = decrypt(Ciphertext(envelope.messageType ?: 1, body)) ?: return Handled.DONE
        return onPayload(payload, envelope)
    }

    /** Null means the message can never be decrypted and is dropped. */
    private suspend fun decrypt(ciphertext: Ciphertext): Payload? {
        for (attempt in 0..1) {
            if (attempt == 1 || _partner.value?.identity == null) {
                // The partner may have new keys that we haven't fetched yet.
                refreshPartner()
            }
            val identity = _partner.value?.identity?.toCore() ?: continue
            try {
                return crypto.decrypt(identity, ciphertext).also { _secure.value = true }
            } catch (e: CoreException) {
                Log.w(TAG, "could not decrypt: ${e.message}")
                if (ciphertext.messageType != 0) return null
            }
        }
        return null
    }

    private suspend fun refreshPartner() {
        bootstrapLock.withLock { applyPartner(api.me().partner) }
    }

    private suspend fun onPayload(payload: Payload, envelope: EnvelopeDto): Handled {
        val now = System.currentTimeMillis()
        when (payload) {
            is Payload.Hello -> Unit
            is Payload.Text -> {
                setTyping(false)
                return insertIncoming(
                    MessageEntity(
                        id = payload.id,
                        fromMe = false,
                        kind = MessageKind.TEXT,
                        body = payload.body,
                        sentAtMs = payload.sentAtMs,
                        sortAtMs = envelope.atMs,
                        state = MessageState.DELIVERED,
                        deliveredAtMs = now,
                        replyTo = payload.replyTo,
                    ),
                )
            }
            is Payload.Read -> dao.markReadByPartner(payload.ids, payload.readAtMs)
            is Payload.Typing -> setTyping(payload.active)
            is Payload.Status -> {
                _partnerStatus.value = payload.status to now
                settings.partnerStatusJson = payloadToJson(payload)
                settings.partnerStatusReceivedAt = now
            }
            is Payload.Nudge -> {
                setTyping(false)
                val handled = insertIncoming(
                    MessageEntity(
                        id = payload.id,
                        fromMe = false,
                        kind = MessageKind.NUDGE,
                        body = payload.kind.name,
                        sentAtMs = payload.sentAtMs,
                        sortAtMs = envelope.atMs,
                        state = MessageState.DELIVERED,
                        deliveredAtMs = now,
                    ),
                )
                if (handled == Handled.NEW_MESSAGE) _incomingNudges.tryEmit(payload.kind)
                return handled
            }
            is Payload.Alert -> return insertIncoming(
                MessageEntity(
                    id = payload.id,
                    fromMe = false,
                    kind = MessageKind.ALERT,
                    body = payload.kind.name,
                    batteryPercent = payload.batteryPercent,
                    sentAtMs = payload.sentAtMs,
                    sortAtMs = envelope.atMs,
                    state = MessageState.DELIVERED,
                    deliveredAtMs = now,
                ),
            )
            is Payload.Unknown -> Unit
        }
        return Handled.DONE
    }

    private suspend fun insertIncoming(message: MessageEntity): Handled =
        if (dao.insert(message) != -1L) Handled.NEW_MESSAGE else Handled.DONE

    private fun setTyping(active: Boolean) {
        _typing.value = active
        typingReset?.cancel()
        if (active) {
            typingReset = scope.launch {
                delay(TYPING_TIMEOUT_MS)
                _typing.value = false
            }
        }
    }

    private fun remember(seq: Long) {
        processedSeqs += seq
        if (processedSeqs.size > 2000) processedSeqs.remove(processedSeqs.first())
    }

    // ---- internals: housekeeping -------------------------------------------------------

    private suspend fun wipeConversation() {
        dao.deleteAll()
        crypto.forgetSessions(null)
        settings.clearPartner()
        _partner.value = null
        _presence.value = null
        _partnerStatus.value = null
        _typing.value = false
        _secure.value = false
        _keyChanged.value = false
        _verifiedKey.value = null
        notifications.clearAll()
    }

    private suspend fun wipeEverything() {
        realtime.stop()
        wipeConversation()
        crypto.reset()
        settings.clearAll()
        _me.value = null
        _invite.value = null
        _displayName.value = ""
        _sharingConfirmed.value = false
        _signedIn.value = false
    }

    private fun loadCachedPartner(): PartnerDto? = settings.partnerJson?.let {
        runCatching { MamiJson.decodeFromString(PartnerDto.serializer(), it) }.getOrNull()
    }

    private fun loadCachedStatus(): Pair<DeviceStatus, Long>? {
        val json = settings.partnerStatusJson ?: return null
        val payload = runCatching { payloadFromJson(json) }.getOrNull() as? Payload.Status ?: return null
        return payload.status to settings.partnerStatusReceivedAt
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch { quietly(block) }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "background task failed: ${e.message}")
        }
    }

    private fun newId() = UUID.randomUUID().toString()

    private companion object {
        const val TAG = "MaMi.Messenger"
        const val KIND_MESSAGE = "message"
        const val KIND_STATUS = "status"
        const val KIND_EPHEMERAL = "ephemeral"
        const val KIND_DELIVERED = "delivered"
        const val MIN_ONE_TIME_KEYS = 5
        const val ONE_TIME_KEY_BATCH = 20
        const val SYNC_PAGE_SIZE = 500
        const val MAX_SYNC_PAGES = 20
        const val TYPING_INTERVAL_MS = 4_000L
        const val TYPING_TIMEOUT_MS = 8_000L
        const val STATUS_MIN_GAP_MS = 60_000L
        const val STATUS_REFRESH_MS = 10 * 60_000L
        const val CRITICAL_BATTERY = 5
        const val LOW_BATTERY_RESET = 15
    }
}
