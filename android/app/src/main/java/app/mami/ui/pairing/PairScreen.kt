package app.mami.ui.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.data.InviteDto
import app.mami.ui.ErrorMessage
import app.mami.ui.Format
import app.mami.ui.MainViewModel
import app.mami.ui.OnboardingPage
import app.mami.ui.PrimaryButton

@Composable
fun PairScreen(vm: MainViewModel) {
    val invite by vm.messenger.invite.collectAsStateWithLifecycle()
    val current = invite
    if (current != null && current.expiresAtMs > System.currentTimeMillis()) {
        WaitingForPartner(vm, current)
    } else {
        ChooseHowToPair(vm)
    }
}

@Composable
private fun ChooseHowToPair(vm: MainViewModel) {
    var partnerEmail by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }

    OnboardingPage(
        title = "Link with your partner",
        subtitle = "One of you creates an invite, the other enters the code. After that, it's just the two of you.",
    ) {
        Text("Invite your partner", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = partnerEmail,
            onValueChange = {
                partnerEmail = it
                vm.clearError()
            },
            label = { Text("Their email (optional)") },
            supportingText = { Text("We'll email them the code, and only they can use it.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Create invite", busy = vm.busy) {
            vm.run({ vm.messenger.createInvite(partnerEmail) })
        }

        Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Text("  or  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f))
        }

        Text("I have a code", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = {
                code = it.uppercase().take(12)
                vm.clearError()
            },
            label = { Text("Invite code") },
            placeholder = { Text("ABCD-EFGH") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = { vm.run({ vm.messenger.acceptInvite(code) }) },
            enabled = code.count(Char::isLetterOrDigit) >= 8 && !vm.busy,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = MaterialTheme.shapes.medium,
        ) { Text("Join my partner") }

        ErrorMessage(vm.error)
        Spacer(Modifier.height(24.dp))
        SignedInAs(vm)
    }
}

@Composable
private fun WaitingForPartner(vm: MainViewModel, invite: InviteDto) {
    val context = LocalContext.current
    val message = "Let's use MaMi together 💗 Install the app, sign in" +
        (invite.partnerEmail?.let { " with $it" } ?: "") +
        " and enter this code: ${invite.code}"

    OnboardingPage(
        title = "Your invite code",
        subtitle = invite.partnerEmail?.let { "We emailed it to $it. You can also send it yourself." }
            ?: "Send this code to your partner. It works once and expires ${Format.relative(invite.expiresAtMs, System.currentTimeMillis())}.",
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                invite.code,
                style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 28.dp),
            )
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
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("  Waiting for your partner to join…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ErrorMessage(vm.error)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { vm.run({ vm.messenger.refresh() }) }) { Text("They joined? Check now") }
        TextButton(onClick = { vm.run({ vm.messenger.cancelInvite() }) }) { Text("Cancel invite") }
        Spacer(Modifier.height(16.dp))
        SignedInAs(vm)
    }
}

@Composable
private fun SignedInAs(vm: MainViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Signed in as ${vm.messenger.email.orEmpty()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { vm.run({ vm.messenger.signOut() }) }) { Text("Sign out") }
    }
}
