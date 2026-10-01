package app.mami.ui.settings

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.BuildConfig
import app.mami.data.SavedQuickStatus
import app.mami.ui.Overlay
import app.mami.ui.UiController
import app.mami.ui.components.Avatar
import app.mami.ui.components.ErrorMessage
import app.mami.ui.components.NavRow
import app.mami.ui.components.SectionCard
import app.mami.ui.components.ToggleRow
import app.mami.ui.onboarding.shareOptions
import app.mami.ui.theme.DarkMode
import app.mami.ui.theme.Palette

private data class Preset(val emoji: String, val label: String)

private val presets = listOf(
    Preset("🚗", "Driving"),
    Preset("💼", "At work"),
    Preset("📚", "Studying"),
    Preset("🏋️", "At the gym"),
    Preset("🍽️", "Eating"),
    Preset("😴", "Sleeping"),
    Preset("👨‍👩‍👧", "With family"),
    Preset("🎬", "At the movies"),
    Preset("🛫", "Travelling"),
)

private val durations = listOf(
    "30 minutes" to 30L * 60_000,
    "1 hour" to 60L * 60_000,
    "2 hours" to 2L * 60 * 60_000,
    "Until I change it" to null,
)

private enum class Confirm { Unlink, SignOut, Delete }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(ui: UiController) {
    val backend = ui.backend
    val shares by backend.shares.collectAsStateWithLifecycle()
    val quick by backend.quickStatus.collectAsStateWithLifecycle()
    val displayName by backend.displayName.collectAsStateWithLifecycle()
    val partner by backend.partner.collectAsStateWithLifecycle()
    var lowBattery by remember(backend) { mutableStateOf(backend.lowBatteryAlerts) }
    var hideText by remember(backend) { mutableStateOf(backend.hideNotificationText) }
    var previews by remember(backend) { mutableStateOf(backend.linkPreviews) }
    var pickedPreset by remember { mutableStateOf<Preset?>(null) }
    var editingName by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val partnerName = partner?.displayName?.ifBlank { null } ?: "your partner"
    val appearance = ui.appearance

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    IconButton(onClick = { ui.overlay = Overlay.None }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Profile
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                onClick = { editingName = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(displayName.ifBlank { "?" }, size = 60.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(displayName.ifBlank { "Add your name" }, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            backend.email.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        )
                    }
                    Icon(Icons.Filled.Edit, contentDescription = "Edit name")
                }
            }

