package app.mami.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.ui.Overlay
import app.mami.ui.UiController
import app.mami.ui.components.GradientButton
import app.mami.ui.theme.Mami

/**
 * Both phones show the same numbers only if nobody (not even the MaMi server)
 * swapped the keys in between.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyScreen(ui: UiController) {
    val backend = ui.backend
    val partner by backend.partner.collectAsStateWithLifecycle()
    val verifiedKey by backend.verifiedPartnerKey.collectAsStateWithLifecycle()
    val code by produceState<String?>(null, partner) { value = runCatching { backend.safetyCode() }.getOrNull() }
    val name = partner?.displayName?.ifBlank { null } ?: "your partner"
    val verified = verifiedKey != null && verifiedKey == partner?.identity?.ed25519

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security code") },
                navigationIcon = {
                    IconButton(onClick = { ui.overlay = Overlay.Settings }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
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
            Box(Modifier.size(96.dp).clip(CircleShape).background(Mami.colors.brush), contentAlignment = Alignment.Center) {
                AnimatedContent(verified, transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() }, label = "shield") { ok ->
                    Icon(if (ok) Icons.Filled.VerifiedUser else Icons.Filled.Shield, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (verified) "You and $name are verified" else "Compare with $name",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Open this screen on both phones, side by side or on a video call. If the numbers match, only the two of you can read your messages.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            val groups = code?.split(" ").orEmpty()
            if (groups.isEmpty()) {
                Text("$name's phone hasn't set up its keys yet.", textAlign = TextAlign.Center)
            }
            groups.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { group ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                group,
                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (!verified && code != null) {
                GradientButton("The numbers match", onClick = backend::markPartnerVerified)
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
