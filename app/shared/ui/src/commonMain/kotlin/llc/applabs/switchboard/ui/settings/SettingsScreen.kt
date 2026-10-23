package llc.applabs.switchboard.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import llc.applabs.design.tokens.MonoLabel
import llc.applabs.switchboard.domain.AuthRepository
import llc.applabs.switchboard.domain.PushRepository
import llc.applabs.switchboard.domain.model.NotifyKinds
import androidx.compose.material3.Switch
import llc.applabs.switchboard.domain.MembersRepository
import llc.applabs.switchboard.domain.model.Device
import llc.applabs.switchboard.domain.model.Me
import llc.applabs.switchboard.ui.common.ErrorBanner
import llc.applabs.switchboard.ui.common.MonoChip
import llc.applabs.switchboard.ui.common.SectionLabel
import llc.applabs.switchboard.ui.common.friendly
import llc.applabs.switchboard.ui.common.shortTime

data class SettingsUiState(val devices: List<Device> = emptyList(), val error: String? = null)

class SettingsViewModel(private val auth: AuthRepository, private val members: MembersRepository, private val push: PushRepository) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        try { _state.update { it.copy(devices = members.devices(), error = null) } } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
    }
    fun removeDevice(id: String) = viewModelScope.launch {
        try { members.removeDevice(id); load() } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
    }
    fun signOut() = viewModelScope.launch { auth.signOut() }

    fun setNotify(kind: String, on: Boolean) = viewModelScope.launch {
        try { push.setPreference(kind, on); load() } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
    }
}

@Composable
private fun MoreRow(title: String, subtitle: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp).testTag(tag)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A More section that opens and closes; [summary] says what's inside while it's closed. */
@Composable
private fun Section(title: String, summary: String, tag: String, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Close" else "Open", onClick = onToggle)
            .padding(vertical = 14.dp).testTag("section-$tag").semantics { stateDescription = if (open) "Open" else "Closed" },
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (!open) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Text(if (open) "−" else "+", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp).clearAndSetSemantics {})
        }
        AnimatedVisibility(open) { Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() } }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Para(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))

