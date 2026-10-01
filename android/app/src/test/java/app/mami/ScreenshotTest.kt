package app.mami

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mami.calls.CallPhase
import app.mami.data.db.MediaType
import app.mami.data.db.MessageEntity
import app.mami.data.db.MessageKind
import app.mami.data.db.MessageState
import app.mami.demo.DemoBackend
import app.mami.media.MediaLibrary
import app.mami.ui.AppController
import app.mami.ui.Destination
import app.mami.ui.MamiScreens
import app.mami.ui.Overlay
import app.mami.ui.PreviewController
import app.mami.ui.SignInStep
import app.mami.ui.call.CallOverlay
import app.mami.ui.chat.MediaViewer
import app.mami.ui.chat.MessageActionsSheet
import app.mami.ui.chat.MessageDetailsSheet
import app.mami.ui.chat.MessageMenu
import app.mami.ui.chat.SearchResults
import app.mami.ui.chat.SearchTopBar
import app.mami.ui.chat.viewable
import app.mami.ui.debug.Playground
import app.mami.ui.pairing.PairScreen
import app.mami.ui.theme.Appearance
import app.mami.ui.theme.DarkMode
import app.mami.ui.theme.MamiTheme
import app.mami.ui.theme.Palette
import app.mami.ui.together.LetterComposer
import app.mami.ui.together.LetterReader
import app.mami.ui.together.LettersCard
import app.mami.ui.together.LocationSheet
import app.mami.ui.together.MoodCard
import app.mami.ui.together.PauseCard
import app.mami.ui.together.SafeCard
import app.mami.ui.together.ScheduleSheet
import app.mami.ui.together.ScheduledCard
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.runBlocking

