package app.mami.ui.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.data.InviteDto
import app.mami.ui.Format
import app.mami.ui.Stage
import app.mami.ui.UiController
import app.mami.ui.components.CodeBoxes
import app.mami.ui.components.ErrorMessage
import app.mami.ui.components.GlassCard
import app.mami.ui.components.GradientButton
import app.mami.ui.components.OrDivider
import app.mami.ui.components.TypingDots
import app.mami.ui.onboarding.DemoHint
import app.mami.ui.onboarding.OnboardingScaffold
import app.mami.ui.onboarding.ScreenTitle
import app.mami.ui.theme.Mami

@Composable
fun PairScreen(ui: UiController) {
    val invite by ui.backend.invite.collectAsStateWithLifecycle()
    val current = invite
    if (current != null && current.expiresAtMs > System.currentTimeMillis()) {
        WaitingForPartner(ui, current)
    } else {
        ChooseHowToPair(ui)
    }
}

@Composable
private fun ChooseHowToPair(ui: UiController) {
    var partnerEmail by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }

    OnboardingScaffold(ui, Stage.Pair) {
        ScreenTitle("💞", "Link with your person", "One of you creates an invite, the other enters the code. Then it's just the two of you.")

        GlassCard {
            Text("Invite them", style = MaterialTheme.typography.titleLarge)
            Text(
                "Add their email and only they can use the code. Or leave it empty and send the code yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = partnerEmail,
                onValueChange = {
                    partnerEmail = it.trim()
                    ui.clearError()
                },
                label = { Text("Their email (optional)") },
                leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            GradientButton("Create invite", onClick = { ui.run({ ui.backend.createInvite(partnerEmail) }) }, busy = ui.busy)
        }

        OrDivider()

        GlassCard {
            Text("I have a code", style = MaterialTheme.typography.titleLarge)
            Text(
                "Type the 8 letters and numbers your partner sent you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            CodeBoxes(
                value = code,
                length = 8,
                letters = true,
                separatorAfter = 4,
                onValueChange = {
                    code = it
                    ui.clearError()
                },
            )
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(
                onClick = { ui.run({ ui.backend.acceptInvite(code) }) },
                enabled = code.length == 8 && !ui.busy,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) { Text("Join my partner", style = MaterialTheme.typography.titleMedium) }
        }
        ErrorMessage(ui.error)
        DemoHint(ui, "Demo mode: any 8-character code links you with Maya.")
        Spacer(Modifier.height(20.dp))
        SignedInAs(ui)
    }
}

@Composable
private fun WaitingForPartner(ui: UiController, invite: InviteDto) {
    val context = LocalContext.current
    val message = "Let's use MaMi together 💗 Install the app, sign in" +
        (invite.partnerEmail?.let { " with $it" } ?: "") +
        " and enter this code: ${invite.code}"
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "scale",
    )

    OnboardingScaffold(ui, Stage.Pair, hearts = true) {
        Box(Modifier.graphicsLayer {
            scaleX = pulse
            scaleY = pulse
        }) {
            Text("💌", fontSize = 84.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text("Your invite code", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            invite.partnerEmail?.let { "We emailed it to $it. You can also send it yourself." }
                ?: "Send it to your partner. It works once and expires ${Format.relative(invite.expiresAtMs, System.currentTimeMillis())}.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        GlassCard {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                invite.code.forEach { char ->
                    if (char == '-') {
                        Text("–", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.outline)
                    } else {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = BorderStroke(1.5.dp, Mami.colors.gradient.first().copy(alpha = 0.5f)),
                            modifier = Modifier.weight(1f).aspectRatio(0.8f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    char.toString(),
                                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("MaMi invite code", invite.code))
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Copy")
                }
                FilledTonalButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
                        context.startActivity(Intent.createChooser(send, "Send your invite"))
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Share")
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypingDots(color = MaterialTheme.colorScheme.primary)
            Text("   Waiting for your partner to join", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DemoHint(ui, "Demo mode: Maya joins in a few seconds.")
        ErrorMessage(ui.error)
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = { ui.run({ ui.backend.refresh() }) }) { Text("They joined? Check now") }
        TextButton(onClick = { ui.run({ ui.backend.cancelInvite() }) }) { Text("Cancel invite") }
        SignedInAs(ui)
    }
}

@Composable
private fun SignedInAs(ui: UiController) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 8.dp)) {
        Text(
            "Signed in as ${ui.backend.email.orEmpty()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { ui.run({ ui.backend.signOut() }) }) { Text("Sign out") }
    }
}
