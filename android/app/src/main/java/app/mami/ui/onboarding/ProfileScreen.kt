package app.mami.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.mami.ui.ErrorMessage
import app.mami.ui.MainViewModel
import app.mami.ui.OnboardingPage
import app.mami.ui.PrimaryButton

@Composable
fun ProfileScreen(vm: MainViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    OnboardingPage(
        title = "What should your partner call you?",
        subtitle = "This is the name they see in MaMi. A nickname is perfect.",
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(40)
                vm.clearError()
            },
            label = { Text("Your name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorMessage(vm.error)
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Continue", busy = vm.busy, enabled = name.isNotBlank()) {
            vm.run({ vm.messenger.setDisplayName(name) })
        }
    }
}
