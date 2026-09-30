package app.mami.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mami.BuildConfig
import app.mami.ui.ErrorMessage
import app.mami.ui.MainViewModel
import app.mami.ui.OnboardingPage
import app.mami.ui.PrimaryButton

@Composable
fun SignInScreen(vm: MainViewModel) {
    var email by rememberSaveable { mutableStateOf(vm.messenger.email.orEmpty()) }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var editingServer by rememberSaveable { mutableStateOf(false) }

    if (!codeSent) {
        OnboardingPage(
            title = "MaMi",
            subtitle = "A private space for just the two of you.\nNo guessing, no overthinking.",
        ) {
            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    vm.clearError()
                },
                label = { Text("Your email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.run({ vm.messenger.requestCode(email) }) { codeSent = true } }),
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorMessage(vm.error)
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Send me a code", busy = vm.busy, enabled = email.contains('@')) {
                vm.run({ vm.messenger.requestCode(email) }) { codeSent = true }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Your messages are end-to-end encrypted. Only your partner's phone can read them — not even MaMi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (BuildConfig.DEBUG || BuildConfig.DEFAULT_SERVER_URL.isEmpty()) {
                TextButton(onClick = { editingServer = true }) { Text("Server: ${vm.messenger.serverUrl.ifEmpty { "not set" }}") }
            }
        }
    } else {
        OnboardingPage(title = "Check your email", subtitle = "We sent a 6-digit code to\n$email") {
            OutlinedTextField(
                value = code,
                onValueChange = { value ->
                    code = value.filter(Char::isDigit).take(6)
                    vm.clearError()
                    if (code.length == 6 && !vm.busy) vm.run({ vm.messenger.verifyCode(email, code) })
                },
                label = { Text("Code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center, letterSpacing = 6.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorMessage(vm.error)
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Continue", busy = vm.busy, enabled = code.length == 6) {
                vm.run({ vm.messenger.verifyCode(email, code) })
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { vm.run({ vm.messenger.requestCode(email) }) }) { Text("Send a new code") }
            TextButton(onClick = {
                codeSent = false
                code = ""
                vm.clearError()
            }) { Text("Use a different email") }
        }
    }

    if (editingServer) {
        var url by rememberSaveable { mutableStateOf(vm.messenger.serverUrl) }
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
                    vm.messenger.serverUrl = url
                    editingServer = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingServer = false }) { Text("Cancel") } },
        )
    }
}
