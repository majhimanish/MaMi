package app.mami.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.BuildConfig
import app.mami.data.SavedQuickStatus
import app.mami.ui.ErrorMessage
import app.mami.ui.MainViewModel
import app.mami.ui.onboarding.ToggleRow
import app.mami.ui.onboarding.shareOptions

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
)

private val durations = listOf(
    "30 min" to 30L * 60_000,
    "1 hour" to 60L * 60_000,
    "2 hours" to 2L * 60 * 60_000,
    "Until I change it" to null,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onVerify: () -> Unit) {
    val messenger = vm.messenger
    val shares by messenger.shares.collectAsStateWithLifecycle()
    val quick by messenger.quickStatus.collectAsStateWithLifecycle()
    val displayName by messenger.displayName.collectAsStateWithLifecycle()
    var lowBattery by remember { mutableStateOf(messenger.lowBatteryAlerts) }
    var hideText by remember { mutableStateOf(messenger.hideNotificationText) }
    var pickedPreset by remember { mutableStateOf<Preset?>(null) }
    var editingName by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val partnerName = messenger.partnerName

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Section("You")
            OutlinedButton(onClick = { editingName = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Name: $displayName")
            }

            Section("Your status")
            val activeQuick = quick?.takeIf { it.untilMs == null || it.untilMs > System.currentTimeMillis() }
            Text(
                activeQuick?.let { "${it.emoji} ${it.label} — $partnerName can see this" } ?: "Let $partnerName know what you're up to.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = activeQuick?.label == preset.label,
                        onClick = { pickedPreset = preset },
                        label = { Text("${preset.emoji} ${preset.label}") },
                    )
                }
            }
            if (activeQuick != null) {
                TextButton(onClick = { messenger.setQuickStatus(null) }) { Text("Clear status") }
            }

            Section("What you share with $partnerName")
            shareOptions.forEach { option ->
                ToggleRow(option.icon, option.title, option.detail, checked = option.kind in shares) { on ->
                    messenger.setShares(if (on) shares + option.kind else shares - option.kind)
                }
            }
            ToggleRow(
                Icons.Filled.BatteryAlert,
                "Dying battery alert",
                "Tell $partnerName automatically when your phone is about to switch off",
                checked = lowBattery,
            ) {
                lowBattery = it
                messenger.lowBatteryAlerts = it
            }
            Text(
                "Sharing is two-way: you only see what you share too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Notifications")
            ToggleRow(
                Icons.Filled.VisibilityOff,
                "Hide message text",
                "Notifications say \"New message\" instead of showing it",
                checked = hideText,
            ) {
                hideText = it
                messenger.hideNotificationText = it
            }

            Section("Privacy")
            NavigationRow(
                Icons.Filled.Lock,
                "Verify $partnerName's security code",
                "Compare a code in person to be sure no one is in between",
                onClick = onVerify,
            )

            Section("Account")
            Text(
                "Signed in as ${messenger.email.orEmpty()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ErrorMessage(vm.error)
            OutlinedButton(onClick = { confirm = Confirm.Unlink }, modifier = Modifier.fillMaxWidth()) {
                Text("Unlink from $partnerName")
            }
            OutlinedButton(onClick = { confirm = Confirm.SignOut }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
            TextButton(onClick = { confirm = Confirm.Delete }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete my account", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "MaMi ${BuildConfig.VERSION_NAME}" + if (BuildConfig.DEBUG) " · ${messenger.serverUrl}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
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
                            messenger.setQuickStatus(
                                SavedQuickStatus(preset.emoji, preset.label, length?.let { System.currentTimeMillis() + it }),
                            )
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
                TextButton(
                    onClick = { vm.run({ messenger.setDisplayName(name) }) { editingName = false } },
                    enabled = name.isNotBlank(),
                ) { Text("Save") }
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
                    when (action) {
                        Confirm.Unlink -> vm.run({ messenger.unpair() })
                        Confirm.SignOut -> vm.run({ messenger.signOut() })
                        Confirm.Delete -> vm.run({ messenger.deleteAccount() })
                    }
                }) { Text(button, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private enum class Confirm { Unlink, SignOut, Delete }

@Composable
private fun NavigationRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp),
    )
}
