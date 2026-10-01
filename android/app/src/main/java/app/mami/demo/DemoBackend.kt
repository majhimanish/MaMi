package app.mami.demo

import android.net.Uri
import app.mami.calls.CallOutcome
import app.mami.core.DeviceStatus
import app.mami.core.LinkPreview
import app.mami.core.NetworkKind
import app.mami.core.NudgeKind
import app.mami.core.QuickStatus
import app.mami.core.RingerMode
import app.mami.core.ShareKind
import app.mami.data.ApiException
import app.mami.data.ConnectionState
import app.mami.data.IdentityDto
import app.mami.data.InviteDto
import app.mami.data.PartnerDto
import app.mami.data.PresenceDto
import app.mami.data.SavedQuickStatus
import app.mami.data.db.MediaType
import app.mami.data.db.MessageEntity
import app.mami.data.db.MessageKind
import app.mami.data.db.MessageState
import app.mami.data.db.Transfer
import app.mami.media.MediaException
import app.mami.media.MediaLibrary
import app.mami.media.PreparedMedia
import app.mami.media.VoiceRecording
import app.mami.sync.MamiBackend
import java.io.File
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A pretend server and partner, for debug builds and screenshots. Everything
 * happens in memory: messages tick through their states, the partner types
 * and replies, and the playground can change the partner's phone at will.
 */
