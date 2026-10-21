package llc.applabs.switchboard.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import llc.applabs.design.tokens.MonoLabel
import llc.applabs.switchboard.ui.common.ErrorBanner
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(viewModel: ConnectViewModel, devSignIn: Boolean, scanQr: (suspend () -> String?)? = null) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val s by viewModel.state.collectAsState()
    val on = viewModel::onEvent
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text("SWITCHBOARD", style = MonoLabel, color = MaterialTheme.colorScheme.primary)
            Text(
                when (s.step) {
                    ConnectStep.Address -> "Connect to your hub"
                    ConnectStep.Choose -> s.hub?.name ?: "Your hub"
                    ConnectStep.PairOwner -> "Pair this phone"
                    ConnectStep.Join -> "Accept an invite"
                    ConnectStep.Token -> "Sign in with a test token"
                },
                style = MaterialTheme.typography.headlineMedium,
            )
            when (s.step) {
                ConnectStep.Address -> {
                    Text("The hub runs on your own computer and connects you, and the people you share it with, to your AI agents. Enter its address, or open an invite link.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Field("Hub address or invite link", s.hubUrl, { on(ConnectUiEvent.HubUrl(it)) }, "e.g. mini-1.your-tailnet.ts.net", KeyboardType.Uri, "hub-url")
                    Primary("Continue", s.busy, s.hubUrl.isNotBlank()) { on(ConnectUiEvent.Continue) }
                    scanQr?.let { scan ->
                        OutlinedButton(onClick = { scope.launch { scan()?.let { on(ConnectUiEvent.Scanned(it)) } } },
                            enabled = !s.busy, modifier = Modifier.fillMaxWidth().testTag("scan-qr")) { Text("Scan a QR code") }
                        Text("The setup code on the hub computer, or an invite you were sent.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ConnectStep.Choose -> {
                    s.hub?.let { Text("Hub ${it.hubVersion} at ${s.hubUrl}", style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Primary("Sign in with passkey", s.busy, true) { on(ConnectUiEvent.ChoosePasskey) }
                    OutlinedButton(onClick = { on(ConnectUiEvent.ChooseJoin) }, modifier = Modifier.fillMaxWidth().testTag("join")) { Text("I have an invite") }
                    TextButton(onClick = { on(ConnectUiEvent.ChooseSetupCode) }, modifier = Modifier.testTag("setup-code")) { Text("Owner: add this phone with a setup code") }
                    if (devSignIn) TextButton(onClick = { on(ConnectUiEvent.ChooseToken) }, modifier = Modifier.testTag("dev-token")) { Text("Use a test token (debug build)") }
                    TextButton(onClick = { on(ConnectUiEvent.Back) }) { Text("Different hub") }
                }
                ConnectStep.PairOwner -> {
                    Text(if (s.hub?.setupRequired == true) "This hub has no owner yet. On the hub machine, find the setup code in the hub's log, or run switchboard-hub setup-code."
                        else "Add this phone to the owner's account (a new or replacement phone): on the hub machine run switchboard-hub setup-code.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Field("Setup code", s.code, { on(ConnectUiEvent.Code(it)) }, "XXXX-XXXX", KeyboardType.Ascii, "setup-code", caps = true)
                    Field("Your name", s.name, { on(ConnectUiEvent.Name(it)) }, "Shown to other members", KeyboardType.Text, "name")
                    Primary("Create passkey and pair", s.busy, s.code.isNotBlank() && s.name.isNotBlank()) { on(ConnectUiEvent.Submit) }
                    if (devSignIn) TextButton(onClick = { on(ConnectUiEvent.ChooseToken) }) { Text("Use a test token (debug build)") }
                    TextButton(onClick = { on(ConnectUiEvent.Back) }) { Text("Back") }
                }
                ConnectStep.Join -> {
                    val inv = s.invite
                    if (inv == null) {
                        Field("Invite code", s.code, { on(ConnectUiEvent.Code(it)) }, "XXXX-XXXX-XXXX", KeyboardType.Ascii, "invite-code", caps = true)
                        Primary("Check invite", s.busy, s.code.isNotBlank()) { on(ConnectUiEvent.Submit) }
                    } else {
                        Text("${inv.invitedBy} invited you to ${inv.hubName} as ${inv.role}.", style = MaterialTheme.typography.bodyLarge)
                        Text("Your chats are private. The owner can see which agents and models you use, but not what you write.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Field("Your name", s.name, { on(ConnectUiEvent.Name(it)) }, "Shown to other members", KeyboardType.Text, "name")
                        Primary("Create passkey and join", s.busy, s.name.isNotBlank()) { on(ConnectUiEvent.Submit) }
                    }
                    TextButton(onClick = { on(ConnectUiEvent.Back) }) { Text("Back") }
                }
                ConnectStep.Token -> {
                    Text("For testing before passkeys work: on the hub machine run switchboard-hub dev-token and paste the token.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Field("Token", s.token, { on(ConnectUiEvent.Token(it)) }, "", KeyboardType.Password, "token", secret = true)
                    Primary("Sign in", s.busy, s.token.isNotBlank()) { on(ConnectUiEvent.Submit) }
                    TextButton(onClick = { on(ConnectUiEvent.Back) }) { Text("Back") }
                }
            }
            s.error?.let { ErrorBanner(it, onRetry = null) }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String, type: KeyboardType, tag: String,
                  caps: Boolean = false, secret: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, placeholder = { Text(placeholder) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Words, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun Primary(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("primary")) {
        if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp)) else Text(text)
    }
}