/**
 * Renders every screen with the simulated partner and saves PNGs to
 * docs/screenshots. Run with `./gradlew recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir = System.getProperty("mami.screenshots") ?: "build/screenshots"

    private fun shot(
        name: String,
        appearance: Appearance = Appearance(darkMode = DarkMode.LIGHT),
        setup: DemoBackend.(PreviewController) -> Unit = {},
        content: @Composable (PreviewController) -> Unit = { MamiScreens(it) },
    ) {
        val scope = MainScope()
        val demo = DemoBackend(scope, MediaLibrary(ApplicationProvider.getApplicationContext()))
        demo.autoReply = false
        val ui = PreviewController(demo, appearance, scope)
        demo.setup(ui)
        compose.mainClock.autoAdvance = false
        compose.setContent { MamiTheme(appearance) { content(ui) } }
        compose.mainClock.advanceTimeBy(2_500)
        compose.waitForIdle()
        captureScreenRoboImage("$dir/$name.png")
    }

    @Test fun welcome() = shot("01-welcome")

    @Test fun email() = shot("02-email", setup = { ui ->
        ui.signInStep = SignInStep.Email
        ui.signInEmail = "sam@example.com"
    })

    @Test fun code() = shot("03-code", setup = { ui ->
        ui.signInStep = SignInStep.Code
        ui.signInEmail = "sam@example.com"
    })

    @Test fun name() = shot("04-name", setup = { skipSignIn() })

    @Test fun sharing() = shot("05-sharing", setup = { skipProfile() })

    @Test fun pair() = shot("06-pair", setup = { skipSharing() })

    @Test fun pairCode() = shot("06-pair-code", setup = { skipSharing() }) { ui -> PairScreen(ui, startWithCode = true) }

    @Test fun invite() = shot("07-invite", setup = {
        skipSharing()
        runBlocking { createInvite("maya@example.com") }
    })

    @Test fun chat() = shot("08-chat", setup = {
        skipPairing()
        partnerTypes(600_000)
    })

    @Test fun chatDark() = shot("09-chat-dark", appearance = Appearance(darkMode = DarkMode.DARK), setup = { skipPairing() })

    @Test fun chatPhoneDied() = shot("10-chat-phone-died", setup = {
        skipPairing()
        simulatePhoneDied()
    })

    @Test fun partnerSheet() = shot("11-partner", setup = { ui ->
        skipPairing()
        setPartnerQuickStatus("🚗", "Driving")
        ui.partnerSheetOpen = true
    })

    @Test fun messageDetails() = shot("12-message-details", setup = { skipPairing() }) {
        val now = System.currentTimeMillis()
        MessageDetailsSheet(
            MessageEntity(
                id = "m", fromMe = true, kind = MessageKind.TEXT, body = "Almost home 🏡", sentAtMs = now - 90_000,
                sortAtMs = now - 89_000, state = MessageState.READ, serverAtMs = now - 89_600, deliveredAtMs = now - 88_400,
                readAtMs = now - 20_000,
            ),
            "Maya",
        ) {}
    }

    @Test fun settings() = shot("13-settings", setup = { ui ->
        skipPairing()
        ui.overlay = Overlay.Settings
    })

    @Test fun safety() = shot("14-safety", setup = { ui ->
        skipPairing()
        ui.overlay = Overlay.Safety
    })

    @Test fun chatOcean() = shot("15-chat-ocean", appearance = Appearance(Palette.OCEAN, DarkMode.LIGHT), setup = { skipPairing() })

    @Test fun chatSunsetDark() = shot("16-chat-sunset-dark", appearance = Appearance(Palette.SUNSET, DarkMode.DARK), setup = { skipPairing() })

    @Test fun chatMedia() = shot("18-chat-media", setup = {
        skipPairing()
        showcaseMedia()
    })

    @Test fun messageActions() = shot("19-message-actions", setup = { skipPairing() }) {
        val now = System.currentTimeMillis()
        MessageActionsSheet(
            MessageEntity(
                id = "m", fromMe = true, kind = MessageKind.TEXT, body = "Dinner at the momo place tonight? 🥟", sentAtMs = now - 60_000,
                sortAtMs = now - 60_000, state = MessageState.READ, theirReaction = "😍", myReaction = "❤️",
            ),
            "Maya",
            MessageMenu(
                onReact = {}, onReply = {}, onCopy = {}, onEdit = {}, onPin = {}, onStar = {}, onInfo = {},
                onSave = null, onShare = null, onUnsend = {}, onDelete = {},
            ),
        ) {}
    }

    @Test fun sharedMedia() = shot("20-shared-media", setup = { ui ->
        skipPairing()
        showcaseMedia()
        ui.overlay = Overlay.Media
    })

    @Test fun search() = shot("21-search", setup = { skipPairing() }) { ui ->
        Column {
            SearchTopBar("momo", onQuery = {}, onClose = {})
            SearchResults(ui.backend, "momo", "Maya", System.currentTimeMillis(), onOpen = {})
        }
    }

    @Test fun starred() = shot("22-starred", setup = { ui ->
        skipPairing()
        showcaseMedia()
        ui.overlay = Overlay.Starred
    })

    @Test fun viewer() = shot("23-viewer", setup = {
        skipPairing()
        showcaseMedia()
    }) { ui ->
        val messages by ui.backend.messages.collectAsState(initial = emptyList())
        val photo = messages.lastOrNull { it.fromMe && it.mediaKind == MediaType.PHOTO }
        if (photo != null) {
            MediaViewer(ui.backend, viewable(messages, ui.backend, photo.id), photo.id, "Maya", onShowInChat = {}, onDismiss = {})
        }
    }

    @Test fun callIncoming() = shot("24-call-incoming", setup = {
        skipPairing()
        calls.preview(CallPhase.Incoming, video = true, outgoing = false)
    }) { CallOverlay(it) }

    @Test fun callRinging() = shot("25-call-ringing", setup = {
        skipPairing()
        updatePartnerStatus { it.copy(batteryPercent = 23, charging = false) }
        calls.preview(CallPhase.Ringing, video = false)
    }) { CallOverlay(it) }

    @Test fun callConnected() = shot("26-call-voice", setup = {
        skipPairing()
        calls.preview(CallPhase.Connected, video = false)
    }) { CallOverlay(it) }

    @Test fun callVideo() = shot("27-call-video", setup = {
        skipPairing()
        calls.preview(CallPhase.Connected, video = true)
        calls.partnerTogglesCamera()
    }) { CallOverlay(it) }

    @Test fun chatCalls() = shot("28-chat-calls", setup = {
        skipPairing()
        showcaseCalls()
    })

    @Test fun together() = shot("29-together", setup = { ui ->
        skipPairing()
        showcaseTogether()
        ui.overlay = Overlay.Together
    })

    @Test fun togetherMoods() = shot("30-together-mood", setup = {
        skipPairing()
        showcaseTogether()
    }) { ui ->
        val now = System.currentTimeMillis()
        val moods by ui.backend.moods.collectAsState(initial = emptyList())
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MoodCard(moods, "Maya", now, onMood = { _, _ -> })
            }
        }
    }

    @Test fun togetherMore() = shot("31-together-more", setup = {
        skipPairing()
        showcaseTogether()
        pauseSharing(System.currentTimeMillis() + 2 * 60 * 60_000L)
    }) { ui ->
        val now = System.currentTimeMillis()
        val messages by ui.backend.messages.collectAsState(initial = emptyList())
        val location by ui.backend.partnerLocation.collectAsState()
        val paused by ui.backend.sharingPausedUntil.collectAsState()
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LettersCard(messages.filter { it.kind == MessageKind.LETTER }.asReversed(), "Maya", now, onWrite = {}, onOpen = {})
                SafeCard("Maya", location, null, now, onCheckIn = {}, onLocation = {})
                ScheduledCard(messages.filter { it.fromMe && it.hiddenUntil(now) }, "Maya", ZoneId.of("Asia/Kathmandu"), now, onCancel = {})
                PauseCard(paused, "Maya", now, onPause = {})
            }
        }
    }

    @Test fun chatTogether() = shot("32-chat-together", setup = {
        skipPairing()
        clearChat()
        showcaseTogether()
        partnerMood()
        partnerDriving(true)
    })

    @Test fun letter() = shot("33-letter", setup = { skipPairing() }) {
        val now = System.currentTimeMillis()
        LetterReader(
            MessageEntity(
                id = "l", fromMe = false, kind = MessageKind.LETTER, sentAtMs = now - 3_600_000, sortAtMs = now - 3_600_000,
                state = MessageState.DELIVERED, title = "For my favourite person", paper = "cream",
                body = "My love,\n\nI know the distance is hard some days. But every morning I wake up and the first thing I think is " +
                    "that you exist, somewhere, thinking of me too.\n\nCount down the days with me.\n\nForever yours,\nMaya",
            ),
            "Maya",
            now,
            onOpened = {},
            onDismiss = {},
        )
    }

    @Test fun letterSealed() = shot("34-letter-sealed", setup = { skipPairing() }) {
        val now = System.currentTimeMillis()
        LetterReader(
            MessageEntity(
                id = "l", fromMe = false, kind = MessageKind.LETTER, body = "…", sentAtMs = now, sortAtMs = now,
                state = MessageState.DELIVERED, title = "Open on our anniversary", paper = "rose", unlockAtMs = now + 2 * 24 * 3_600_000L + 3 * 3_600_000L,
            ),
            "Maya",
            now,
            onOpened = {},
            onDismiss = {},
        )
    }

    @Test fun letterComposer() = shot("35-letter-write", setup = { skipPairing() }) {
        LetterComposer("Maya", System.currentTimeMillis(), onDismiss = {}) { _, _, _, _ -> }
    }

    @Test fun schedule() = shot("36-schedule", setup = { skipPairing() }) {
        ScheduleSheet(
            text = "Good morning, beautiful ☀️ Have the best first day at the new job!",
            partnerName = "Maya",
            partnerZone = ZoneId.of("Asia/Kathmandu"),
            now = System.currentTimeMillis(),
            onDismiss = {},
            onSchedule = {},
        )
    }

    @Test fun location() = shot("37-location", setup = {
        skipPairing()
        partnerSharesLocation(moving = false)
    }) { ui -> LocationSheet(ui.backend, "Maya", onDismiss = {}) }

    @Test fun partnerPaused() = shot("38-partner-paused", setup = { ui ->
        skipPairing()
        partnerPausesSharing(true)
        partnerWokeUp()
        ui.partnerSheetOpen = true
    })

    @Test fun playground() {
        val controller = AppController(AppGraph(ApplicationProvider.getApplicationContext()), MainScope())
        controller.goTo(Destination.Chat)
        compose.mainClock.autoAdvance = false
        compose.setContent { MamiTheme(Appearance(darkMode = DarkMode.LIGHT)) { Playground(controller) {} } }
        compose.mainClock.advanceTimeBy(2_500)
        compose.waitForIdle()
        captureScreenRoboImage("$dir/17-playground.png")
    }
}
