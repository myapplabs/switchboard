package llc.applabs.switchboard.ui.settings

import androidx.compose.foundation.clickable
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
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun notifyLabel(kind: String) = when (kind) {
    NotifyKinds.APPROVAL -> "Approvals waiting for me"
    NotifyKinds.QUESTION -> "Questions from agents"
    NotifyKinds.RUN_FINISHED -> "Long answers finished"
    NotifyKinds.JOB_FAILED -> "Failed scheduled jobs"
    NotifyKinds.MEMBER_JOINED -> "New members"
    NotifyKinds.TASK_NEEDS_YOU -> "Cards that need me"
    NotifyKinds.TASK_REVIEW -> "Cards ready for review"
    else -> kind
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, me: Me, hubUrl: String, onMembers: (() -> Unit)? = null, onSpecialties: (() -> Unit)? = null,
                   onForge: (() -> Unit)? = null) {
    val s by viewModel.state.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("More") }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onMembers != null || onSpecialties != null || onForge != null) {
                item { SectionLabel("Manage") }
                onForge?.let { item { MoreRow("FORGE", "Your FORGE tickets on the board; decide FORGE's gates here", "more-forge", it) } }
                onMembers?.let { item { MoreRow("Members", "Invite people, roles, agent access, activity", "more-members", it) } }
                onSpecialties?.let { item { MoreRow("What agents are good at", "Descriptions and tags used when picking agents for cards", "more-specialties", it) } }
            }
            item { SectionLabel("Account") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(me.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(me.hub.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MonoChip(me.role.uppercase())
                }
            }
            item { SectionLabel("Hub") }
            item { Text(hubUrl, style = MonoLabel) }
            item { SectionLabel("Notifications on this device") }
            val me = s.devices.firstOrNull { it.thisDevice }
            if (me?.pushEnabled != true) item {
                Text("Not connected yet. Notifications arrive through the Switchboard push service once it's set up for this hub; until then the app updates live while it's open.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else items(NotifyKinds.all, key = { it }) { kind ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(notifyLabel(kind), modifier = Modifier.weight(1f))
                    Switch(me.notifyPrefs[kind] != false, { viewModel.setNotify(kind, it) })
                }
            }
            item { SectionLabel("Signed-in devices") }
            items(s.devices, key = { it.id }) { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name + if (d.thisDevice) " (this device)" else "", style = MaterialTheme.typography.bodyMedium)
                        Text("${d.platform} · ${d.lastUsedAt?.let { shortTime(it) } ?: "—"}", style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!d.thisDevice) TextButton(onClick = { viewModel.removeDevice(d.id) }) { Text("Sign out") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            s.error?.let { item { ErrorBanner(it, onRetry = { viewModel.load() }) } }
            item {
                OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("sign-out")) { Text("Sign out") }
            }
            item { Text("Switchboard 0.1.0", style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
