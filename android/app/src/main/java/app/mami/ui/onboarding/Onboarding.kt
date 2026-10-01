package app.mami.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mami.BuildConfig
import app.mami.core.ShareKind
import app.mami.demo.DemoBackend
import app.mami.ui.SignInStep
import app.mami.ui.Stage
import app.mami.ui.UiController
import app.mami.ui.components.AuroraBackground
import app.mami.ui.components.Avatar
import app.mami.ui.components.CodeBoxes
import app.mami.ui.components.ErrorMessage
import app.mami.ui.components.FloatingHearts
import app.mami.ui.components.GlassCard
import app.mami.ui.components.GradientButton
import app.mami.ui.components.SignalBars
import app.mami.ui.components.StatusPill
import app.mami.ui.components.ToggleRow
import app.mami.ui.theme.Mami
import kotlinx.coroutines.launch

/** Aurora background, a back button, a debug "Skip" and a centred scrolling column. */
@Composable
fun OnboardingScaffold(
    ui: UiController,
    stage: Stage?,
    onBack: (() -> Unit)? = null,
    hearts: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    AuroraBackground {
        if (hearts) FloatingHearts()
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
                Spacer(Modifier.weight(1f))
                if (ui.canSkip && stage != null) {
                    TextButton(onClick = { ui.skip(stage) }) {
                        Text("Skip")
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = content,
                )
            }
        }
    }
}

/** A big emoji in a glowing gradient bubble, then a title and a line of explanation. */
@Composable
fun ScreenTitle(emoji: String, title: String, subtitle: String?) {
    val glow = Mami.colors.gradient.first()
    Box(
        Modifier
            .size(96.dp)
            .shadow(24.dp, CircleShape, ambientColor = glow, spotColor = glow)
            .clip(CircleShape)
            .background(Mami.colors.brush),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 46.sp)
    }
    Spacer(Modifier.height(22.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    if (subtitle != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(26.dp))
}

// ---- sign-in ------------------------------------------------------------------------------

@Composable
fun SignInFlow(ui: UiController) {
    BackHandler(enabled = ui.signInStep != SignInStep.Welcome) {
        ui.clearError()
        ui.signInStep = if (ui.signInStep == SignInStep.Code) SignInStep.Email else SignInStep.Welcome
    }
    AnimatedContent(
        targetState = ui.signInStep,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
        },
        label = "signin",
    ) { step ->
        when (step) {
            SignInStep.Welcome -> WelcomeStep(ui)
            SignInStep.Email -> EmailStep(ui)
            SignInStep.Code -> CodeStep(ui)
        }
    }
}

private data class Intro(val emoji: String, val title: String, val text: String)