@Composable
private fun Bullet(text: String) = Row(Modifier.padding(bottom = 4.dp)) {
    Text("•", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Link(label: String, url: String) {
    val uri = LocalUriHandler.current
    TextButton(onClick = { runCatching { uri.openUri(url) } }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(label) }
}

private fun notifyLabel(kind: String) = when (kind) {
    NotifyKinds.APPROVAL -> "Approvals waiting for me"
    NotifyKinds.QUESTION -> "Questions from agents"
    NotifyKinds.RUN_FINISHED -> "Long answers finished"
    NotifyKinds.JOB_FAILED -> "Failed scheduled jobs"
    NotifyKinds.JOB_FINISHED -> "Scheduled job results"
    NotifyKinds.MEMBER_JOINED -> "New members"
    NotifyKinds.TASK_NEEDS_YOU -> "Cards that need me"
    NotifyKinds.TASK_REVIEW -> "Cards ready for review"
    else -> kind
}

const val GITHUB_URL = "https://github.com/myapplabs/switchboard"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, me: Me, hubUrl: String, onMembers: (() -> Unit)? = null, onSpecialties: (() -> Unit)? = null,
                   onForge: (() -> Unit)? = null, appVersion: String = "") {
    val s by viewModel.state.collectAsState()
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    val toggle = { key: String -> open = if (key in open) open - key else open + key }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val thisDevice = s.devices.firstOrNull { it.thisDevice }
    Scaffold(topBar = { TopAppBar(title = { Text("More") }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            if (onMembers != null || onSpecialties != null || onForge != null) item {
                Section("Manage", listOfNotNull("FORGE".takeIf { onForge != null }, "Members".takeIf { onMembers != null },
                    "What agents are good at".takeIf { onSpecialties != null }).joinToString(" · "), "manage", "manage" in open, { toggle("manage") }) {
                    onForge?.let { MoreRow("FORGE", "Your FORGE tickets on the board; decide FORGE's gates here", "more-forge", it) }
                    onMembers?.let { MoreRow("Members", "Invite people, roles, agent access, activity", "more-members", it) }
                    onSpecialties?.let { MoreRow("What agents are good at", "Descriptions and tags used when picking agents for cards", "more-specialties", it) }
                }
            }
            item {
                Section("Account", "${me.displayName} · ${me.hub.name}", "account", "account" in open, { toggle("account") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(me.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(me.hub.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MonoChip(me.role.uppercase())
                    }
                    SectionLabel("Hub")
                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Copy the hub's address") { clipboard.setText(AnnotatedString(hubUrl)); copied = true }
                        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(hubUrl, style = MonoLabel, modifier = Modifier.weight(1f))
                        Text(if (copied) "Copied" else "Copy", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                val on = thisDevice?.let { d -> NotifyKinds.all.count { d.notifyPrefs[it] != false } }
                Section("Notifications", if (thisDevice?.pushEnabled != true) "Not set up on this device" else "$on of ${NotifyKinds.all.size} on, on this device",
                    "notifications", "notifications" in open, { toggle("notifications") }) {
                    if (thisDevice?.pushEnabled != true) Text("Not connected yet. Notifications arrive through the Switchboard push service once it's set up for this hub; until then the app updates live while it's open.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else NotifyKinds.all.forEach { kind ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(notifyLabel(kind), modifier = Modifier.weight(1f))
                            Switch(thisDevice.notifyPrefs[kind] != false, { viewModel.setNotify(kind, it) })
                        }
                    }
                }
            }
            item {
                Section("Signed-in devices", if (s.devices.size == 1) "1 device" else "${s.devices.size} devices", "devices", "devices" in open, { toggle("devices") }) {
                    s.devices.forEach { d ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name + if (d.thisDevice) " (this device)" else "", style = MaterialTheme.typography.bodyMedium)
                                Text("${d.platform} · last used ${d.lastUsedAt?.let { shortTime(it) } ?: "—"}", style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!d.thisDevice) TextButton(onClick = { viewModel.removeDevice(d.id) }) { Text("Sign out") }
                        }
                    }
                }
            }
            item {
                Section("About Switchboard", "What it is, the hub, and the source code", "about", "about" in open, { toggle("about") }) {
                    Para("Switchboard puts your own AI agents in your pocket, and lets you share them safely with your family or team. Chat with them, approve what they want to do, follow their scheduled jobs, and hand them work on a shared board.")
                    Para("Why: your agents run on your own computers, with your own keys and models. Switchboard keeps it that way. There's no Switchboard cloud in the middle, and you decide who may use which agent, with which models and limits.")
                    Para("The hub is the small server this app connects to. You run it yourself, on a home computer, a mini PC or a virtual machine, and reach it privately over Tailscale. It keeps members, permissions and conversations, and talks to your agents for you.")
                    Para("Switchboard is free and open source (Apache License 2.0), made by AppLabs.")
                    Link("Source code on GitHub", GITHUB_URL)
                    Link("Setup guide", "$GITHUB_URL/blob/main/docs/setup-guide.md")
                    Link("Security", "$GITHUB_URL/blob/main/SECURITY.md")
                }
            }
            item {
                Section("Terms of use", "The rules for using Switchboard", "terms", "terms" in open, { toggle("terms") }) {
                    Text("Last updated October 22, 2026", style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                    Bullet("Switchboard is free, open-source software from AppLabs, LLC, licensed under the Apache License 2.0. It's provided \"as is\", without warranties of any kind, as the license describes.")
                    Bullet("You run your own hub and connect your own agents. You're responsible for them, for what they do, and for the AI providers and accounts you use with them, including their costs and terms.")
                    Bullet("Agents can take real actions, such as running commands, changing files or sending messages. Read what you approve, and give people only the access they need.")
                    Bullet("Use Switchboard lawfully. Don't use it to harm others or to reach systems or data you're not allowed to.")
                    Bullet("AppLabs runs the notification service for the official app as a convenience. It may change or end it.")
                    Bullet("These terms can change with new versions of the app; the date above shows the latest.")
                    Link("Read the license", "$GITHUB_URL/blob/main/LICENSE")
                }
            }
            item {
                Section("Privacy policy", "Your data stays on your hub", "privacy", "privacy" in open, { toggle("privacy") }) {
                    Text("Last updated October 22, 2026", style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                    Bullet("Your data stays on your hub. Conversations, members, permissions, agent keys and settings are stored on the hub you run. AppLabs doesn't receive them.")
                    Bullet("No tracking. This app has no analytics, advertising or crash-reporting tools.")
                    Bullet("Notifications: if you allow them, your phone's notification token is registered with AppLabs' notification relay, which delivers through Google's Firebase Cloud Messaging. Your hub sends it only content-free notices, such as \"approval needed\" and ids to open, never message text or names. The relay stores nothing.")
                    Bullet("Sign-in uses passkeys. The private key stays on your device, in its password manager; your hub keeps only the public key.")
                    Bullet("Your agents, and the AI providers they use, receive what you send them, under their own privacy policies.")
                    Bullet("Questions or concerns: open an issue on GitHub, or report security problems privately as described in Security.")
                    Link("Switchboard on GitHub", GITHUB_URL)
                }
            }
            s.error?.let { item { ErrorBanner(it, onRetry = { viewModel.load() }, modifier = Modifier.padding(top = 12.dp)) } }
            item {
                OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("sign-out")) { Text("Sign out") }
            }
            item {
                Text(listOf("Switchboard", appVersion).filter { it.isNotEmpty() }.joinToString(" "), style = MonoLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
            }
        }
    }
}
