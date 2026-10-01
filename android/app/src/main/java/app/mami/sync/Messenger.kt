package app.mami.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Base64
import android.util.Log
import app.mami.calls.CallOutcome
import app.mami.calls.CallSignals
import app.mami.calls.Calls
import app.mami.core.AlertKind
import app.mami.core.CheckInKind
import app.mami.core.Ciphertext
import app.mami.core.CoreException
import app.mami.core.DeviceStatus
import app.mami.core.LinkPreview
import app.mami.core.MediaInfo
import app.mami.core.MediaKind
import app.mami.core.NudgeKind
import app.mami.core.Payload
import app.mami.core.ShareKind
import app.mami.core.TogetherKey
import app.mami.core.dailyQuestion
import app.mami.core.decryptFile
import app.mami.core.encryptFile
import app.mami.core.payloadFromJson
import app.mami.core.payloadToJson
import app.mami.core.questionDay
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
import app.mami.data.Moods
import app.mami.data.PartnerDto
import app.mami.data.PresenceDto
import app.mami.data.Realtime
import app.mami.data.SavedQuickStatus
import app.mami.data.SendRequestDto
import app.mami.data.ServerFrame
import app.mami.data.Settings
import app.mami.data.SharedLocation
import app.mami.data.TogetherInfo
import app.mami.data.pausedIndefinitely
import app.mami.data.db.AnswerEntity
import app.mami.data.db.MamiDatabase
import app.mami.data.db.MediaType
import app.mami.data.db.MessageEntity
import app.mami.data.db.MessageKind
import app.mami.data.db.MessageState
import app.mami.data.db.MoodEntity
import app.mami.data.db.OutboxEntity
import app.mami.data.db.Transfer
import app.mami.device.DeviceStatusCollector
import app.mami.device.Driving
import app.mami.device.RawStatus
import app.mami.location.LocationService
import app.mami.media.LinkPreviewer
import app.mami.media.MediaLibrary
import app.mami.media.PreparedMedia
import app.mami.media.VoicePlayer
import app.mami.media.VoiceRecording
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

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
    private val media: MediaLibrary,
    private val linkPreviewer: LinkPreviewer,
    private val scope: CoroutineScope,
) : MamiBackend, CallSignals {
    private val dao = db.messages()
    private val outbox = db.outbox()
    private val togetherDao = db.together()

    private val _signedIn = MutableStateFlow(settings.token != null)
    override val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _displayName = MutableStateFlow(settings.displayName)
    override val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _sharingConfirmed = MutableStateFlow(settings.sharingConfirmed)
    override val sharingConfirmed: StateFlow<Boolean> = _sharingConfirmed.asStateFlow()

    private val _me = MutableStateFlow<MeDto?>(null)
    val me: StateFlow<MeDto?> = _me.asStateFlow()

    private val _invite = MutableStateFlow<InviteDto?>(null)
    override val invite: StateFlow<InviteDto?> = _invite.asStateFlow()

    private val _partner = MutableStateFlow(loadCachedPartner())
    override val partner: StateFlow<PartnerDto?> = _partner.asStateFlow()

    private val _presence = MutableStateFlow(_partner.value?.presence)
    override val presence: StateFlow<PresenceDto?> = _presence.asStateFlow()

    private val _partnerStatus = MutableStateFlow(loadCachedStatus())
    /** The partner's last status and when it arrived. */
    override val partnerStatus: StateFlow<Pair<DeviceStatus, Long>?> = _partnerStatus.asStateFlow()

    private val _typing = MutableStateFlow(false)
    override val partnerTyping: StateFlow<Boolean> = _typing.asStateFlow()

    private val _secure = MutableStateFlow(false)
    /** An end-to-end encrypted session with the partner's current phone exists. */
    override val secure: StateFlow<Boolean> = _secure.asStateFlow()

    private val _keyChanged = MutableStateFlow(settings.partnerKeyChanged)
    override val partnerKeyChanged: StateFlow<Boolean> = _keyChanged.asStateFlow()

    private val _verifiedKey = MutableStateFlow(settings.verifiedPartnerKey)
    override val verifiedPartnerKey: StateFlow<String?> = _verifiedKey.asStateFlow()

    private val _incomingNudges = MutableSharedFlow<NudgeKind>(extraBufferCapacity = 8)
    override val incomingNudges: SharedFlow<NudgeKind> = _incomingNudges

    override val messages: Flow<List<MessageEntity>> = dao.observeAll()
    override val connection: StateFlow<ConnectionState> = realtime.state
    override val shares: StateFlow<Set<ShareKind>> = settings.sharesFlow
    override val quickStatus: StateFlow<SavedQuickStatus?> = settings.quickStatusFlow

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val transferProgress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    /** Set by the chat screen while it is visible. */
    @Volatile
    override var chatVisible = false

    @Volatile
    private var foreground = false

    private val bootstrapLock = Mutex()
    private val syncLock = Mutex()
    private val outboxLock = Mutex()
    private val transferLock = Mutex()

    @Volatile
    private var transfersRequested = false

    /** Set by [app.mami.AppGraph]: the call engine. */
    override lateinit var calls: Calls

    @Volatile
    private var inCall = false
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
        kickTransfers()
    }

    fun onBackground() {
        foreground = false
        // A call keeps the live connection, so hang-ups and network changes still arrive.
        if (!inCall) realtime.stop()
        launchSafely { publishStatus(force = false) }
    }

    fun onNetworkAvailable() {
        if (settings.token == null) return
        realtime.kick()
        launchSafely {
            flushOutbox()
            sync()
        }
        kickTransfers()
    }

    /** Something about the phone changed (battery, charging, ringer, network). */
    fun onDeviceChanged() {
        if (settings.token == null) return
        launchSafely { publishStatus(force = false) }
    }

    /** Periodic background work: catch up, retry, refresh our status. */
    suspend fun backgroundTick() {
        if (settings.token == null) return
        checkIdle()
        quietly { onAlarm() }
        quietly { refresh() }
        quietly { sync() }
        quietly { flushOutbox() }
        quietly { publishStatus(force = false) }
        quietly { runTransfers() }
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

    override suspend fun requestCode(email: String) = api.authStart(email.trim())

    override suspend fun verifyCode(email: String, code: String) {
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

    override suspend fun setDisplayName(name: String) {
        val clean = name.trim()
        api.setDisplayName(clean)
        settings.displayName = clean
        _displayName.value = clean
    }

    override fun confirmSharing(shares: Set<ShareKind>, lowBatteryAlerts: Boolean) {
        settings.shares = shares
        settings.lowBatteryAlerts = lowBatteryAlerts
        settings.sharingConfirmed = true
        _sharingConfirmed.value = true
    }

    override fun setShares(shares: Set<ShareKind>) {
        settings.shares = shares
        launchSafely { publishStatus(force = true) }
    }

    override fun setQuickStatus(status: SavedQuickStatus?) {
        settings.quickStatus = status
        launchSafely { publishStatus(force = true) }
    }

    override var lowBatteryAlerts: Boolean
        get() = settings.lowBatteryAlerts
        set(value) {
            settings.lowBatteryAlerts = value
        }

    override var hideNotificationText: Boolean
        get() = settings.hideNotificationText
        set(value) {
            settings.hideNotificationText = value
        }

    override var serverUrl: String
        get() = settings.serverUrl
        set(value) {
            settings.serverUrl = value
        }

    override var linkPreviews: Boolean
        get() = settings.linkPreviews
        set(value) {
            settings.linkPreviews = value
        }

    override val email: String? get() = settings.email

    // ---- pairing ------------------------------------------------------------------

    override suspend fun createInvite(partnerEmail: String?): InviteDto =
        api.createInvite(partnerEmail?.trim()?.ifBlank { null }).also { _invite.value = it }

    override suspend fun cancelInvite() {
        api.cancelInvite()
        _invite.value = null
    }

    override suspend fun acceptInvite(code: String) {
        api.acceptInvite(code)
        refresh()
    }

    /** Either partner can leave at any time. The conversation is deleted on both phones. */
    override suspend fun unpair() {
        api.unpair()
        refresh()
    }

    override suspend fun signOut() {
        try {
            api.signOut()
        } catch (e: IOException) {
            Log.w(TAG, "sign-out request failed; signing out locally", e)
        }
        wipeEverything()
    }

    override suspend fun deleteAccount() {
        api.deleteAccount()
        wipeEverything()
    }

    /** The code both partners compare in person, or null before the partner has keys. */
    override suspend fun safetyCode(): String? {
        val partnerKey = _partner.value?.identity?.ed25519 ?: return null
        return app.mami.core.safetyCode(crypto.identity().ed25519, partnerKey)
    }

    override fun markPartnerVerified() {
        val key = _partner.value?.identity?.ed25519 ?: return
        settings.verifiedPartnerKey = key
        settings.partnerKeyChanged = false
        _verifiedKey.value = key
        _keyChanged.value = false
    }

    override fun dismissKeyChanged() {
        settings.partnerKeyChanged = false
        _keyChanged.value = false
    }

    override val partnerName: String
        get() = _partner.value?.let { p -> p.displayName.ifBlank { p.email.substringBefore('@') } } ?: "Your partner"

    // ---- conversation ---------------------------------------------------------------

    override fun sendText(text: String, replyTo: String?, link: LinkPreview?, deliverAt: Long?) {
        val body = text.trim()
        if (body.isEmpty()) return
        val now = System.currentTimeMillis()
        val scheduled = deliverAt?.takeIf { it > now }
        val message = MessageEntity(
            id = newId(),
            fromMe = true,
            kind = MessageKind.TEXT,
            body = body,
            sentAtMs = now,
            sortAtMs = scheduled ?: now,
            state = MessageState.PENDING,
            replyTo = replyTo,
            linkUrl = link?.url,
            linkTitle = link?.title,
            linkDescription = link?.description,
            linkImage = link?.image,
            scheduledAtMs = scheduled,
        )
        launchSafely {
            dao.insert(message)
            flushOutbox()
        }
        stopTyping()
    }

    // ---- attachments ------------------------------------------------------------------

    override suspend fun sendMedia(uris: List<Uri>, caption: String?, viewOnce: Boolean, asDocument: Boolean, replyTo: String?) {
        uris.forEachIndexed { index, uri ->
            val prepared = media.prepare(uri, asDocument)
            // A caption goes with the first photo of a batch, like a photo album.
            queueMedia(prepared, if (index == 0) caption else null, viewOnce, if (index == 0) replyTo else null)
        }
    }

    override fun sendVoice(recording: VoiceRecording, replyTo: String?) {
        launchSafely { queueMedia(media.prepareVoice(recording.file, recording.durationMs, recording.levels), null, false, replyTo) }
    }

    override fun newVoiceFile(): File = media.newFile("m4a")

    override fun fileFor(message: MessageEntity): File? = message.mediaFile?.let(media::file)?.takeIf(File::exists)

    private suspend fun queueMedia(prepared: PreparedMedia, caption: String?, viewOnce: Boolean, replyTo: String?) {
        val now = System.currentTimeMillis()
        dao.insert(
            MessageEntity(
                id = newId(),
                fromMe = true,
                kind = MessageKind.MEDIA,
                body = caption?.trim().orEmpty(),
                sentAtMs = now,
                sortAtMs = now,
                state = MessageState.PENDING,
                replyTo = replyTo,
                mediaKind = prepared.kind,
                mediaFile = prepared.fileName,
                mediaMime = prepared.mime,
                mediaName = prepared.name,
                mediaSize = prepared.size,
                mediaWidth = prepared.width,
                mediaHeight = prepared.height,
                mediaDurationMs = prepared.durationMs,
                mediaThumb = prepared.thumbnail,
                mediaWaveform = prepared.waveform,
                transfer = Transfer.UPLOAD,
                viewOnce = viewOnce && (prepared.kind == MediaType.PHOTO || prepared.kind == MediaType.VIDEO),
            ),
        )
        kickTransfers()
    }

    override fun retryTransfer(id: String) {
        launchSafely {
            val message = dao.get(id) ?: return@launchSafely
            when (message.transfer) {
                Transfer.FAILED_UPLOAD -> dao.setTransfer(id, Transfer.UPLOAD)
                Transfer.FAILED_DOWNLOAD, Transfer.ASK -> dao.setTransfer(id, Transfer.DOWNLOAD)
            }
            kickTransfers()
        }
    }

    override suspend fun saveToDevice(message: MessageEntity): Boolean {
        val file = message.mediaFile ?: return false
        return media.saveToDevice(file, message.mediaKind, message.mediaMime ?: "application/octet-stream", message.mediaName)
    }

    private fun kickTransfers() {
        if (settings.token == null) return
        launchSafely { runTransfers() }
    }

    /** Uploads and downloads waiting attachments, one at a time, oldest first. */
    private suspend fun runTransfers() {
        transfersRequested = true
        if (!transferLock.tryLock()) return
        try {
            while (transfersRequested) {
                transfersRequested = false
                for (message in dao.waitingTransfers()) {
                    val ok = when (message.transfer) {
                        Transfer.UPLOAD -> upload(message)
                        Transfer.DOWNLOAD -> download(message)
                        else -> true
                    }
                    // No connection: try again when there is one.
                    if (!ok) return
                }
            }
        } finally {
            transferLock.unlock()
        }
    }

    /** Encrypts and uploads one of my attachments. False means "offline, retry later". */
    private suspend fun upload(message: MessageEntity): Boolean {
        val source = fileFor(message)
        if (source == null) {
            dao.setTransfer(message.id, Transfer.FAILED_UPLOAD)
            return true
        }
        val encrypted = media.tempFile(".enc")
        try {
            val key = withContext(Dispatchers.IO) { encryptFile(source.path, encrypted.path) }
            setProgress(message.id, 0f)
            val blob = api.uploadBlob(encrypted) { done, total -> setProgress(message.id, done.toFloat() / total.coerceAtLeast(1)) }
            dao.markUploaded(message.id, blob.id, key.key)
            if (message.viewOnce) {
                // View once means once: not even the sender keeps a copy to look at again.
                media.delete(message.mediaFile)
                dao.clearMediaFile(message.id)
            }
            flushOutbox()
            return true
        } catch (e: ApiException) {
            Log.w(TAG, "upload of ${message.id} refused: ${e.code}")
            dao.setTransfer(message.id, Transfer.FAILED_UPLOAD)
            return true
        } catch (e: CoreException) {
            Log.w(TAG, "could not encrypt ${message.id}", e)
            dao.setTransfer(message.id, Transfer.FAILED_UPLOAD)
            return true
        } catch (e: IOException) {
            Log.d(TAG, "upload of ${message.id} waits: ${e.message}")
            return false
        } finally {
            encrypted.delete()
            clearProgress(message.id)
        }
    }

    /** Downloads and decrypts one of their attachments. False means "offline, retry later". */
    private suspend fun download(message: MessageEntity): Boolean {
        val blobId = message.blobId
        val key = message.blobKey
        if (blobId == null || key == null) {
            dao.setTransfer(message.id, Transfer.GONE)
            return true
        }
        val encrypted = media.tempFile(".enc")
        val out = media.newFile(MediaLibrary.extensionFor(message.mediaMime, message.mediaName))
        try {
            setProgress(message.id, 0f)
            api.downloadBlob(blobId, encrypted) { done, total -> setProgress(message.id, if (total > 0) done.toFloat() / total else 0f) }
            withContext(Dispatchers.IO) { decryptFile(encrypted.path, out.path, key) }
            dao.markDownloaded(message.id, out.name)
            // It's safely here; the server doesn't need to keep it.
            quietly { api.deleteBlob(blobId) }
            return true
        } catch (e: ApiException) {
            out.delete()
            dao.setTransfer(message.id, if (e.status == 404) Transfer.GONE else Transfer.FAILED_DOWNLOAD)
            return true
        } catch (e: CoreException) {
            Log.w(TAG, "attachment ${message.id} failed to decrypt", e)
            out.delete()
            dao.setTransfer(message.id, Transfer.FAILED_DOWNLOAD)
            return true
        } catch (e: IOException) {
            out.delete()
            Log.d(TAG, "download of ${message.id} waits: ${e.message}")
            return false
        } finally {
            encrypted.delete()
            clearProgress(message.id)
        }
    }

    private fun setProgress(id: String, value: Float) = _progress.update { it + (id to value.coerceIn(0f, 1f)) }

    private fun clearProgress(id: String) = _progress.update { it - id }

    /** Photos and voice notes always come straight away; big videos and files wait for Wi-Fi or a tap. */
    private fun autoDownload(kind: MediaKind, size: Long): Boolean {
        if (kind == MediaKind.PHOTO || kind == MediaKind.VOICE || size <= AUTO_DOWNLOAD_BYTES) return true
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return !connectivity.isActiveNetworkMetered
    }

    // ---- after sending -------------------------------------------------------------------

    override fun react(id: String, emoji: String?) {
        launchSafely {
            dao.setMyReaction(id, emoji)
            enqueue(Payload.Reaction(id, emoji, System.currentTimeMillis()))
        }
    }

    override fun edit(id: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        launchSafely {
            val now = System.currentTimeMillis()
            if (dao.edit(id, fromMe = true, body = body, atMs = now) > 0) enqueue(Payload.Edit(id, body, now))
        }
    }

    override fun unsend(id: String) {
        launchSafely {
            val message = dao.get(id)?.takeIf { it.fromMe } ?: return@launchSafely
            media.delete(message.mediaFile)
            if (message.hiddenUntil(System.currentTimeMillis())) {
                // A scheduled message nobody has seen yet: cancel it quietly on both phones.
                dao.delete(id)
                if (message.state != MessageState.PENDING) enqueue(Payload.Unsend(id, System.currentTimeMillis()))
                return@launchSafely
            }
            if (message.state == MessageState.PENDING) {
                // It never left this phone: just take it back.
                dao.delete(id)
                return@launchSafely
            }
            message.blobId?.let { blob -> quietly { api.deleteBlob(blob) } }
            dao.unsend(id, fromMe = true)
            enqueue(Payload.Unsend(id, System.currentTimeMillis()))
        }
    }

    override fun deleteForMe(id: String) {
        launchSafely {
            dao.get(id)?.let { media.delete(it.mediaFile) }
            dao.delete(id)
        }
    }

    override fun setPinned(id: String, pinned: Boolean) {
        launchSafely {
            val now = System.currentTimeMillis()
            dao.setPinned(id, if (pinned) now else null)
            enqueue(Payload.Pin(id, pinned, now))
        }
    }

    override fun setStarred(id: String, starred: Boolean) {
        launchSafely { dao.setStarred(id, starred) }
    }

    override fun viewOnceOpened(id: String) {
        launchSafely {
            val message = dao.get(id)?.takeIf { !it.fromMe && it.viewOnce } ?: return@launchSafely
            media.delete(message.mediaFile)
            val now = System.currentTimeMillis()
            dao.markOpened(id, now)
            enqueue(Payload.Opened(id, now))
        }
    }

    override suspend fun search(query: String): List<MessageEntity> {
        val words = query.trim()
        if (words.isEmpty()) return emptyList()
        val escaped = words.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return dao.search("%$escaped%")
    }

    override suspend fun linkPreview(url: String): LinkPreview? = if (settings.linkPreviews) linkPreviewer.fetch(url) else null

    // ---- together ------------------------------------------------------------------------

    override val together: StateFlow<TogetherInfo> = settings.togetherFlow
    override val answers: Flow<List<AnswerEntity>> = togetherDao.observeAnswers()
    override val moods: Flow<List<MoodEntity>> = togetherDao.observeMoods()
    override val partnerLocation: StateFlow<SharedLocation?> = settings.partnerLocationFlow
    override val myLocationUntil: StateFlow<Long?> = settings.locationUntilFlow
    override val sharingPausedUntil: StateFlow<Long?> = settings.pausedUntilFlow

    override fun setTogether(key: TogetherKey, value: String?) {
        val now = System.currentTimeMillis()
        settings.together = applyTogether(settings.together, key, value, now)
        launchSafely { enqueue(Payload.Together(key, value, now)) }
    }

    override fun answerQuestion(text: String) {
        val answer = text.trim()
        if (answer.isEmpty()) return
        val now = System.currentTimeMillis()
        val day = questionDay(now)
        val question = dailyQuestion(day)
        launchSafely {
            val existing = togetherDao.answer(day)
            togetherDao.putAnswer(
                existing?.copy(mine = answer, mineAtMs = now) ?: AnswerEntity(day, question.id, mine = answer, mineAtMs = now),
            )
            enqueue(Payload.Answer(day, question.id, answer, now))
        }
    }

    override fun setMood(mood: String, note: String?) {
        val now = System.currentTimeMillis()
        val id = newId()
        val text = note?.trim()?.ifEmpty { null }
        launchSafely {
            togetherDao.addMood(MoodEntity(id, fromMe = true, mood = mood, note = text, atMs = now))
            enqueue(Payload.Mood(id, mood, text, now))
        }
    }

    override fun sendLetter(title: String, body: String, paper: String, openAt: Long?) {
        if (body.isBlank()) return
        val now = System.currentTimeMillis()
        queueMessage(
            MessageEntity(
                id = newId(), fromMe = true, kind = MessageKind.LETTER, body = body.trim(), sentAtMs = now, sortAtMs = now,
                state = MessageState.PENDING, title = title.trim().ifEmpty { null }, paper = paper,
                unlockAtMs = openAt?.takeIf { it > now },
            ),
        )
    }

    override fun openLetter(id: String) {
        launchSafely {
            val letter = dao.get(id)?.takeIf { !it.fromMe && it.kind == MessageKind.LETTER && it.openedAtMs == null } ?: return@launchSafely
            val now = System.currentTimeMillis()
            if ((letter.unlockAtMs ?: 0) > now) return@launchSafely
            dao.markLetterOpened(id, now)
            enqueue(Payload.Opened(id, now))
        }
    }

    override fun checkIn(kind: CheckInKind) {
        val now = System.currentTimeMillis()
        queueMessage(MessageEntity(id = newId(), fromMe = true, kind = MessageKind.CHECKIN, body = kind.name, sentAtMs = now, sortAtMs = now, state = MessageState.PENDING))
    }

    override fun shareLocation(durationMs: Long) {
        val now = System.currentTimeMillis()
        val until = now + durationMs
        val previous = settings.locationShareId
        val id = newId()
        settings.locationShareId = id
        settings.locationShareUntil = until
        launchSafely {
            if (previous != null) dao.endLiveLocation(previous, now)
            dao.insert(MessageEntity(id = id, fromMe = true, kind = MessageKind.LIVE_LOCATION, body = "", sentAtMs = now, sortAtMs = now, state = MessageState.PENDING, untilMs = until))
            flushOutbox()
        }
        LocationService.start(context)
        Alarms.at(context, until, Alarms.LOCATION_ENDS)
    }

    override suspend fun myLocation(): Pair<Double, Double>? =
        withContext(Dispatchers.IO) { LocationService.lastKnown(context)?.let { it.latitude to it.longitude } }

    override fun stopSharingLocation() {
        val id = settings.locationShareId
        settings.locationShareId = null
        settings.locationShareUntil = null
        lastLocationSent = null
        LocationService.stop(context)
        Alarms.cancel(context, Alarms.LOCATION_ENDS)
        if (id == null) return
        launchSafely {
            val now = System.currentTimeMillis()
            dao.endLiveLocation(id, now)
            enqueue(Payload.LiveLocationEnd(id, now))
        }
    }

    @Volatile
    private var lastLocationSent: android.location.Location? = null

    /** A new position from [LocationService]: send it, but not more than every few seconds unless it moved. */
    fun onMyLocation(location: android.location.Location) {
        val until = settings.locationShareUntil ?: return
        val now = System.currentTimeMillis()
        if (until <= now || (settings.sharingPausedUntil ?: 0) > now) {
            stopSharingLocation()
            return
        }
        val last = lastLocationSent
        if (last != null && location.time - last.time < LOCATION_MIN_GAP_MS && location.distanceTo(last) < LOCATION_MIN_MOVE_M) return
        lastLocationSent = location
        launchSafely {
            sendPayload(
                Payload.Location(
                    lat = location.latitude,
                    lng = location.longitude,
                    accuracyM = location.accuracy.takeIf { location.hasAccuracy() },
                    speedMps = location.speed.takeIf { location.hasSpeed() },
                    atMs = now,
                    untilMs = until,
                ),
                KIND_LOCATION,
                newId(),
                push = false,
            )
        }
    }

    override fun pauseSharing(until: Long?) {
        val now = System.currentTimeMillis()
        val pause = until?.takeIf { it > now }
        settings.sharingPausedUntil = pause
        if (pause != null) {
            if (settings.locationShareUntil != null) stopSharingLocation()
            if (!pausedIndefinitely(pause)) Alarms.at(context, pause, Alarms.PAUSE_ENDS) else Alarms.cancel(context, Alarms.PAUSE_ENDS)
        } else {
            Alarms.cancel(context, Alarms.PAUSE_ENDS)
        }
        launchSafely { publishStatus(force = true) }
    }

    override var autoDriving: Boolean
        get() = settings.autoDriving
        set(value) {
            settings.autoDriving = value
            if (value) {
                if (!Driving.enable(context)) settings.autoDriving = false
            } else {
                Driving.disable(context)
                settings.autoStatus = null
                launchSafely { publishStatus(force = true) }
            }
        }

    override var autoWakeUp: Boolean
        get() = settings.autoWakeUp
        set(value) {
            settings.autoWakeUp = value
            if (!value) settings.wokeAtMs = 0
            launchSafely { publishStatus(force = true) }
        }

    /** Activity recognition says the phone started or stopped travelling in a vehicle. */
    fun onDriving(driving: Boolean) {
        if (!settings.autoDriving) return
        val now = System.currentTimeMillis()
        settings.autoStatus = if (driving) app.mami.data.SavedQuickStatus("🚗", "Driving", now + DRIVING_EXPIRES_MS) else null
        launchSafely { publishStatus(force = true) }
    }

    fun onScreenOff() {
        settings.lastScreenOffAt = System.currentTimeMillis()
    }

    fun onUnlocked() {
        maybeWokeUp(settings.lastScreenOffAt)
    }

    /** Background fallback for waking up, when the app wasn't running at screen-off. */
    private fun checkIdle() {
        val power = context.getSystemService(android.os.PowerManager::class.java) ?: return
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val inUse = power.isInteractive && keyguard?.isKeyguardLocked != true
        val now = System.currentTimeMillis()
        if (!inUse) {
            if (settings.idleSince == 0L) settings.idleSince = now
        } else {
            val since = settings.idleSince
            settings.idleSince = 0
            if (since > 0) maybeWokeUp(maxOf(since, settings.lastScreenOffAt.takeIf { it > since } ?: since))
        }
    }

    /** First use of the phone in the morning after a long rest: "Woke up at 7:12". */
    private fun maybeWokeUp(idleSince: Long) {
        if (!settings.autoWakeUp || idleSince <= 0) return
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val local = java.time.Instant.ofEpochMilli(now).atZone(zone)
        if (local.hour !in WAKE_FROM_HOUR until WAKE_UNTIL_HOUR) return
        if (now - idleSince < WAKE_AFTER_IDLE_MS) return
        val last = settings.wokeAtMs
        if (last > 0 && java.time.Instant.ofEpochMilli(last).atZone(zone).toLocalDate() == local.toLocalDate()) return
        settings.wokeAtMs = now
        launchSafely { publishStatus(force = true) }
    }

    /**
     * An alarm (or the periodic check): show scheduled messages that are due,
     * end a pause or a live location share whose time is up.
     */
    suspend fun onAlarm() {
        val now = System.currentTimeMillis()
        val pause = settings.sharingPausedUntil
        if (pause != null && pause <= now) {
            settings.sharingPausedUntil = null
            quietly { publishStatus(force = true) }
        }
        val sharingUntil = settings.locationShareUntil
        if (sharingUntil != null && sharingUntil <= now) stopSharingLocation()
        val due = dao.unreadIncoming(8, now)
        if (due.any { it.scheduledAtMs != null && it.scheduledAtMs <= now } && !(chatVisible && foreground)) {
            notifications.showConversation(partnerName, due, settings.hideNotificationText)
        }
        scheduleNextDelivery()
    }

    private suspend fun scheduleNextDelivery() {
        val next = dao.nextScheduled(System.currentTimeMillis()) ?: return
        Alarms.at(context, next, Alarms.SCHEDULED)
    }

    private fun applyTogether(info: TogetherInfo, key: TogetherKey, value: String?, at: Long): TogetherInfo = when (key) {
        TogetherKey.SINCE -> if (at > info.sinceAt) info.copy(since = value, sinceAt = at) else info
        TogetherKey.NEXT_MEETING -> if (at > info.nextMeetingAt) info.copy(nextMeetingMs = value?.toLongOrNull(), nextMeetingAt = at) else info
        TogetherKey.NEXT_MEETING_LABEL -> if (at > info.nextMeetingLabelAt) info.copy(nextMeetingLabel = value, nextMeetingLabelAt = at) else info
        TogetherKey.UNKNOWN -> info
    }

    private fun queueMessage(message: MessageEntity) {
        launchSafely {
            dao.insert(message)
            flushOutbox()
        }
    }

    /** Queues a small payload that must reach the partner, even if the app closes first. */
    private suspend fun enqueue(payload: Payload) {
        outbox.add(OutboxEntity(newId(), payloadToJson(payload), System.currentTimeMillis()))
        flushOutbox()
    }

    override fun sendNudge(kind: NudgeKind) = queue(MessageKind.NUDGE, kind.name)

    override fun onComposerChanged(text: String) {
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
    override fun markChatRead() {
        launchSafely {
            notifications.clearConversation()
            if (dao.markIncomingRead(System.currentTimeMillis()) > 0) flushReadReceipts()
        }
    }

    /** Fetches the latest from the server now (pull to refresh, app start). */
    override suspend fun refresh() {
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
                // Attachments go out once their file is uploaded.
                if (message.isMedia && (message.transfer != Transfer.DONE || message.blobId == null)) continue
                val payload = payloadFor(message) ?: continue
                val accepted = try {
                    sendPayload(payload, KIND_MESSAGE, message.id, push = true)
                } catch (e: IOException) {
                    Log.d(TAG, "message ${message.id} waits: ${e.message}")
                    return
                }
                dao.markSent(message.id, accepted.atMs)
            }
            for (item in outbox.all()) {
                val payload = runCatching { payloadFromJson(item.payloadJson) }.getOrNull()
                if (payload == null) {
                    outbox.remove(item.id)
                    continue
                }
                try {
                    sendPayload(payload, KIND_MESSAGE, item.id, push = false)
                } catch (e: IOException) {
                    Log.d(TAG, "outbox waits: ${e.message}")
                    return
                }
                outbox.remove(item.id)
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
        MessageKind.TEXT -> Payload.Text(
            message.id,
            message.body,
            message.sentAtMs,
            message.replyTo,
            message.linkUrl?.let { LinkPreview(it, message.linkTitle, message.linkDescription, message.linkImage) },
            message.scheduledAtMs,
        )
        MessageKind.LETTER -> Payload.Letter(message.id, message.title.orEmpty(), message.body, message.paper ?: "cream", message.unlockAtMs, message.sentAtMs)
        MessageKind.CHECKIN -> Payload.CheckIn(message.id, CheckInKind.entries.firstOrNull { it.name == message.body } ?: CheckInKind.HOME_SAFE, message.sentAtMs)
        MessageKind.LIVE_LOCATION -> Payload.LiveLocation(message.id, message.untilMs ?: message.sentAtMs, message.sentAtMs)
        MessageKind.MEDIA -> Payload.Media(
            id = message.id,
            sentAtMs = message.sentAtMs,
            replyTo = message.replyTo,
            media = MediaInfo(
                kind = MediaKind.entries.firstOrNull { it.name == message.mediaKind } ?: MediaKind.FILE,
                blobId = message.blobId ?: return null,
                key = message.blobKey ?: return null,
                size = (message.mediaSize ?: 0L).toULong(),
                mime = message.mediaMime ?: "application/octet-stream",
                name = message.mediaName,
                width = message.mediaWidth,
                height = message.mediaHeight,
                durationMs = message.mediaDurationMs,
                thumbnail = message.mediaThumb,
                waveform = message.mediaWaveform?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(0),
            ),
            caption = message.body.ifBlank { null },
            viewOnce = message.viewOnce,
        )
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
        val status = collector.toShared(
            raw,
            settings.shares,
            settings.quickStatus,
            pausedUntil = settings.sharingPausedUntil,
            auto = settings.autoStatus.takeIf { settings.autoDriving },
            wokeAt = settings.wokeAtMs.takeIf { settings.autoWakeUp && it > 0 },
        )
        val fingerprint = listOf(
            status.pausedUntilMs,
            status.autoStatus,
            status.wokeAtMs,
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
                notifications.showConversation(partnerName, dao.unreadIncoming(8, System.currentTimeMillis()), settings.hideNotificationText)
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
            KIND_MESSAGE, KIND_STATUS, KIND_EPHEMERAL, KIND_CALL, KIND_LOCATION -> Unit
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
                val scheduled = payload.deliverAtMs?.takeIf { it > envelope.atMs }
                val handled = insertIncoming(
                    MessageEntity(
                        id = payload.id,
                        fromMe = false,
                        kind = MessageKind.TEXT,
                        body = payload.body,
                        sentAtMs = payload.sentAtMs,
                        sortAtMs = scheduled ?: envelope.atMs,
                        scheduledAtMs = scheduled,
                        state = MessageState.DELIVERED,
                        deliveredAtMs = now,
                        replyTo = payload.replyTo,
                        linkUrl = payload.link?.url,
                        linkTitle = payload.link?.title,
                        linkDescription = payload.link?.description,
                        linkImage = payload.link?.image,
                    ),
                )
                if (scheduled != null && scheduled > now) {
                    // It's a surprise until its time: no notification now, an alarm then.
                    scheduleNextDelivery()
                    return Handled.DONE
                }
                return handled
            }
            is Payload.Letter -> return insertIncoming(
                MessageEntity(
                    id = payload.id, fromMe = false, kind = MessageKind.LETTER, body = payload.body, sentAtMs = payload.sentAtMs,
                    sortAtMs = envelope.atMs, state = MessageState.DELIVERED, deliveredAtMs = now, title = payload.title.ifEmpty { null },
                    paper = payload.paper, unlockAtMs = payload.openAtMs,
                ),
            )
            is Payload.CheckIn -> return insertIncoming(
                MessageEntity(
                    id = payload.id, fromMe = false, kind = MessageKind.CHECKIN, body = payload.kind.name, sentAtMs = payload.sentAtMs,
                    sortAtMs = envelope.atMs, state = MessageState.DELIVERED, deliveredAtMs = now,
                ),
            )
            is Payload.LiveLocation -> return insertIncoming(
                MessageEntity(
                    id = payload.id, fromMe = false, kind = MessageKind.LIVE_LOCATION, body = "", sentAtMs = payload.sentAtMs,
                    sortAtMs = envelope.atMs, state = MessageState.DELIVERED, deliveredAtMs = now, untilMs = payload.untilMs,
                ),
            )
            is Payload.Location -> {
                val current = settings.partnerLocation
                if (current == null || payload.atMs >= current.atMs) {
                    settings.partnerLocation = SharedLocation(payload.lat, payload.lng, payload.accuracyM, payload.speedMps, payload.atMs, payload.untilMs)
                }
            }
            is Payload.LiveLocationEnd -> {
                dao.endLiveLocation(payload.id, payload.atMs)
                settings.partnerLocation = settings.partnerLocation?.copy(ended = true)
            }
            is Payload.Together -> settings.together = applyTogether(settings.together, payload.key, payload.value, payload.updatedAtMs)
            is Payload.Answer -> {
                val existing = togetherDao.answer(payload.day)
                togetherDao.putAnswer(
                    existing?.copy(theirs = payload.text, theirsAtMs = payload.answeredAtMs)
                        ?: AnswerEntity(payload.day, payload.questionId, theirs = payload.text, theirsAtMs = payload.answeredAtMs),
                )
                notifications.showTogether(
                    "💭 $partnerName answered today's question",
                    if (existing?.mine == null) "Answer it too to see what they said" else "See both answers",
                )
            }
            is Payload.Mood -> {
                togetherDao.addMood(MoodEntity(payload.id, fromMe = false, mood = payload.mood, note = payload.note, atMs = payload.atMs))
                notifications.showTogether(Moods.sentence(partnerName, payload.mood), payload.note ?: "Tap to say something sweet")
            }
            is Payload.Media -> {
                setTyping(false)
                val info = payload.media
                val size = info.size.toLong()
                val handled = insertIncoming(
                    MessageEntity(
                        id = payload.id,
                        fromMe = false,
                        kind = MessageKind.MEDIA,
                        body = payload.caption.orEmpty(),
                        sentAtMs = payload.sentAtMs,
                        sortAtMs = envelope.atMs,
                        state = MessageState.DELIVERED,
                        deliveredAtMs = now,
                        replyTo = payload.replyTo,
                        mediaKind = if (info.kind == MediaKind.UNKNOWN) MediaType.FILE else info.kind.name,
                        mediaMime = info.mime,
                        mediaName = info.name,
                        mediaSize = size,
                        mediaWidth = info.width,
                        mediaHeight = info.height,
                        mediaDurationMs = info.durationMs,
                        mediaThumb = info.thumbnail,
                        mediaWaveform = info.waveform.takeIf { it.isNotEmpty() }?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                        blobId = info.blobId,
                        blobKey = info.key,
                        transfer = if (autoDownload(info.kind, size)) Transfer.DOWNLOAD else Transfer.ASK,
                        viewOnce = payload.viewOnce,
                    ),
                )
                if (handled == Handled.NEW_MESSAGE) kickTransfers()
                return handled
            }
            is Payload.Reaction -> dao.setTheirReaction(payload.targetId, payload.emoji)
            is Payload.Edit -> dao.edit(payload.targetId, fromMe = false, body = payload.body, atMs = payload.editedAtMs)
            is Payload.Unsend -> {
                val target = dao.get(payload.targetId)?.takeIf { !it.fromMe }
                target?.let { media.delete(it.mediaFile) }
                // A scheduled message that was never shown just disappears.
                if (target?.hiddenUntil(now) == true) dao.delete(payload.targetId) else dao.unsend(payload.targetId, fromMe = false)
            }
            is Payload.Pin -> dao.setPinned(payload.targetId, if (payload.pinned) payload.atMs else null)
            is Payload.Opened -> dao.markOpenedByPartner(payload.targetId, payload.atMs)
            is Payload.CallOffer, is Payload.CallRinging, is Payload.CallAnswer, is Payload.CallCandidates,
            is Payload.CallMedia, is Payload.CallEnd -> onCallPayload(payload)
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

    /** Calls are wired up by [app.mami.AppGraph]; until then call signalling is ignored. */
    var callHandler: (suspend (Payload) -> Unit)? = null

    // ---- calls (CallSignals) ------------------------------------------------------------

    override val canCall: Boolean get() = _partner.value?.identity != null && _secure.value

    override suspend fun sendCall(payload: Payload, push: Boolean) {
        val partnerCurve = _partner.value?.identity?.curve25519 ?: throw NoSessionException()
        val ciphertext = crypto.encrypt(partnerCurve, payload) ?: throw NoSessionException()
        val request = SendRequestDto(newId(), KIND_CALL, ciphertext.messageType, ciphertext.body, push)
        // The live connection is quickest; the first ring goes over HTTP so the server can push it.
        if (!push && realtime.sendEphemeral(request)) return
        api.send(request)
    }

    override fun stayConnected(active: Boolean) {
        inCall = active
        if (active) realtime.start() else if (!foreground) realtime.stop()
    }

    override suspend fun logCall(id: String, outgoing: Boolean, video: Boolean, outcome: String, startedAtMs: Long, durationMs: Long?) {
        val now = System.currentTimeMillis()
        val missed = !outgoing && outcome != CallOutcome.ANSWERED && outcome != CallOutcome.DECLINED
        dao.insert(
            MessageEntity(
                id = id,
                fromMe = outgoing,
                kind = MessageKind.CALL,
                body = "",
                sentAtMs = startedAtMs,
                sortAtMs = startedAtMs,
                state = if (outgoing) MessageState.READ else MessageState.DELIVERED,
                deliveredAtMs = startedAtMs,
                readAtMs = if (missed && !(chatVisible && foreground)) null else now,
                // There's nothing to acknowledge: both phones write their own call entry.
                readReceiptSent = true,
                mediaKind = if (video) MediaType.VIDEO else MediaType.VOICE,
                mediaDurationMs = durationMs,
                callOutcome = outcome,
            ),
        )
    }

    private suspend fun onCallPayload(payload: Payload) {
        callHandler?.invoke(payload)
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
        VoicePlayer.stop()
        if (settings.locationShareUntil != null) {
            LocationService.stop(context)
            Alarms.cancel(context, Alarms.LOCATION_ENDS)
        }
        dao.deleteAll()
        outbox.clear()
        togetherDao.clearAnswers()
        togetherDao.clearMoods()
        media.clear()
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
        const val KIND_CALL = "call"
        const val KIND_LOCATION = "location"
        const val LOCATION_MIN_GAP_MS = 8_000L
        const val LOCATION_MIN_MOVE_M = 25f
        const val DRIVING_EXPIRES_MS = 3 * 60 * 60_000L
        const val WAKE_FROM_HOUR = 4
        const val WAKE_UNTIL_HOUR = 12
        const val WAKE_AFTER_IDLE_MS = 3 * 60 * 60_000L
        const val MIN_ONE_TIME_KEYS = 5
        const val ONE_TIME_KEY_BATCH = 20
        const val SYNC_PAGE_SIZE = 500
        const val MAX_SYNC_PAGES = 20
        const val TYPING_INTERVAL_MS = 4_000L
        const val TYPING_TIMEOUT_MS = 8_000L
        const val STATUS_MIN_GAP_MS = 60_000L
        const val STATUS_REFRESH_MS = 10 * 60_000L
        const val CRITICAL_BATTERY = 5
        const val AUTO_DOWNLOAD_BYTES = 16L * 1024 * 1024
        const val LOW_BATTERY_RESET = 15
    }
}
