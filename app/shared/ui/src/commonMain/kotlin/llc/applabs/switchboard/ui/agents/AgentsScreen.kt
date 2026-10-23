package llc.applabs.switchboard.ui.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import llc.applabs.design.tokens.MonoBody
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import llc.applabs.design.tokens.MonoLabel
import llc.applabs.design.tokens.StatusColors
import llc.applabs.switchboard.domain.model.Agent
import llc.applabs.switchboard.ui.common.EmptyNote
import llc.applabs.switchboard.ui.common.ErrorBanner
import llc.applabs.switchboard.ui.common.MonoChip
import llc.applabs.switchboard.ui.common.SectionLabel
import llc.applabs.switchboard.ui.common.StatusDot
import llc.applabs.switchboard.ui.common.statusColor
import llc.applabs.switchboard.ui.common.statusLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(viewModel: AgentsViewModel, hubName: String, canAddAgents: Boolean, canAddEveryoneCommands: Boolean,
                 onOpenChat: (String) -> Unit, onAddAgent: () -> Unit, onAskAll: () -> Unit = {}) {
    val s by viewModel.state.collectAsState()
    var addingCommand by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.effects.collect { if (it is AgentsUiEffect.OpenChat) onOpenChat(it.sessionId) } }
    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text(hubName, style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(if (s.connected) StatusColors.online() else StatusColors.offline())
                    Text(if (s.connected) "HUB CONNECTED" else "HUB OFFLINE", style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }, actions = { if (canAddAgents) TextButton(onClick = onAddAgent, modifier = Modifier.testTag("add-agent")) { Text("Add agent") } })
    }) { pad ->
        PullToRefreshBox(isRefreshing = s.loading && s.agents.isNotEmpty(), onRefresh = { viewModel.refresh() }, modifier = Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                s.error?.let { item { ErrorBanner(it, onRetry = { viewModel.refresh() }) } }
                if (!s.loading && s.agents.isEmpty()) item {
                    EmptyNote("No agents yet", if (canAddAgents) "Add your first agent: its address and API key." else "Ask the owner to give you access to an agent.")
                }
                if (s.approvals.isNotEmpty()) {
                    item { SectionLabel("Needs you") }
                    items(s.approvals, key = { it.id }) { a ->
                        val agentName = s.agents.firstOrNull { it.id == a.agentId }?.name ?: "agent"
                        Card(Modifier.fillMaxWidth().clickable { onOpenChat(a.sessionId) }, border = BorderStroke(2.dp, StatusColors.busy())) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${agentName.uppercase()} WANTS TO RUN", style = MonoLabel, color = StatusColors.busy())
                                Text(a.command, style = MonoBody, maxLines = 3)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if ("approve_once" in a.allowedDecisions) Button(onClick = { viewModel.decide(a.id, "approve_once") }) { Text("Allow once") }
                                    if ("deny" in a.allowedDecisions) OutlinedButton(onClick = { viewModel.decide(a.id, "deny") }) { Text("Deny") }
                                }
                            }
                        }
                    }
                }
                item { SectionLabel("Agents") }
                items(s.agents, key = { it.id }) { a -> AgentCard(a, starting = s.starting == a.id) { viewModel.startChat(a) } }
                if (s.agents.count { it.myPermissions.chat } >= 2) item {
                    OutlinedButton(onClick = onAskAll, modifier = Modifier.fillMaxWidth().testTag("ask-all")) { Text("Ask all agents…") }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Quick commands", Modifier.weight(1f))
                        TextButton(onClick = { addingCommand = true }) { Text("Add") }
                    }
                }
                if (s.commands.isEmpty()) item {
                    Text("Save prompts you send often, like \"Morning briefing\". Tap one to start a conversation with it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(s.commands.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { c ->
                            OutlinedButton(onClick = { viewModel.runCommand(c) }, modifier = Modifier.weight(1f).testTag("qc-${c.label}")) {
                                Text(c.label + if (c.scope == "everyone") " ·" else "", maxLines = 1)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    if (addingCommand) AddCommandDialog(canAddEveryoneCommands, onAdd = { l, p, h -> addingCommand = false; viewModel.addCommand(l, p, h) },
        onDismiss = { addingCommand = false })
}

@Composable
private fun AddCommandDialog(canEveryone: Boolean, onAdd: (String, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var everyone by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Quick command") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(label, { label = it.take(40) }, label = { Text("Label") }, singleLine = true)
            OutlinedTextField(prompt, { prompt = it }, label = { Text("Prompt") }, minLines = 3)
            if (canEveryone) Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(everyone, { everyone = it }); Text("For every member", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }, confirmButton = { TextButton(onClick = { onAdd(label, prompt, everyone) }, enabled = label.isNotBlank() && prompt.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun AgentCard(a: Agent, starting: Boolean, onClick: () -> Unit) {
    val dim = !a.isAvailable
    Card(
        modifier = Modifier.fillMaxWidth().testTag("agent-${a.name}")
            .clickable(enabled = a.myPermissions.chat && !starting, onClickLabel = "Start a conversation", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${a.name}, ${statusLabel(a.status).lowercase()}, ${a.activity ?: if (a.isAvailable) "idle" else "not answering"}"
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(statusColor(a.status))
                Text(a.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                    color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (starting) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
                else Text(statusLabel(a.status), style = MonoLabel, color = statusColor(a.status))
            }
            Text(a.activity ?: if (a.isAvailable) "Idle" else "Not answering", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoChip(a.type)
                // An agent on the hub's own computer has a loopback address; say so instead.
                a.host?.let { MonoChip(if (it == "localhost" || it.startsWith("127.") || it == "::1" || it == "[::1]") "on the hub" else it) }
                a.currentModel?.let { MonoChip(it) }
                if (a.capabilities.jobs && a.jobCount != null) MonoChip("${a.jobCount} jobs")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAgentScreen(viewModel: AddAgentViewModel, onDone: () -> Unit) {
    val s by viewModel.state.collectAsState()
    LaunchedEffect(s.added) { if (s.added) onDone() }
    Scaffold(topBar = { TopAppBar(title = { Text("Add agent") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Hermes Agent with its API server enabled (API_SERVER_KEY in the profile's .env).", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(s.name, viewModel::name, label = { Text("Name") }, placeholder = { Text("mini-1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(s.address, viewModel::address, label = { Text("Address") }, placeholder = { Text("127.0.0.1:8642 (from the hub)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(s.apiKey, viewModel::apiKey, label = { Text("API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = viewModel::test, enabled = !s.busy && s.address.isNotBlank() && s.apiKey.isNotBlank()) { Text("Test connection") }
                Button(onClick = viewModel::add, enabled = !s.busy && s.test?.ok == true && s.name.isNotBlank()) { Text("Add") }
            }
            s.test?.checks?.forEach { c ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (c.ok) "✓" else "✗", color = if (c.ok) StatusColors.online() else StatusColors.problem(), style = MonoLabel)
                    Column { Text(c.name, style = MonoLabel); c.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                }
            }
            s.error?.let { ErrorBanner(it, onRetry = null) }
        }
    }
}
