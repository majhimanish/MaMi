package app.mami.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.ui.MainViewModel
import app.mami.ui.PrimaryButton

/**
 * Both phones show the same numbers only if nobody (not even the MaMi server)
 * swapped the keys in between.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyScreen(vm: MainViewModel, onBack: () -> Unit) {
    val messenger = vm.messenger
    val partner by messenger.partner.collectAsStateWithLifecycle()
    val verifiedKey by messenger.verifiedPartnerKey.collectAsStateWithLifecycle()
    val code by produceState<String?>(null, partner) { value = runCatching { messenger.safetyCode() }.getOrNull() }
    val name = messenger.partnerName
    val verified = verifiedKey != null && verifiedKey == partner?.identity?.ed25519

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security code") },
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Open this screen on both phones, side by side or on a video call. If the numbers match, only you and $name can read your messages.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                val groups = code?.split(" ").orEmpty()
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (groups.isEmpty()) {
                        Text("$name's phone hasn't set up its keys yet.", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    groups.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { group ->
                                Text(group, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (verified) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                    Text("  You verified $name", style = MaterialTheme.typography.titleMedium)
                }
            } else if (code != null) {
                PrimaryButton("The numbers match", busy = false) { messenger.markPartnerVerified() }
                Spacer(Modifier.height(12.dp))
                Text(
                    "If they don't match, don't send anything private and contact us.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