class DemoBackend(
    private val scope: CoroutineScope,
    /** Where pretend photos and voice notes are written. Without it, attachments show as thumbnails only. */
    private val media: MediaLibrary? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : MamiBackend {
    private val demoMedia = media?.let(::DemoMedia)
    private val allShares = ShareKind.entries.toSet() - ShareKind.UNKNOWN

    private val _signedIn = MutableStateFlow(false)
    override val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _displayName = MutableStateFlow("")
    override val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _sharingConfirmed = MutableStateFlow(false)
    override val sharingConfirmed: StateFlow<Boolean> = _sharingConfirmed.asStateFlow()

    private val _invite = MutableStateFlow<InviteDto?>(null)
    override val invite: StateFlow<InviteDto?> = _invite.asStateFlow()

    private val _partner = MutableStateFlow<PartnerDto?>(null)
    override val partner: StateFlow<PartnerDto?> = _partner.asStateFlow()

    private val _presence = MutableStateFlow<PresenceDto?>(null)
    override val presence: StateFlow<PresenceDto?> = _presence.asStateFlow()

    private val _partnerStatus = MutableStateFlow<Pair<DeviceStatus, Long>?>(null)
    override val partnerStatus: StateFlow<Pair<DeviceStatus, Long>?> = _partnerStatus.asStateFlow()

    private val _typing = MutableStateFlow(false)
    override val partnerTyping: StateFlow<Boolean> = _typing.asStateFlow()

    private val _secure = MutableStateFlow(false)
    override val secure: StateFlow<Boolean> = _secure.asStateFlow()

    private val _keyChanged = MutableStateFlow(false)
    override val partnerKeyChanged: StateFlow<Boolean> = _keyChanged.asStateFlow()

    private val _verified = MutableStateFlow<String?>(null)
    override val verifiedPartnerKey: StateFlow<String?> = _verified.asStateFlow()

    private val _nudges = MutableSharedFlow<NudgeKind>(extraBufferCapacity = 8)
    override val incomingNudges: SharedFlow<NudgeKind> = _nudges

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    override val messages: Flow<List<MessageEntity>> = _messages

    private val _connection = MutableStateFlow(ConnectionState.Connected)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _shares = MutableStateFlow(allShares)
    override val shares: StateFlow<Set<ShareKind>> = _shares.asStateFlow()

    private val _quickStatus = MutableStateFlow<SavedQuickStatus?>(null)
    override val quickStatus: StateFlow<SavedQuickStatus?> = _quickStatus.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val transferProgress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    /** Pretend calls with Maya; she answers when [autoReply] is on. */
    override val calls = DemoCalls(scope, autoAnswer = { autoReply }) { id, outgoing, video, outcome, startedAt, duration ->
        val missed = !outgoing && outcome != CallOutcome.ANSWERED && outcome != CallOutcome.DECLINED
        val message = MessageEntity(
            id = id, fromMe = outgoing, kind = MessageKind.CALL, body = "", sentAtMs = startedAt, sortAtMs = startedAt,
            state = if (outgoing) MessageState.READ else MessageState.DELIVERED, deliveredAtMs = startedAt,
            readAtMs = if (missed && !chatVisible) null else clock(), readReceiptSent = true,
            mediaKind = if (video) MediaType.VIDEO else MediaType.VOICE, mediaDurationMs = duration, callOutcome = outcome,
        )
        _messages.update { (it + message).sortedBy(MessageEntity::sortAtMs) }
    }

    override val partnerName: String get() = _partner.value?.displayName ?: "Your partner"
    override var email: String? = null
        private set
    override var serverUrl: String = "demo (simulated partner)"
    override var lowBatteryAlerts: Boolean = true
    override var hideNotificationText: Boolean = false
    override var linkPreviews: Boolean = true
    override var chatVisible: Boolean = false

    /** Whether the pretend partner reads, types and replies on their own. */
    var autoReply = true

    private var typingJob: Job? = null

    // ---- sign-in and setup -----------------------------------------------------------

    override suspend fun requestCode(email: String) {
        delay(600)
        if (!email.contains('@')) throw ApiException(400, "bad_email")
        this.email = email.trim().lowercase()
    }

    override suspend fun verifyCode(email: String, code: String) {
        delay(500)
        if (code == "000000") throw ApiException(400, "wrong_code")
        this.email = email.trim().lowercase()
        _signedIn.value = true
    }

    override suspend fun setDisplayName(name: String) {
        delay(250)
        _displayName.value = name.trim()
    }

    override fun confirmSharing(shares: Set<ShareKind>, lowBatteryAlerts: Boolean) {
        _shares.value = shares
        this.lowBatteryAlerts = lowBatteryAlerts
        _sharingConfirmed.value = true
    }

    override fun setShares(shares: Set<ShareKind>) {
        _shares.value = shares
    }

    override fun setQuickStatus(status: SavedQuickStatus?) {
        _quickStatus.value = status
    }

    // ---- pairing ---------------------------------------------------------------------

    override suspend fun createInvite(partnerEmail: String?): InviteDto {
        delay(500)
        val invite = InviteDto("LOVE-2026", partnerEmail?.ifBlank { null }, clock() + 7 * DAY)
        _invite.value = invite
        if (autoReply) {
            scope.launch {
                delay(6000)
                if (_invite.value == invite && _partner.value == null) pair(withHistory = false)
            }
        }
        return invite
    }

    override suspend fun cancelInvite() {
        delay(200)
        _invite.value = null
    }

    override suspend fun acceptInvite(code: String) {
        delay(700)
        if (code.count(Char::isLetterOrDigit) < 8) throw ApiException(404, "invite_not_found")
        pair(withHistory = false)
    }

    override suspend fun unpair() {
        delay(400)
        clearPartner()
    }

    override suspend fun signOut() {
        delay(300)
        reset()
    }

    override suspend fun deleteAccount() {
        delay(500)
        reset()
    }

    override suspend fun refresh() {
        delay(400)
    }

    // ---- security --------------------------------------------------------------------

    override suspend fun safetyCode(): String? =
        if (_partner.value == null) null else "52819 04471 83302 61957 20486 77135 39028 14460"

    override fun markPartnerVerified() {
        _verified.value = PARTNER_KEY
        _keyChanged.value = false
    }

    override fun dismissKeyChanged() {
        _keyChanged.value = false
    }

    // ---- conversation -----------------------------------------------------------------

    override fun sendText(text: String, replyTo: String?, link: LinkPreview?) {
        val body = text.trim()
        if (body.isEmpty()) return
        val id = send(MessageKind.TEXT, body) {
            it.copy(replyTo = replyTo, linkUrl = link?.url, linkTitle = link?.title, linkDescription = link?.description, linkImage = link?.image)
        }
        if (autoReply) {
            scope.launch {
                replyLater(id) {
                    if ("love" in body.lowercase()) partnerReacts(id, "❤️")
                    receiveText(replyFor(body))
                }
            }
        }
    }

    // ---- attachments ------------------------------------------------------------------

    override suspend fun sendMedia(uris: List<Uri>, caption: String?, viewOnce: Boolean, asDocument: Boolean, replyTo: String?) {
        val library = media ?: throw MediaException("Attachments need a real phone.")
        uris.forEachIndexed { index, uri ->
            val prepared = library.prepare(uri, asDocument)
            sendPrepared(prepared, if (index == 0) caption else null, viewOnce, if (index == 0) replyTo else null)
        }
    }

    override fun sendVoice(recording: VoiceRecording, replyTo: String?) {
        val library = media ?: return
        sendPrepared(library.prepareVoice(recording.file, recording.durationMs, recording.levels), null, false, replyTo)
    }

    override fun newVoiceFile(): File? = media?.newFile("m4a")

    override fun fileFor(message: MessageEntity): File? =
        message.mediaFile?.takeIf { it.isNotEmpty() }?.let { media?.file(it) }?.takeIf(File::exists)

    override fun retryTransfer(id: String) {
        val message = _messages.value.firstOrNull { it.id == id } ?: return
        scope.launch {
            if (message.fromMe) {
                update(id) { it.copy(transfer = Transfer.UPLOAD) }
                upload(id)
            } else {
                update(id) { it.copy(transfer = Transfer.DOWNLOAD) }
                download(id)
            }
        }
    }

    override suspend fun saveToDevice(message: MessageEntity): Boolean {
        val library = media ?: return false
        val file = message.mediaFile ?: return false
        return library.saveToDevice(file, message.mediaKind, message.mediaMime ?: "application/octet-stream", message.mediaName)
    }

    private fun sendPrepared(prepared: PreparedMedia, caption: String?, viewOnce: Boolean, replyTo: String?) {
        val id = send(MessageKind.MEDIA, caption?.trim().orEmpty(), deliverNow = false) {
            it.copy(
                replyTo = replyTo,
                transfer = Transfer.UPLOAD,
                viewOnce = viewOnce && (prepared.kind == MediaType.PHOTO || prepared.kind == MediaType.VIDEO),
            ).withMedia(prepared)
        }
        scope.launch {
            upload(id)
            if (autoReply) {
                replyLater(id) {
                    when (prepared.kind) {
                        MediaType.PHOTO -> {
                            partnerReacts(id, "😍")
                            receiveText(if (viewOnce) "Ooh a secret one 🙈" else "Aww look at you 🥹")
                        }
                        MediaType.VIDEO -> receiveText("Haha I watched it three times 😂")
                        MediaType.VOICE -> {
                            partnerReacts(id, "❤️")
                            receiveText("Your voice 🥰 I needed that")
                        }
                        else -> receiveText("Got it, thank you 💕")
                    }
                }
            }
        }
    }

    /** Pretend upload: a progress bar that fills over a second or two. */
    private suspend fun upload(id: String) {
        for (step in 0..10) {
            if (!_secure.value || _connection.value != ConnectionState.Connected) {
                _progress.update { it - id }
                return
            }
            _progress.update { it + (id to step / 10f) }
            delay(140)
        }
        _progress.update { it - id }
        update(id) { message ->
            message.copy(transfer = Transfer.DONE, blobId = "demo-blob").let {
                if (it.viewOnce) it.copy(mediaFile = null) else it
            }
        }
        deliver(id)
    }

    private suspend fun download(id: String) {
        for (step in 0..10) {
            _progress.update { it + (id to step / 10f) }
            delay(120)
        }
        _progress.update { it - id }
        update(id) { message -> message.copy(transfer = if (fileFor(message) != null) Transfer.DONE else Transfer.GONE) }
    }

    // ---- after sending -------------------------------------------------------------------

    override fun react(id: String, emoji: String?) = update(id) { it.copy(myReaction = emoji) }

    override fun edit(id: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        update(id) { if (it.fromMe && !it.unsent) it.copy(body = body, editedAtMs = clock(), linkUrl = null, linkTitle = null, linkDescription = null, linkImage = null) else it }
    }

    override fun unsend(id: String) {
        val message = _messages.value.firstOrNull { it.id == id && it.fromMe } ?: return
        media?.delete(message.mediaFile?.takeIf { it.isNotEmpty() })
        if (message.state == MessageState.PENDING) {
            _messages.update { list -> list.filterNot { it.id == id } }
        } else {
            update(id) { it.unsentCopy() }
        }
    }

    override fun deleteForMe(id: String) {
        _messages.value.firstOrNull { it.id == id }?.let { media?.delete(it.mediaFile?.takeIf { f -> f.isNotEmpty() }) }
        _messages.update { list -> list.filterNot { it.id == id } }
    }

    override fun setPinned(id: String, pinned: Boolean) = update(id) { it.copy(pinnedAtMs = if (pinned) clock() else null) }

    override fun setStarred(id: String, starred: Boolean) = update(id) { it.copy(starred = starred) }

    override fun viewOnceOpened(id: String) {
        val message = _messages.value.firstOrNull { it.id == id && !it.fromMe && it.viewOnce } ?: return
        media?.delete(message.mediaFile?.takeIf { it.isNotEmpty() })
        update(id) { it.copy(mediaFile = null, openedAtMs = clock(), transfer = Transfer.GONE) }
    }

    override suspend fun search(query: String): List<MessageEntity> {
        val words = query.trim()
        if (words.isEmpty()) return emptyList()
        return _messages.value
            .filter { !it.unsent && (it.kind == MessageKind.TEXT || it.kind == MessageKind.MEDIA) }
            .filter { m -> listOfNotNull(m.body, m.mediaName, m.linkTitle).any { it.contains(words, ignoreCase = true) } }
            .sortedByDescending { it.sortAtMs }
    }

    override suspend fun linkPreview(url: String): LinkPreview? {
        if (!linkPreviews) return null
        delay(500)
        val host = url.substringAfter("://").substringBefore('/').removePrefix("www.")
        val name = host.substringBefore('.').replaceFirstChar { it.uppercase() }
        return LinkPreview(url, "$name: a link worth sharing", "A preview made on your phone. MaMi's server never sees your links.", demoMedia?.let { withContext(Dispatchers.Default) { it.linkImage() } })
    }

    override fun sendNudge(kind: NudgeKind) {
        val id = send(MessageKind.NUDGE, kind.name)
        if (autoReply) {
            scope.launch {
                replyLater(id) {
                    when (kind) {
                        NudgeKind.MISS_YOU -> receiveText("Miss you more 🥺 come home soon")
                        NudgeKind.THINKING_OF_YOU -> receiveNudge(NudgeKind.THINKING_OF_YOU)
                        else -> receiveNudge(kind)
                    }
                }
            }
        }
    }

    override fun onComposerChanged(text: String) = Unit

    override fun markChatRead() {
        val now = clock()
        _messages.update { list -> list.map { if (!it.fromMe && it.readAtMs == null) it.copy(readAtMs = now) else it } }
    }

    // ---- playground controls ------------------------------------------------------------

    /** Signed out, nothing set up: the very first launch. */
    fun reset() {
        _signedIn.value = false
        _displayName.value = ""
        _sharingConfirmed.value = false
        _shares.value = allShares
        _quickStatus.value = null
        email = null
        clearPartner()
    }

    fun skipSignIn() {
        if (email == null) email = "you@example.com"
        _signedIn.value = true
    }

    fun skipProfile() {
        skipSignIn()
        if (_displayName.value.isBlank()) _displayName.value = "Sam"
    }

    fun skipSharing() {
        skipProfile()
        _sharingConfirmed.value = true
    }

    /** Paired with a pretend partner and a little conversation history. */
    fun skipPairing() {
        skipSharing()
        if (_partner.value == null) pair(withHistory = true)
    }

    fun setPartnerOnline(online: Boolean) {
        _presence.value = PresenceDto(online, if (online) null else clock())
        if (!online) _typing.value = false
    }

    fun setPartnerLastSeen(minutesAgo: Int) {
        _presence.value = PresenceDto(false, clock() - minutesAgo * MINUTE)
    }

    fun updatePartnerStatus(change: (DeviceStatus) -> DeviceStatus) {
        val current = _partnerStatus.value?.first ?: defaultStatus()
        _partnerStatus.value = change(current).copy(capturedAtMs = clock()) to clock()
    }

    fun setPartnerQuickStatus(emoji: String?, label: String?) {
        updatePartnerStatus { it.copy(quickStatus = if (emoji == null || label == null) null else QuickStatus(emoji, label, clock() + HOUR)) }
    }

    fun partnerTypes(forMs: Long = 4000) {
        typingJob?.cancel()
        _typing.value = true
        typingJob = scope.launch {
            delay(forMs)
            _typing.value = false
        }
    }

    fun receiveText(text: String = randomLine()) {
        _typing.value = false
        receive(MessageKind.TEXT, text)
    }

    fun receiveNudge(kind: NudgeKind) {
        receive(MessageKind.NUDGE, kind.name)
        _nudges.tryEmit(kind)
    }

    fun receiveBatteryAlert(percent: Int = 4) {
        receive(MessageKind.ALERT, "BATTERY_CRITICAL", battery = percent)
    }

    /** Battery ran out 25 minutes ago and nothing since: the "don't panic" case. */
    fun simulatePhoneDied() {
        val diedAt = clock() - 25 * MINUTE
        _partnerStatus.value = (_partnerStatus.value?.first ?: defaultStatus()).copy(
            capturedAtMs = diedAt,
            batteryPercent = 3,
            charging = false,
        ) to diedAt
        _presence.value = PresenceDto(false, diedAt - MINUTE)
        _typing.value = false
        receive(MessageKind.ALERT, "BATTERY_CRITICAL", battery = 4, at = diedAt - 2 * MINUTE)
    }

    /** Makes it 2:30 am where the partner is. */
    fun simulateNight() {
        val utcMinutes = Math.floorMod(clock() / MINUTE, 24L * 60).toInt()
        val offset = Math.floorMod(150 - utcMinutes + 12 * 60, 24 * 60) - 12 * 60
        updatePartnerStatus { it.copy(utcOffsetMinutes = offset, timezone = "Pacific/Auckland") }
        setPartnerLastSeen(95)
    }

    fun simulateKeyChange() {
        _keyChanged.value = true
        _verified.value = null
    }

    fun setSecure(secure: Boolean) {
        _secure.value = secure
        if (secure) flushPending()
    }

    /** This phone loses / regains its connection; messages wait meanwhile. */
    fun setOnline(online: Boolean) {
        _connection.value = if (online) ConnectionState.Connected else ConnectionState.Offline
        if (online) flushPending()
    }

    fun clearChat() {
        _messages.value.forEach { media?.delete(it.mediaFile?.takeIf { f -> f.isNotEmpty() }) }
        _messages.value = emptyList()
    }

    /** Maya sends a photo (a drawn sunset, beach or night sky). */
    fun receivePhoto(scene: DemoMedia.Scene = DemoMedia.Scene.entries.random(), viewOnce: Boolean = false) {
        val maker = demoMedia ?: return
        scope.launch {
            val prepared = withContext(Dispatchers.Default) { maker.photo(scene) }
            receiveMedia(prepared, if (viewOnce) "" else scene.caption, viewOnce)
        }
    }

    /** Maya sends a hummed voice note. */
    fun receiveVoice() {
        val maker = demoMedia ?: return
        scope.launch { receiveMedia(withContext(Dispatchers.Default) { maker.voice() }, "") }
    }

    /** Maya sends a short video (made on this phone's encoder, so it takes a few seconds). */
    fun receiveVideo() {
        val maker = demoMedia ?: return
        partnerTypes(4500)
        scope.launch { receiveMedia(withContext(Dispatchers.Default) { maker.video() }, "Golden hour ✨", askFirst = true) }
    }

    fun receiveDocument() {
        val maker = demoMedia ?: return
        scope.launch { receiveMedia(withContext(Dispatchers.Default) { maker.document() }, "Here's the plan 🗺️") }
    }

    fun receiveLink() {
        scope.launch {
            val url = "https://www.youtube.com/watch?v=lake-sunset"
            val preview = LinkPreview(url, "Lakeside sunset, 4K", "Ten quiet minutes of the sun setting over Phewa lake.", demoMedia?.let { withContext(Dispatchers.Default) { it.linkImage() } })
            receive(MessageKind.TEXT, "This made me think of us $url") {
                it.copy(linkUrl = preview.url, linkTitle = preview.title, linkDescription = preview.description, linkImage = preview.image)
            }
        }
    }

    /**
     * Adds one of every kind of attachment at once, synchronously (screenshots
     * and the playground): my photo with a caption she loved, her video, a PDF,
     * my voice note and a view-once photo from her.
     */
    fun showcaseMedia() {
        val maker = demoMedia ?: return
        val now = clock()
        fun mine(minutesAgo: Long, prepared: PreparedMedia, caption: String = "") = MessageEntity(
            UUID.randomUUID().toString(), true, MessageKind.MEDIA, caption,
            sentAtMs = now - minutesAgo * MINUTE, sortAtMs = now - minutesAgo * MINUTE, state = MessageState.READ,
            serverAtMs = now - minutesAgo * MINUTE + 300, deliveredAtMs = now - minutesAgo * MINUTE + 900,
            readAtMs = now - minutesAgo * MINUTE + 30_000, blobId = "demo-blob",
        ).withMedia(prepared)
        fun theirs(minutesAgo: Long, prepared: PreparedMedia, caption: String = "", transfer: Int = Transfer.DONE) = MessageEntity(
            UUID.randomUUID().toString(), false, MessageKind.MEDIA, caption,
            sentAtMs = now - minutesAgo * MINUTE - 400, sortAtMs = now - minutesAgo * MINUTE, state = MessageState.DELIVERED,
            deliveredAtMs = now - minutesAgo * MINUTE + 500, readAtMs = now - minutesAgo * MINUTE + 1000, blobId = "demo-blob",
            transfer = transfer,
        ).withMedia(prepared)
        val beach = maker.photo(DemoMedia.Scene.BEACH)
        val sunset = maker.photo(DemoMedia.Scene.SUNSET, 1080, 720)
        val still = maker.photo(DemoMedia.Scene.SUNSET, 480, 848).let { it.copy(kind = MediaType.VIDEO, mime = "video/mp4", durationMs = 14_000, fileName = "") }
        val added = listOf(
            mine(30, beach, "Our spot, last summer 🏖️").copy(theirReaction = "😍", starred = true),
            theirs(26, still, "Golden hour ✨", transfer = Transfer.ASK).copy(mediaSize = 8_400_000),
            theirs(22, maker.document(), "Here's the plan 🗺️"),
            mine(18, maker.voice(3.2)).copy(theirReaction = "❤️"),
            theirs(12, sunset, "").copy(viewOnce = true),
        )
        _messages.update { (it + added).sortedBy(MessageEntity::sortAtMs) }
    }

    /** A missed call from her and a call we had, for screenshots. */
    fun showcaseCalls() {
        val now = clock()
        fun call(minutesAgo: Long, outgoing: Boolean, video: Boolean, outcome: String, minutes: Long?) = MessageEntity(
            UUID.randomUUID().toString(), outgoing, MessageKind.CALL, "", sentAtMs = now - minutesAgo * MINUTE, sortAtMs = now - minutesAgo * MINUTE,
            state = MessageState.READ, readAtMs = now, readReceiptSent = true, mediaKind = if (video) MediaType.VIDEO else MediaType.VOICE,
            mediaDurationMs = minutes?.let { it * MINUTE + 17_000 }, callOutcome = outcome,
        )
        val added = listOf(
            call(20, outgoing = false, video = false, outcome = CallOutcome.MISSED, minutes = null),
            call(15, outgoing = true, video = true, outcome = CallOutcome.ANSWERED, minutes = 23),
        )
        _messages.update { (it + added).sortedBy(MessageEntity::sortAtMs) }
    }

    /** Maya reacts to my newest message. */
    fun partnerReacts(id: String? = null, emoji: String = "❤️") {
        val target = id ?: _messages.value.lastOrNull { it.fromMe && !it.unsent }?.id ?: return
        update(target) { it.copy(theirReaction = emoji) }
    }

    /** Maya edits her newest text. */
    fun partnerEdits() {
        val target = _messages.value.lastOrNull { !it.fromMe && it.kind == MessageKind.TEXT && !it.unsent } ?: return
        update(target.id) { it.copy(body = target.body + " (I mean it 💗)", editedAtMs = clock()) }
    }

    /** Maya unsends her newest message. */
    fun partnerUnsends() {
        val target = _messages.value.lastOrNull { !it.fromMe && !it.unsent && (it.kind == MessageKind.TEXT || it.kind == MessageKind.MEDIA) } ?: return
        media?.delete(target.mediaFile?.takeIf { it.isNotEmpty() })
        update(target.id) { it.unsentCopy() }
    }

    /** Maya pins her newest text. */
    fun partnerPins() {
        val target = _messages.value.lastOrNull { !it.fromMe && it.kind == MessageKind.TEXT && !it.unsent } ?: return
        update(target.id) { it.copy(pinnedAtMs = clock()) }
    }

    private fun receiveMedia(prepared: PreparedMedia, caption: String, viewOnce: Boolean = false, askFirst: Boolean = false) {
        val hasFile = prepared.fileName.isNotEmpty()
        val id = receive(MessageKind.MEDIA, caption) {
            it.copy(
                transfer = if (askFirst || !hasFile) Transfer.ASK else Transfer.DOWNLOAD,
                viewOnce = viewOnce,
                blobId = "demo-blob",
            ).withMedia(prepared)
        }
        if (hasFile && !askFirst) scope.launch { download(id) }
    }

    // ---- internals ----------------------------------------------------------------------

    private fun pair(withHistory: Boolean) {
        val now = clock()
        _invite.value = null
        _partner.value = PartnerDto(
            userId = "demo-partner",
            email = "maya@example.com",
            displayName = "Maya",
            pairedAtMs = now,
            identity = IdentityDto("demo-curve25519", PARTNER_KEY, "demo-signature"),
            presence = PresenceDto(true),
        )
        _presence.value = PresenceDto(true)
        _partnerStatus.value = defaultStatus() to now
        _secure.value = true
        if (withHistory) {
            _messages.value = history(now)
        } else if (autoReply) {
            scope.launch {
                delay(1500)
                partnerTypes(1800)
                delay(1800)
                receiveText("Hi love! It's me 💗 we're connected")
            }
        }
    }

    private fun clearPartner() {
        _invite.value = null
        _partner.value = null
        _presence.value = null
        _partnerStatus.value = null
        _typing.value = false
        _secure.value = false
        _keyChanged.value = false
        _verified.value = null
        _messages.value = emptyList()
    }

    private fun send(
        kind: String,
        body: String,
        deliverNow: Boolean = true,
        customize: (MessageEntity) -> MessageEntity = { it },
    ): String {
        val now = clock()
        val id = UUID.randomUUID().toString()
        _messages.update { it + customize(MessageEntity(id, true, kind, body, sentAtMs = now, sortAtMs = now, state = MessageState.PENDING)) }
        if (deliverNow) scope.launch { deliver(id) }
        return id
    }

    private suspend fun deliver(id: String) {
        if (!_secure.value || _connection.value != ConnectionState.Connected) return
        delay(350)
        val sentAt = clock()
        update(id) { it.copy(state = MessageState.SENT, serverAtMs = sentAt, sortAtMs = sentAt) }
        delay(700)
        update(id) { it.copy(state = MessageState.DELIVERED, deliveredAtMs = clock()) }
    }

    private suspend fun replyLater(id: String, reply: () -> Unit) {
        if (_presence.value?.online != true) return
        delay(2000)
        if (_messages.value.none { it.id == id && it.state >= MessageState.DELIVERED }) return
        update(id) { it.copy(state = MessageState.READ, readAtMs = clock()) }
        delay(500)
        partnerTypes(2200)
        delay(2200)
        reply()
    }

    private fun flushPending() {
        _messages.value.filter { it.fromMe && it.state == MessageState.PENDING }.forEach {
            scope.launch { if (it.transfer == Transfer.UPLOAD) upload(it.id) else deliver(it.id) }
        }
    }

    private fun receive(
        kind: String,
        body: String,
        battery: Int? = null,
        at: Long = clock(),
        customize: (MessageEntity) -> MessageEntity = { it },
    ): String {
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            fromMe = false,
            kind = kind,
            body = body,
            batteryPercent = battery,
            sentAtMs = at - 400,
            sortAtMs = at,
            state = MessageState.DELIVERED,
            deliveredAtMs = at,
            readAtMs = if (chatVisible) clock() else null,
        )
        _messages.update { (it + customize(message)).sortedBy(MessageEntity::sortAtMs) }
        return message.id
    }

    private fun update(id: String, change: (MessageEntity) -> MessageEntity) {
        _messages.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun MessageEntity.withMedia(prepared: PreparedMedia) = copy(
        mediaKind = prepared.kind,
        mediaFile = prepared.fileName.ifEmpty { null },
        mediaMime = prepared.mime,
        mediaName = prepared.name,
        mediaSize = prepared.size,
        mediaWidth = prepared.width,
        mediaHeight = prepared.height,
        mediaDurationMs = prepared.durationMs,
        mediaThumb = prepared.thumbnail,
        mediaWaveform = prepared.waveform,
    )

    private fun MessageEntity.unsentCopy() = copy(
        unsent = true, body = "", mediaFile = null, mediaThumb = null, mediaWaveform = null, blobId = null,
        transfer = Transfer.DONE, linkUrl = null, linkTitle = null, linkDescription = null, linkImage = null,
        myReaction = null, theirReaction = null, pinnedAtMs = null, starred = false,
    )

    private fun defaultStatus() = DeviceStatus(
        capturedAtMs = clock(),
        batteryPercent = 76,
        charging = false,
        network = NetworkKind.WIFI,
        signalLevel = 3,
        ringer = RingerMode.NORMAL,
        doNotDisturb = false,
        timezone = "Asia/Kathmandu",
        utcOffsetMinutes = 345,
        quickStatus = null,
        shares = allShares.toList(),
        platform = "android",
    )

    private fun history(now: Long): List<MessageEntity> {
        val maker = demoMedia
        fun mine(minutesAgo: Long, body: String, state: Int = MessageState.READ, kind: String = MessageKind.TEXT): MessageEntity {
            val at = now - minutesAgo * MINUTE
            return MessageEntity(
                UUID.randomUUID().toString(), true, kind, body, sentAtMs = at, sortAtMs = at, state = state,
                serverAtMs = at + 300,
                deliveredAtMs = if (state >= MessageState.DELIVERED) at + 1200 else null,
                readAtMs = if (state >= MessageState.READ) at + 60_000 else null,
                readReceiptSent = true,
            )
        }
        fun theirs(minutesAgo: Long, body: String, kind: String = MessageKind.TEXT): MessageEntity {
            val at = now - minutesAgo * MINUTE
            return MessageEntity(
                UUID.randomUUID().toString(), false, kind, body, sentAtMs = at - 500, sortAtMs = at,
                state = MessageState.DELIVERED, deliveredAtMs = at + 800, readAtMs = at + 90_000, readReceiptSent = true,
            )
        }
        val night = maker?.photo(DemoMedia.Scene.NIGHT)
        val voice = maker?.voice()
        val linkImage = maker?.linkImage()
        val momo = "https://www.google.com/maps/place/momo-house"
        return listOfNotNull(
            theirs(14 * 60 + 20, "Goodnight 🌙 sleep well"),
            night?.let { theirs(14 * 60 + 19, DemoMedia.Scene.NIGHT.caption, MessageKind.MEDIA).withMedia(it) },
            mine(14 * 60 + 18, "Night love 💗 talk tomorrow").copy(theirReaction = "❤️"),
            theirs(9 * 60, NudgeKind.THINKING_OF_YOU.name, MessageKind.NUDGE),
            theirs(8 * 60 + 58, "Good morning sunshine ☀️").copy(pinnedAtMs = now - 8 * HOUR),
            mine(8 * 60 + 40, "Morning! Coffee first ☕"),
            mine(8 * 60 + 39, "Then you 😌").copy(theirReaction = "🥰"),
            theirs(3 * 60 + 5, "Lunch at 1? $momo").copy(
                linkUrl = momo,
                linkTitle = "Momo House · Lakeside",
                linkDescription = "★ 4.8 · Nepali dumplings · open until 10 pm",
                linkImage = linkImage,
            ),
            mine(3 * 60 + 2, "Yes!! The momo place 🥟"),
            theirs(3 * 60, "😍"),
            voice?.let { theirs(70, "", MessageKind.MEDIA).withMedia(it) },
            mine(42, "Leaving work early today", state = MessageState.READ).copy(myReaction = null),
            theirs(40, "Yay! Drive safe 🚗"),
            mine(6, "Almost home", state = MessageState.DELIVERED),
        )
    }

    private fun replyFor(text: String): String {
        val lower = text.lowercase()
        return when {
            "love" in lower -> "Love you more 💗"
            "miss" in lower -> "Miss you too 🥺"
            "night" in lower -> "Goodnight 🌙 sweet dreams"
            "morning" in lower -> "Good morning ☀️"
            lower.startsWith("hi") || lower.startsWith("hey") || lower.startsWith("hello") -> "Heyyy 🥰"
            lower.endsWith("?") -> listOf("Hmm let me think 🤔", "Yes! 😄", "What do you think? 😏").random()
            text.none(Char::isLetterOrDigit) -> listOf("😂", "🥰", "💗💗💗").random()
            else -> randomLine()
        }
    }

    private fun randomLine() = listOf(
        "Haha 😂",
        "Aww 🥹",
        "Tell me more!",
        "Same here 💕",
        "On my way 🚗",
        "Can't wait to see you",
        "You're the best 🥰",
    ).random(Random(clock()))

    private companion object {
        const val PARTNER_KEY = "demo-ed25519"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
