package app.mami.calls

import android.content.Context
import android.util.Log
import android.view.View
import app.mami.core.CallEndReason
import app.mami.core.Payload
import app.mami.data.Api
import app.mami.sync.Notifications
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Real calls over WebRTC. Audio and video go straight between the two phones
 * (or through a TURN relay that can't decrypt them), encrypted with DTLS-SRTP.
 * The offer and answer, which carry the DTLS fingerprints, travel inside the
 * end-to-end encrypted chat, so nobody — not even MaMi's server — can sit in
 * the middle of a call.
 *
 * All call logic runs on one thread, in order.
 */
class CallManager(
    private val context: Context,
    private val api: Api,
    private val signals: CallSignals,
    private val notifications: Notifications,
) : Calls {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "MaMi.Call") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)
    private val audio = CallAudio(context)

    private val _call = MutableStateFlow<CallState?>(null)
    override val call: StateFlow<CallState?> = _call.asStateFlow()
    override val hasVideo: Boolean = true

    private var session: Session? = null

    /** Network paths that arrived before the offer they belong to. */
    private val early = HashMap<String, MutableList<app.mami.core.IceCandidate>>()

    private val egl: EglBase by lazy { EglBase.create() }
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        val audioModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private class Session(val id: String, val video: Boolean, val outgoing: Boolean, val startedAt: Long) {
        var peer: PeerConnection? = null
        var remoteOffer: String? = null
        var remoteSet = false
        val pendingRemote = ArrayList<IceCandidate>()
        var canSignal = false
        val pendingLocal = ArrayList<app.mami.core.IceCandidate>()
        var flush: Job? = null
        var timeout: Job? = null
        var reconnect: Job? = null
        var audioSource: AudioSource? = null
        var audioTrack: AudioTrack? = null
        var videoSource: VideoSource? = null
        var videoTrack: VideoTrack? = null
        var capturer: CameraVideoCapturer? = null
        var textures: SurfaceTextureHelper? = null
        var remoteVideo: VideoTrack? = null
        val localViews = HashSet<SurfaceViewRenderer>()
        val remoteViews = HashSet<SurfaceViewRenderer>()
        var ended = false
    }

    // ---- what the screens call ------------------------------------------------------

    override fun start(video: Boolean) {
        scope.launch { startCall(video) }
    }

    override fun accept() {
        scope.launch { acceptCall() }
    }

    override fun decline() {
        scope.launch {
            val s = session ?: return@launch
            if (_call.value?.phase == CallPhase.Incoming) end(s, CallEndReason.DECLINED, tell = true, mine = true)
        }
    }

    override fun hangUp() {
        scope.launch {
            val s = session ?: return@launch
            val phase = _call.value?.phase
            val reason = if (phase == CallPhase.Incoming) CallEndReason.DECLINED else CallEndReason.HANGUP
            end(s, reason, tell = true, mine = true)
        }
    }

    override fun setMuted(muted: Boolean) {
        scope.launch {
            val s = session ?: return@launch
            s.audioTrack?.setEnabled(!muted)
            _call.update { it?.copy(muted = muted) }
            sendMediaState(s)
        }
    }

    override fun setCameraOn(on: Boolean) {
        scope.launch {
            val s = session ?: return@launch
            if (!s.video) return@launch
            val capturer = s.capturer
            if (on) runCatching { capturer?.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS) } else runCatching { capturer?.stopCapture() }
            s.videoTrack?.setEnabled(on)
            _call.update { it?.copy(cameraOn = on) }
            sendMediaState(s)
        }
    }

    override fun switchCamera() {
        scope.launch {
            val s = session ?: return@launch
            s.capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(front: Boolean) {
                    _call.update { it?.copy(frontCamera = front) }
                    s.localViews.forEach { view -> view.post { view.setMirror(front) } }
                }

                override fun onCameraSwitchError(error: String?) {
                    Log.w(TAG, "camera switch failed: $error")
                }
            })
        }
    }

    override fun setRoute(route: AudioRoute) {
        scope.launch {
            audio.setRoute(route)
            val state = _call.value ?: return@launch
            _call.value = state.copy(route = route, routes = audio.routes())
            audio.proximity(!state.video && route == AudioRoute.Earpiece && state.phase != CallPhase.Incoming)
        }
    }

    override fun createVideoView(context: Context, remote: Boolean): View {
        val view = SurfaceViewRenderer(context)
        view.init(egl.eglBaseContext, null)
        view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        view.setEnableHardwareScaler(true)
        if (!remote) {
            view.setMirror(_call.value?.frontCamera != false)
            view.setZOrderMediaOverlay(true)
        }
        scope.launch {
            val s = session ?: return@launch
            if (remote) {
                s.remoteViews += view
                s.remoteVideo?.addSink(view)
            } else {
                s.localViews += view
                s.videoTrack?.addSink(view)
            }
        }
        return view
    }

    override fun releaseVideoView(view: View) {
        val renderer = view as? SurfaceViewRenderer ?: return
        scope.launch {
            session?.let { s ->
                s.remoteVideo?.removeSink(renderer)
                s.videoTrack?.removeSink(renderer)
                s.remoteViews -= renderer
                s.localViews -= renderer
            }
            renderer.post { renderer.release() }
        }
    }

    // ---- signalling from the partner --------------------------------------------------

    /** Called by the messenger with every decrypted call payload. */
    suspend fun onSignal(payload: Payload) {
        withContext(thread) { handle(payload) }
    }

    private suspend fun handle(payload: Payload) {
        when (payload) {
            is Payload.CallOffer -> onOffer(payload)
            is Payload.CallRinging -> session?.takeIf { it.id == payload.callId && it.outgoing }?.let {
                _call.update { state -> if (state?.phase == CallPhase.Calling) state.copy(phase = CallPhase.Ringing) else state }
            }
            is Payload.CallAnswer -> session?.takeIf { it.id == payload.callId && it.outgoing }?.let { onAnswer(it, payload.sdp) }
            is Payload.CallCandidates -> {
                val s = session
                if (s != null && s.id == payload.callId) {
                    addRemote(s, payload.candidates)
                } else {
                    early.getOrPut(payload.callId) { ArrayList() } += payload.candidates
                    if (early.size > 4) early.remove(early.keys.first())
                }
            }
            is Payload.CallMedia -> if (session?.id == payload.callId) {
                _call.update { it?.copy(partnerMuted = payload.muted, partnerCameraOn = payload.cameraOn) }
            }
            is Payload.CallEnd -> session?.takeIf { it.id == payload.callId }?.let { end(it, payload.reason, tell = false, mine = false) }
            else -> Unit
        }
    }

    private suspend fun onOffer(offer: Payload.CallOffer) {
        val current = session
        if (current != null) {
            if (current.id == offer.callId) return
            val calling = current.outgoing && _call.value?.phase.let { it == CallPhase.Calling || it == CallPhase.Ringing }
            if (calling && offer.callId < current.id) {
                // We called each other at the same moment: the smaller id wins, so both phones pick the same call.
                current.ended = true
                cleanUp(current)
                session = null
            } else {
                runCatching { signals.sendCall(Payload.CallEnd(offer.callId, CallEndReason.BUSY), push = false) }
                return
            }
            startIncoming(offer, autoAccept = true)
            return
        }
        startIncoming(offer, autoAccept = false)
    }

    private suspend fun startIncoming(offer: Payload.CallOffer, autoAccept: Boolean) {
        val now = System.currentTimeMillis()
        val s = Session(offer.callId, offer.video, outgoing = false, startedAt = now).apply { remoteOffer = offer.sdp }
        early.remove(offer.callId)?.let { candidates -> candidates.forEach { s.pendingRemote += it.toWebRtc() } }
        session = s
        _call.value = CallState(s.id, s.video, outgoing = false, phase = CallPhase.Incoming, startedAtMs = now, routes = audio.routes())
        signals.stayConnected(true)
        runCatching { signals.sendCall(Payload.CallRinging(s.id), push = false) }
        if (autoAccept) {
            acceptCall()
            return
        }
        audio.ring()
        notifications.showIncomingCall(signals.partnerName, s.video)
        s.timeout = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (_call.value?.phase == CallPhase.Incoming) end(s, CallEndReason.NO_ANSWER, tell = true, mine = false)
        }
    }

    private suspend fun onAnswer(s: Session, sdp: String) {
        val peer = s.peer ?: return
        try {
            peer.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
            s.remoteSet = true
            s.pendingRemote.forEach { peer.addIceCandidate(it) }
            s.pendingRemote.clear()
            s.timeout?.cancel()
            _call.update { it?.copy(phase = CallPhase.Connecting) }
        } catch (e: IOException) {
            Log.w(TAG, "bad answer", e)
            end(s, CallEndReason.FAILED, tell = true, mine = true)
        }
    }

    private fun addRemote(s: Session, candidates: List<app.mami.core.IceCandidate>) {
        val peer = s.peer
        candidates.map { it.toWebRtc() }.forEach { candidate ->
            if (peer != null && s.remoteSet) peer.addIceCandidate(candidate) else s.pendingRemote += candidate
        }
    }

    // ---- starting and answering --------------------------------------------------------

    private suspend fun startCall(video: Boolean) {
        if (session != null) return
        val now = System.currentTimeMillis()
        val s = Session(UUID.randomUUID().toString(), video, outgoing = true, startedAt = now)
        session = s
        if (!signals.canCall) {
            _call.value = CallState(s.id, video, outgoing = true, phase = CallPhase.Ended, startedAtMs = now, endReason = "The secure connection isn't ready yet")
            session = null
            dismissLater(s.id)
            return
        }
        val route = audio.start(video)
        _call.value = CallState(s.id, video, outgoing = true, phase = CallPhase.Calling, startedAtMs = now, route = route, routes = audio.routes())
        audio.proximity(!video && route == AudioRoute.Earpiece)
        CallService.start(context, video)
        signals.stayConnected(true)
        try {
            val peer = createPeer(s)
            addMedia(s, peer)
            val offer = peer.awaitCreate(offer = true, video = video)
            peer.awaitSetLocal(offer)
            signals.sendCall(Payload.CallOffer(s.id, video, offer.description, now), push = true)
            s.canSignal = true
            flushLocal(s)
            s.timeout = scope.launch {
                delay(RING_TIMEOUT_MS)
                val phase = _call.value?.phase
                if (phase == CallPhase.Calling || phase == CallPhase.Ringing) end(s, CallEndReason.NO_ANSWER, tell = true, mine = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not start the call", e)
            end(s, CallEndReason.FAILED, tell = true, mine = true)
        }
    }

    private suspend fun acceptCall() {
        val s = session ?: return
        if (_call.value?.phase != CallPhase.Incoming) return
        s.timeout?.cancel()
        notifications.cancelIncomingCall()
        val route = audio.start(s.video)
        _call.update { it?.copy(phase = CallPhase.Connecting, route = route, routes = audio.routes()) }
        audio.proximity(!s.video && route == AudioRoute.Earpiece)
        CallService.start(context, s.video)
        try {
            val peer = createPeer(s)
            addMedia(s, peer)
            peer.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, s.remoteOffer ?: throw IOException("no offer")))
            s.remoteSet = true
            s.pendingRemote.forEach { peer.addIceCandidate(it) }
            s.pendingRemote.clear()
            val answer = peer.awaitCreate(offer = false, video = s.video)
            peer.awaitSetLocal(answer)
            signals.sendCall(Payload.CallAnswer(s.id, answer.description), push = false)
            s.canSignal = true
            flushLocal(s)
        } catch (e: Exception) {
            Log.w(TAG, "could not answer", e)
            end(s, CallEndReason.FAILED, tell = true, mine = true)
        }
    }

    private suspend fun createPeer(s: Session): PeerConnection {
        val servers = try {
            api.iceServers().iceServers.map { server ->
                PeerConnection.IceServer.builder(server.urls)
                    .setUsername(server.username.orEmpty())
                    .setPassword(server.credential.orEmpty())
                    .createIceServer()
            }
        } catch (e: IOException) {
            Log.w(TAG, "no ICE servers from the server, using public STUN: ${e.message}")
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val peer = factory.createPeerConnection(config, Observer(s)) ?: throw IOException("could not create the connection")
        s.peer = peer
        return peer
    }

    private fun addMedia(s: Session, peer: PeerConnection) {
        val audioSource = factory.createAudioSource(MediaConstraints())
        val audioTrack = factory.createAudioTrack("mami-audio", audioSource)
        s.audioSource = audioSource
        s.audioTrack = audioTrack
        peer.addTrack(audioTrack, listOf(STREAM))
        if (!s.video) return
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull() ?: return
        val capturer = enumerator.createCapturer(name, null) ?: return
        val textures = SurfaceTextureHelper.create("MaMi.Camera", egl.eglBaseContext)
        val source = factory.createVideoSource(capturer.isScreencast)
        capturer.initialize(textures, context, source.capturerObserver)
        capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
        val track = factory.createVideoTrack("mami-video", source)
        s.capturer = capturer
        s.textures = textures
        s.videoSource = source
        s.videoTrack = track
        s.localViews.forEach { track.addSink(it) }
        peer.addTrack(track, listOf(STREAM))
    }

    private fun sendMediaState(s: Session) {
        val state = _call.value ?: return
        scope.launch { runCatching { signals.sendCall(Payload.CallMedia(s.id, state.cameraOn, state.muted), push = false) } }
    }

    /** Sends gathered network paths in small batches, once the offer or answer is out. */
    private fun flushLocal(s: Session) {
        if (!s.canSignal || s.pendingLocal.isEmpty() || s.flush != null) return
        s.flush = scope.launch {
            delay(CANDIDATE_BATCH_MS)
            val batch = s.pendingLocal.toList()
            s.pendingLocal.clear()
            s.flush = null
            if (batch.isNotEmpty() && !s.ended) runCatching { signals.sendCall(Payload.CallCandidates(s.id, batch), push = false) }
            flushLocal(s)
        }
    }

    // ---- connection state --------------------------------------------------------------

    private inner class Observer(private val s: Session) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            scope.launch {
                s.pendingLocal += app.mami.core.IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                flushLocal(s)
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            scope.launch { onConnection(s, state) }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track() as? VideoTrack ?: return
            scope.launch {
                s.remoteVideo = track
                s.remoteViews.forEach { track.addSink(it) }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun onConnection(s: Session, state: PeerConnection.IceConnectionState) {
        if (session !== s || s.ended) return
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                s.reconnect?.cancel()
                s.reconnect = null
                s.timeout?.cancel()
                _call.update { it?.copy(phase = CallPhase.Connected, connectedAtMs = it.connectedAtMs ?: System.currentTimeMillis()) }
                notifications.updateOngoingCall(context, signals.partnerName, s.video, _call.value?.connectedAtMs)
            }
            PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.FAILED -> {
                if (_call.value?.connectedAtMs != null) _call.update { it?.copy(phase = CallPhase.Reconnecting) }
                if (s.reconnect == null) {
                    s.reconnect = scope.launch {
                        delay(RECONNECT_TIMEOUT_MS)
                        end(s, CallEndReason.FAILED, tell = true, mine = true)
                    }
                }
            }
            else -> Unit
        }
    }

    // ---- ending --------------------------------------------------------------------------

    private suspend fun end(s: Session, reason: CallEndReason, tell: Boolean, mine: Boolean) {
        if (s.ended) return
        s.ended = true
        if (tell) runCatching { signals.sendCall(Payload.CallEnd(s.id, reason), push = false) }
        val state = _call.value
        cleanUp(s)
        if (session === s) session = null
        val now = System.currentTimeMillis()
        val connectedAt = state?.connectedAtMs
        val outcome = when {
            connectedAt != null -> CallOutcome.ANSWERED
            reason == CallEndReason.DECLINED -> CallOutcome.DECLINED
            reason == CallEndReason.BUSY -> CallOutcome.BUSY
            reason == CallEndReason.FAILED -> CallOutcome.FAILED
            else -> CallOutcome.MISSED
        }
        runCatching { signals.logCall(s.id, s.outgoing, s.video, outcome, s.startedAt, connectedAt?.let { now - it }) }
        if (!s.outgoing && connectedAt == null && !(mine && reason == CallEndReason.DECLINED)) {
            notifications.showMissedCall(signals.partnerName, s.video)
        }
        val words = when {
            connectedAt != null -> "Call ended"
            reason == CallEndReason.DECLINED -> if (mine) "Declined" else "${signals.partnerName} can't talk right now"
            reason == CallEndReason.BUSY -> "${signals.partnerName} is on another call"
            reason == CallEndReason.NO_ANSWER -> if (s.outgoing) "No answer" else "Missed call"
            reason == CallEndReason.FAILED -> "Couldn't connect"
            else -> "Call ended"
        }
        _call.value = (state ?: CallState(s.id, s.video, s.outgoing, CallPhase.Ended, s.startedAt)).copy(phase = CallPhase.Ended, endReason = words)
        dismissLater(s.id)
    }

    private fun dismissLater(id: String) {
        scope.launch {
            delay(ENDED_SHOWN_MS)
            if (_call.value?.id == id && _call.value?.phase == CallPhase.Ended) _call.value = null
        }
    }

    private fun cleanUp(s: Session) {
        s.timeout?.cancel()
        s.reconnect?.cancel()
        s.flush?.cancel()
        audio.stop()
        notifications.cancelIncomingCall()
        CallService.stop(context)
        signals.stayConnected(false)
        runCatching { s.capturer?.stopCapture() }
        s.capturer?.dispose()
        s.textures?.dispose()
        s.remoteVideo?.let { track -> s.remoteViews.forEach { track.removeSink(it) } }
        s.videoTrack?.let { track -> s.localViews.forEach { track.removeSink(it) } }
        s.peer?.dispose()
        s.videoSource?.dispose()
        s.audioSource?.dispose()
        s.peer = null
    }

    // ---- helpers ------------------------------------------------------------------------

    private suspend fun PeerConnection.awaitCreate(offer: Boolean, video: Boolean): SessionDescription =
        suspendCancellableCoroutine { cont ->
            val constraints = MediaConstraints().apply {
                mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
                mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", video.toString())
            }
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = cont.resume(description)
                override fun onCreateFailure(error: String?) = cont.resumeWithException(IOException(error ?: "create failed"))
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            }
            if (offer) createOffer(observer, constraints) else createAnswer(observer, constraints)
        }

    private suspend fun PeerConnection.awaitSetLocal(description: SessionDescription) = awaitSet { setLocalDescription(it, description) }

    private suspend fun PeerConnection.awaitSetRemote(description: SessionDescription) = awaitSet { setRemoteDescription(it, description) }

    private suspend fun awaitSet(set: (SdpObserver) -> Unit) = suspendCancellableCoroutine { cont ->
        set(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) = Unit
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) = cont.resumeWithException(IOException(error ?: "set failed"))
        })
    }

    private fun app.mami.core.IceCandidate.toWebRtc() = IceCandidate(sdpMid, sdpMLineIndex, candidate)

    private companion object {
        const val TAG = "MaMi.Call"
        const val STREAM = "mami"
        const val RING_TIMEOUT_MS = 45_000L
        const val RECONNECT_TIMEOUT_MS = 20_000L
        const val ENDED_SHOWN_MS = 2_200L
        const val CANDIDATE_BATCH_MS = 150L
        const val CAPTURE_WIDTH = 1280
        const val CAPTURE_HEIGHT = 720
        const val CAPTURE_FPS = 30
    }
}
