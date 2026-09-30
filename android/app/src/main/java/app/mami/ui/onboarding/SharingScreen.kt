package app.mami.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mami.core.ShareKind
import app.mami.ui.MainViewModel
import app.mami.ui.OnboardingPage
import app.mami.ui.PrimaryButton

/** What each kind of sharing means, in plain words. */
data class ShareOption(val kind: ShareKind, val icon: ImageVector, val title: String, val detail: String)

val shareOptions = listOf(
    ShareOption(ShareKind.BATTERY, Icons.Filled.BatteryChargingFull, "Battery", "How full it is and whether it's charging"),
    ShareOption(ShareKind.NETWORK, Icons.Filled.Wifi, "Connection", "Wi-Fi or mobile data, and signal strength"),
    ShareOption(ShareKind.RINGER, Icons.Filled.NotificationsOff, "Silent mode", "Whether your phone is on silent, vibrate or Do Not Disturb"),
    ShareOption(ShareKind.LOCAL_TIME, Icons.Filled.Public, "Local time", "Your time zone, so they know if it's night where you are"),
)

@Composable
fun SharingScreen(vm: MainViewModel) {
    var selected by rememberSaveable { mutableStateOf(vm.messenger.shares.value.map { it.name }.toSet()) }
    var lowBattery by rememberSaveable { mutableStateOf(vm.messenger.lowBatteryAlerts) }
    val finish = {
        vm.messenger.confirmSharing(
            shares = selected.mapNotNull { name -> ShareKind.entries.firstOrNull { it.name == name } }.toSet(),
            lowBatteryAlerts = lowBattery,
        )
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    OnboardingPage(
        title = "Share what helps",
        subtitle = "So your partner doesn't have to guess why you're quiet. Everything is optional and end-to-end encrypted.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            shareOptions.forEach { option ->
                ToggleRow(option.icon, option.title, option.detail, checked = option.kind.name in selected) { on ->
                    selected = if (on) selected + option.kind.name else selected - option.kind.name
                }
            }
            ToggleRow(
                Icons.Filled.BatteryAlert,
                "Dying battery alert",
                "Tell your partner automatically when your phone is about to switch off",
                checked = lowBattery,
            ) { lowBattery = it }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "You only see what you share too. Change this anytime in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Continue", busy = false) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                finish()
            }
        }
    }
}

@Composable
fun ToggleRow(icon: ImageVector, title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = { onChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
