package llc.applabs.switchboard.ui.members

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import llc.applabs.design.tokens.MonoBody
import llc.applabs.design.tokens.MonoLabel
import llc.applabs.switchboard.domain.model.Me
import llc.applabs.switchboard.domain.model.Member
import llc.applabs.switchboard.domain.model.Roles
import llc.applabs.switchboard.ui.common.ErrorBanner
import llc.applabs.switchboard.ui.common.MonoChip
import llc.applabs.switchboard.ui.common.QrCode
import llc.applabs.switchboard.ui.common.SectionLabel
import llc.applabs.switchboard.ui.common.shortTime

private val roleHint = mapOf(
    Roles.ADMIN to "Manages members and approvals; chats with every agent",
    Roles.MEMBER to "Chats with the agents you grant",
    Roles.RESTRICTED to "Limited access: only granted agents, on a safe profile",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen(viewModel: MembersViewModel, me: Me, onAgentAccess: () -> Unit, onActivity: () -> Unit, onBack: (() -> Unit)? = null) {
    val s by viewModel.state.collectAsState()
    var inviting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Member?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("Members") }, navigationIcon = { onBack?.let { TextButton(onClick = it) { Text("Back") } } },
        actions = { TextButton(onClick = { inviting = true }) { Text("Invite") } }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            s.error?.let { item { ErrorBanner(it, onRetry = { viewModel.load() }) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    if (me.isOwner) TextButton(onClick = onAgentAccess) { Text("Agent access") }
                    TextButton(onClick = onActivity) { Text("Activity") }
                }
            }
            item { SectionLabel("Members") }
            items(s.members, key = { it.userId }) { m ->
                Row(Modifier.fillMaxWidth().clickable(enabled = m.role != Roles.OWNER && m.userId != me.userId) { editing = m }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.displayName + if (m.userId == me.userId) " (you)" else "", style = MaterialTheme.typography.titleSmall)
                        Text(m.lastActiveAt?.let { shortTime(it).let { t -> when { t == "now" -> "Active now"; t.contains('-') -> "Active $t"; else -> "Active $t ago" } } } ?: "Not active yet",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MonoChip(m.role.uppercase(), color = if (m.role == Roles.OWNER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (s.invites.isNotEmpty()) {
                item { SectionLabel("Pending invites") }
                items(s.invites, key = { it.id }) { inv ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${inv.role} invite" + (inv.note?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                            Text("Expires ${inv.expiresAt.take(16).replace('T', ' ')} UTC", style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { viewModel.revoke(inv.id) }) { Text("Revoke") }
                    }
                }
            }
        }
    }

    if (inviting) {
        var role by remember { mutableStateOf(Roles.MEMBER) }
        var note by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { inviting = false }, title = { Text("Invite someone") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (me.isOwner) listOf(Roles.ADMIN, Roles.MEMBER, Roles.RESTRICTED) else listOf(Roles.MEMBER, Roles.RESTRICTED)).forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { role = r }, verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = role == r, onClick = { role = r }, label = { Text(r) })
                        Text(roleHint[r].orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, placeholder = { Text("e.g. for Sam") }, singleLine = true)
            }
        }, confirmButton = { Button(onClick = { inviting = false; viewModel.invite(role, note) }) { Text("Create invite") } },
            dismissButton = { TextButton(onClick = { inviting = false }) { Text("Cancel") } })
    }

    s.created?.let { inv ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(onDismissRequest = viewModel::dismissCreated, title = { Text("Invite ready") }, text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                inv.url?.let { QrCode(it, Modifier.size(220.dp)) }
                Text("Scan with the Switchboard app, or share the link. Single use, valid 24 hours.", style = MaterialTheme.typography.bodySmall)
                inv.code?.let { Text(it, style = MonoBody, color = MaterialTheme.colorScheme.primary) }
            }
        }, confirmButton = {
            TextButton(onClick = { inv.url?.let { clipboard.setText(AnnotatedString(it)) } }) { Text("Copy link") }
        }, dismissButton = { TextButton(onClick = viewModel::dismissCreated) { Text("Done") } })
    }

    editing?.let { m ->
        var confirmRemove by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(m.displayName) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirmRemove) Text("Remove ${m.displayName}? They are signed out on every device and their private conversations are deleted.")
                else (if (me.isOwner) listOf(Roles.ADMIN, Roles.MEMBER, Roles.RESTRICTED) else listOf(Roles.MEMBER, Roles.RESTRICTED)).forEach { r ->
                    FilterChip(selected = m.role == r, onClick = { editing = null; viewModel.setRole(m.userId, r) }, label = { Text(r) })
                }
            }
        }, confirmButton = {
            if (confirmRemove) TextButton(onClick = { editing = null; viewModel.remove(m.userId) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            else TextButton(onClick = { confirmRemove = true }) { Text("Remove member", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Close") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentAccessScreen(viewModel: AgentAccessViewModel, onBack: () -> Unit) {
    val s by viewModel.state.collectAsState()
    val agent = s.agents.firstOrNull { it.id == s.agentId }
    Scaffold(topBar = { TopAppBar(title = { Text("Agent access") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.agents.forEach { a -> FilterChip(selected = a.id == s.agentId, onClick = { viewModel.select(a.id) }, label = { Text(a.name) }) }
                }
            }
            item { Text("You always have access, on the first profile. Others: deny unless granted here.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Before you share an agent", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.semantics { heading() })
                        Text("People you allow here can ask the agent to use its tools: for example run commands or read files on the computer it " +
                            "runs on. Switchboard controls who may use which agent and profile, not what the agent itself can reach. Share a powerful " +
                            "profile only with people you'd trust with that computer, or give them a sandboxed profile that only sees what they need.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("How: github.com/myapplabs/switchboard/blob/main/SECURITY.md", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            items(s.rows, key = { it.role }) { row ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.role.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                                Text(roleHint[row.role].orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(row.canChat, { viewModel.edit(row.copy(canChat = it)) })
                        }
                        if (row.canChat) {
                            Text("They can use this agent's tools through the profile below.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            SectionLabel("Profile (tool level)")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val profiles = agent?.profiles.orEmpty()
                                profiles.forEach { p ->
                                    FilterChip(selected = (row.profileId ?: profiles.first().id) == p.id, onClick = { viewModel.edit(row.copy(profileId = p.id)) },
                                        label = { Text(p.name) })
                                }
                            }
                            OutlinedTextField(row.dailyLimit, { v -> viewModel.edit(row.copy(dailyLimit = v.filter { it.isDigit() })) },
                                label = { Text("Messages per day (empty = no limit)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(280.dp))
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::save, enabled = !s.saving && s.agentId != null) { Text("Save") }
                    if (s.saved) Text("Saved", style = MonoLabel, color = MaterialTheme.colorScheme.primary)
                }
            }
            s.error?.let { item { ErrorBanner(it, onRetry = null) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(viewModel: ActivityViewModel, onBack: () -> Unit) {
    val s by viewModel.state.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Activity") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            s.error?.let { item { ErrorBanner(it, onRetry = { viewModel.load() }) } }
            item { Text("Who used which agent and model, and changes to members. Never the content of conversations.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
            items(s.items, key = { it.id }) { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(shortTime(a.at), style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.action.replace('_', ' '), style = MaterialTheme.typography.bodyMedium)
                        Text(listOfNotNull(a.userId?.let { s.names[it] ?: "someone" }, a.agentId?.let { s.names[it] ?: "an agent" }, a.modelLabel)
                            .joinToString(" · "), style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