private val intro = listOf(
    Intro("💞", "Just the two of you", "A private little world for you and your person. Nobody else is in here, not even us."),
    Intro("🔋", "No more guessing", "Dead battery? Asleep? On silent? Driving? See it at a glance, before the worrying starts."),
    Intro("🔒", "Locked tight", "Every message is end-to-end encrypted on your phones. Only the two of you hold the keys."),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WelcomeStep(ui: UiController) {
    val pager = rememberPagerState(pageCount = { intro.size })
    val scope = rememberCoroutineScope()
    OnboardingScaffold(ui, Stage.SignIn, hearts = true) {
        Spacer(Modifier.height(12.dp))
        Text("MaMi", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(340.dp)) { page ->
            val item = intro[page]
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                ScreenTitle(item.emoji, item.title, item.text)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(intro.size) { i ->
                val selected = pager.currentPage == i
                val width by animateDpAsState(if (selected) 26.dp else 8.dp, label = "dot")
                Box(
                    Modifier
                        .height(8.dp)
                        .width(width)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        val last = pager.currentPage == intro.lastIndex
        GradientButton(if (last) "Let's begin" else "Next", onClick = {
            if (last) ui.signInStep = SignInStep.Email else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
        })
        TextButton(onClick = { ui.signInStep = SignInStep.Email }) { Text("I already have an account") }
    }
}

@Composable
private fun EmailStep(ui: UiController) {
    var editingServer by rememberSaveable { mutableStateOf(false) }
    val submit = {
        ui.run({ ui.backend.requestCode(ui.signInEmail) }) { ui.signInStep = SignInStep.Code }
    }
    OnboardingScaffold(ui, Stage.SignIn, onBack = { ui.signInStep = SignInStep.Welcome }) {
        ScreenTitle("💌", "What's your email?", "We'll send you a 6-digit code. No passwords to remember.")
        GlassCard {
            OutlinedTextField(
                value = ui.signInEmail,
                onValueChange = {
                    ui.signInEmail = it.trim()
                    ui.clearError()
                },
                label = { Text("Email address") },
                leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorMessage(ui.error)
            Spacer(Modifier.height(14.dp))
            GradientButton("Send me a code", onClick = submit, busy = ui.busy, enabled = ui.signInEmail.contains('@'))
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text(
                "  End-to-end encrypted. Not even MaMi can read your messages.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (BuildConfig.DEBUG && ui.backend !is DemoBackend) {
            TextButton(onClick = { editingServer = true }) { Text("Server: ${ui.backend.serverUrl.ifEmpty { "not set" }}") }
        }
        DemoHint(ui, "Demo mode: any email and code work.")
    }
    if (editingServer) {
        var url by rememberSaveable { mutableStateOf(ui.backend.serverUrl) }
        AlertDialog(
            onDismissRequest = { editingServer = false },
            title = { Text("Server address") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    placeholder = { Text("https://mami.example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ui.backend.serverUrl = url
                    editingServer = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingServer = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CodeStep(ui: UiController) {
    var code by rememberSaveable { mutableStateOf("") }
    val verify = { ui.run({ ui.backend.verifyCode(ui.signInEmail, code) }) }
    OnboardingScaffold(ui, Stage.SignIn, onBack = {
        ui.clearError()
        ui.signInStep = SignInStep.Email
    }) {
        ScreenTitle("📬", "Check your inbox", "Enter the 6-digit code we sent to\n${ui.signInEmail}")
        GlassCard {
            CodeBoxes(
                value = code,
                length = 6,
                onValueChange = {
                    code = it
                    ui.clearError()
                    if (it.length == 6 && !ui.busy) ui.run({ ui.backend.verifyCode(ui.signInEmail, it) })
                },
                isError = ui.error != null,
            )
            ErrorMessage(ui.error)
            Spacer(Modifier.height(14.dp))
            GradientButton("Continue", onClick = verify, busy = ui.busy, enabled = code.length == 6)
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { ui.run({ ui.backend.requestCode(ui.signInEmail) }) }) { Text("Send a new code") }
        DemoHint(ui, "Demo mode: any code works, 000000 shows the error.")
    }
}

/** A gentle note shown only while the simulated partner is in use. */
@Composable
fun DemoHint(ui: UiController, text: String) {
    if (ui.backend !is DemoBackend) return
    Spacer(Modifier.height(8.dp))
    StatusPill(icon = null, text = "🐞 $text", tint = MaterialTheme.colorScheme.tertiary)
}

// ---- name ------------------------------------------------------------------------------------

private val nicknames = listOf("Love 💗", "Babe", "Sunshine ☀️", "Honey 🍯", "Jaan 💞", "Cutie 🥰")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(ui: UiController) {
    var name by rememberSaveable { mutableStateOf("") }
    val bounce by animateFloatAsState(if (name.isEmpty()) 0.9f else 1f, spring(dampingRatio = 0.4f), label = "bounce")
    OnboardingScaffold(ui, Stage.Profile) {
        Spacer(Modifier.height(8.dp))
        Avatar(
            name.ifBlank { "?" },
            size = 104.dp,
            modifier = Modifier.graphicsLayer {
                scaleX = bounce
                scaleY = bounce
            },
        )
        Spacer(Modifier.height(22.dp))
        Text("What should they call you?", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your partner sees this name. A nickname is perfect.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        GlassCard {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(40)
                    ui.clearError()
                },
                label = { Text("Your name") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                nicknames.forEach { nick ->
                    AssistChip(onClick = { name = nick }, label = { Text(nick) })
                }
            }
            ErrorMessage(ui.error)
            Spacer(Modifier.height(10.dp))
            GradientButton("Continue", onClick = { ui.run({ ui.backend.setDisplayName(name) }) }, busy = ui.busy, enabled = name.isNotBlank())
        }
    }
}

// ---- sharing ---------------------------------------------------------------------------------

/** What each kind of sharing means, in plain words. */
data class ShareOption(val kind: ShareKind, val icon: ImageVector, val title: String, val detail: String)

val shareOptions = listOf(
    ShareOption(ShareKind.BATTERY, Icons.Filled.BatteryChargingFull, "Battery", "How full it is and whether it's charging"),
    ShareOption(ShareKind.NETWORK, Icons.Filled.Wifi, "Connection", "Wi-Fi or mobile data, and signal strength"),
    ShareOption(ShareKind.RINGER, Icons.Filled.NotificationsOff, "Silent mode", "On silent, vibrate or Do Not Disturb"),
    ShareOption(ShareKind.LOCAL_TIME, Icons.Filled.Public, "Local time", "So they know when it's night where you are"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharingScreen(ui: UiController) {
    var selected by rememberSaveable { mutableStateOf(ui.backend.shares.value.map { it.name }.toSet()) }
    var lowBattery by rememberSaveable { mutableStateOf(ui.backend.lowBatteryAlerts) }
    val finish = {
        ui.backend.confirmSharing(
            shares = selected.mapNotNull { name -> ShareKind.entries.firstOrNull { it.name == name } }.toSet(),
            lowBatteryAlerts = lowBattery,
        )
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    OnboardingScaffold(ui, Stage.Sharing) {
        ScreenTitle("🫶", "Share what helps", "So your partner never has to wonder why you're quiet. All optional, all encrypted.")

        Text("They'll see something like", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if ("BATTERY" in selected) StatusPill(Icons.Filled.BatteryChargingFull, "82% · charging", tint = Mami.colors.good)
            if ("NETWORK" in selected) StatusPill(Icons.Filled.Wifi, "Wi-Fi", trailing = { SignalBars(3, color = MaterialTheme.colorScheme.primary) })
            if ("RINGER" in selected) StatusPill(Icons.Filled.NotificationsOff, "On silent", tint = MaterialTheme.colorScheme.tertiary)
            if ("LOCAL_TIME" in selected) StatusPill(Icons.Filled.Public, "9:41 PM for you", tint = MaterialTheme.colorScheme.secondary)
            if (selected.isEmpty()) StatusPill(null, "Nothing — and you'll see nothing from them")
        }
        Spacer(Modifier.height(18.dp))

        GlassCard {
            shareOptions.forEach { option ->
                ToggleRow(option.icon, option.title, option.detail, checked = option.kind.name in selected) { on ->
                    selected = if (on) selected + option.kind.name else selected - option.kind.name
                }
            }
            ToggleRow(
                Icons.Filled.BatteryAlert,
                "Dying battery alert",
                "Tell them automatically when your phone is about to switch off",
                checked = lowBattery,
            ) { lowBattery = it }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Sharing is always two-way: you only see what you share too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        GradientButton("Continue", onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                finish()
            }
        })
    }
}