            // Quick status
            SectionCard("Your status") {
                val active = quick?.takeIf { it.untilMs == null || it.untilMs > System.currentTimeMillis() }
                Text(
                    active?.let { "${it.emoji} ${it.label} — ${partnerName.replaceFirstChar { it.uppercase() }} can see this" }
                        ?: "Let ${partnerName} know what you're up to.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = active?.label == preset.label,
                            onClick = { pickedPreset = preset },
                            label = { Text("${preset.emoji} ${preset.label}") },
                        )
                    }
                }
                if (active != null) {
                    TextButton(onClick = { backend.setQuickStatus(null) }, modifier = Modifier.padding(start = 6.dp)) { Text("Clear status") }
                }
            }

            // Appearance
            SectionCard("Appearance") {
                Text("Colours", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Palette.entries
                        .filter { it != Palette.WALLPAPER || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
                        .forEach { palette ->
                            PaletteSwatch(palette, selected = appearance.palette == palette) {
                                ui.changeAppearance(appearance.copy(palette = palette))
                            }
                        }
                }
                Text("Theme", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    DarkMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = appearance.darkMode == mode,
                            onClick = { ui.changeAppearance(appearance.copy(darkMode = mode)) },
                            shape = SegmentedButtonDefaults.itemShape(index, DarkMode.entries.size),
                        ) { Text(mode.label) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                ToggleRow(Icons.Filled.Wallpaper, "Heart wallpaper", "Little hearts behind your chat", appearance.chatWallpaper) {
                    ui.changeAppearance(appearance.copy(chatWallpaper = it))
                }
            }

            // Sharing
            SectionCard("What you share with $partnerName") {
                shareOptions.forEach { option ->
                    ToggleRow(option.icon, option.title, option.detail, checked = option.kind in shares) { on ->
                        backend.setShares(if (on) shares + option.kind else shares - option.kind)
                    }
                }
                ToggleRow(
                    Icons.Filled.BatteryAlert,
                    "Dying battery alert",
                    "Tell $partnerName automatically when your phone is about to switch off",
                    checked = lowBattery,
                ) {
                    lowBattery = it
                    backend.lowBatteryAlerts = it
                }
                Text(
                    "Sharing is two-way: you only see what you share too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Notifications and privacy
            SectionCard("Privacy") {
                ToggleRow(Icons.Filled.VisibilityOff, "Hide message text", "Notifications say \"New message\" instead", hideText) {
                    hideText = it
                    backend.hideNotificationText = it
                }
                ToggleRow(Icons.Filled.Link, "Link previews", "Your phone fetches a title and picture before sending a link. The MaMi server never sees your links.", previews) {
                    previews = it
                    backend.linkPreviews = it
                }
                NavRow(Icons.Filled.Lock, "Verify ${partnerName}'s security code", "Compare a code to be sure no one is in between") {
                    ui.overlay = Overlay.Safety
                }
            }

            // Account
            SectionCard("Account") {
                NavRow(Icons.Filled.HeartBroken, "Unlink from $partnerName", "Deletes the conversation on both phones", tint = MaterialTheme.colorScheme.error) {
                    confirm = Confirm.Unlink
                }
                NavRow(Icons.AutoMirrored.Filled.Logout, "Sign out", null) { confirm = Confirm.SignOut }
                NavRow(Icons.Filled.DeleteForever, "Delete my account", null, tint = MaterialTheme.colorScheme.error) { confirm = Confirm.Delete }
            }
            ErrorMessage(ui.error)

            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.FavoriteBorder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "MaMi ${BuildConfig.VERSION_NAME} · end-to-end encrypted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (BuildConfig.DEBUG) {
                    Text(backend.serverUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    pickedPreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { pickedPreset = null },
            title = { Text("${preset.emoji} ${preset.label}") },
            text = {
                Column {
                    Text("For how long?")
                    durations.forEach { (label, length) ->
                        TextButton(onClick = {
                            backend.setQuickStatus(SavedQuickStatus(preset.emoji, preset.label, length?.let { System.currentTimeMillis() + it }))
                            pickedPreset = null
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickedPreset = null }) { Text("Cancel") } },
        )
    }

    if (editingName) {
        var name by rememberSaveable { mutableStateOf(displayName) }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Your name") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { ui.run({ backend.setDisplayName(name) }) { editingName = false } }, enabled = name.isNotBlank()) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } },
        )
    }

    confirm?.let { action ->
        val (title, text, button) = when (action) {
            Confirm.Unlink -> Triple(
                "Unlink from $partnerName?",
                "Your conversation will be deleted from both phones. You can link again later with a new invite.",
                "Unlink",
            )
            Confirm.SignOut -> Triple(
                "Sign out?",
                "Your messages and keys will be removed from this phone. Signing in again creates new keys.",
                "Sign out",
            )
            Confirm.Delete -> Triple(
                "Delete your account?",
                "This unlinks you from $partnerName and deletes your account. It can't be undone.",
                "Delete",
            )
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    ui.overlay = Overlay.None
                    when (action) {
                        Confirm.Unlink -> ui.run({ backend.unpair() })
                        Confirm.SignOut -> ui.run({ backend.signOut() })
                        Confirm.Delete -> ui.run({ backend.deleteAccount() })
                    }
                }) { Text(button, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PaletteSwatch(palette: Palette, selected: Boolean, onClick: () -> Unit) {
    val ring by animateDpAsState(if (selected) 3.dp else 0.dp, label = "ring")
    val colors = palette.gradient.ifEmpty { listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .size(54.dp)
                .border(BorderStroke(ring, MaterialTheme.colorScheme.onSurface), CircleShape)
                .padding(5.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(colors)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(palette.label, style = MaterialTheme.typography.labelMedium)
    }
}
