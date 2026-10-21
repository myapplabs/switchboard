package llc.applabs.switchboard.ui.askall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import llc.applabs.design.tokens.MonoLabel
import llc.applabs.design.tokens.StatusColors
import llc.applabs.switchboard.domain.AgentsRepository
import llc.applabs.switchboard.domain.ChatRepository
import llc.applabs.switchboard.domain.ControlRepository
import llc.applabs.switchboard.domain.model.Agent
import llc.applabs.switchboard.domain.model.RunEvent
import llc.applabs.switchboard.ui.common.ErrorBanner
import llc.applabs.switchboard.ui.common.MarkdownText
import llc.applabs.switchboard.ui.common.StatusDot
import llc.applabs.switchboard.ui.common.friendly
import llc.applabs.switchboard.ui.common.statusColor

/** One agent's answer as it streams. */
data class AskCard(
    val agentId: String, val agentName: String, val sessionId: String? = null, val text: String = "",
    val tools: Int = 0, val status: String = "starting", val seconds: Double? = null, val problem: String? = null,
)

data class AskAllUiState(
    val agents: List<Agent> = emptyList(),
    val selected: Set<String> = emptySet(),
    val prompt: String = "",
    val cards: List<AskCard> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
)

/** Ask all agents (spec F15): one prompt to several agents, each answer streaming in its own card. */
class AskAllViewModel(private val agents: AgentsRepository, private val chat: ChatRepository, private val control: ControlRepository) : ViewModel() {
    private val _state = MutableStateFlow(AskAllUiState())
    val state: StateFlow<AskAllUiState> = _state

    init {
        viewModelScope.launch {
            runCatching { if (agents.agents.value.isEmpty()) agents.refresh() }
            val usable = agents.agents.value.filter { it.myPermissions.chat }
            _state.update { s -> s.copy(agents = usable, selected = usable.filter { it.isAvailable }.map { it.id }.toSet()) }
        }
    }

    fun toggle(id: String) = _state.update { s -> s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id) }
    fun prompt(p: String) = _state.update { it.copy(prompt = p) }

    fun send() = viewModelScope.launch {
        val s = _state.value
        if (s.selected.size < 2 || s.prompt.isBlank() || s.sending) return@launch
        _state.update { it.copy(sending = true, error = null, cards = emptyList()) }
        try {
            val started = TimeSource.Monotonic.markNow()
            val items = control.askAll(s.selected.toList(), s.prompt.trim())
            val name = { id: String -> s.agents.firstOrNull { it.id == id }?.name ?: "agent" }
            _state.update { st -> st.copy(cards = items.map { AskCard(it.agentId, name(it.agentId), it.sessionId,
                status = if (it.problem != null) "failed" else "working", problem = it.problem?.let { p -> p.detail ?: p.title.ifEmpty { p.code } }) }) }
            items.filter { it.runId != null }.forEach { item -> launch { follow(item.agentId, item.runId!!, started) } }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.friendly()) }
        } finally {
            _state.update { it.copy(sending = false) }
        }
    }

    private suspend fun follow(agentId: String, runId: String, started: TimeSource.Monotonic.ValueTimeMark) {
        fun edit(f: (AskCard) -> AskCard) = _state.update { s -> s.copy(cards = s.cards.map { if (it.agentId == agentId) f(it) else it }) }
        try {
            chat.events(runId).collect { e ->
                when (e) {
                    is RunEvent.Delta -> edit { it.copy(text = it.text + e.text) }
                    is RunEvent.ToolStarted -> edit { it.copy(tools = it.tools + 1) }
                    is RunEvent.Completed -> edit { it.copy(status = "done", text = it.text.ifEmpty { e.output }, seconds = started.elapsedNow().inWholeMilliseconds / 1000.0) }
                    is RunEvent.Failed -> edit { it.copy(status = "failed", problem = e.error) }
                    is RunEvent.Cancelled -> edit { it.copy(status = "stopped") }
                    is RunEvent.ApprovalRequested, is RunEvent.ClarifyRequested -> edit { it.copy(status = "needs you") }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            edit { it.copy(status = "failed", problem = e.friendly()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AskAllScreen(viewModel: AskAllViewModel, onOpenChat: (String) -> Unit, onBack: () -> Unit) {
    val s by viewModel.state.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Ask all agents") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.agents.forEach { a ->
                        FilterChip(selected = a.id in s.selected, onClick = { viewModel.toggle(a.id) }, enabled = a.isAvailable,
                            label = { Text(a.name) }, leadingIcon = { StatusDot(statusColor(a.status)) },
                            modifier = Modifier.semantics { contentDescription = "${a.name}, ${a.status}, ${if (a.id in s.selected) "selected" else "not selected"}" })
                    }
                }
            }
            item {
                OutlinedTextField(s.prompt, viewModel::prompt, label = { Text("Ask them all") }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("ask-prompt"))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { viewModel.send() }, enabled = s.selected.size >= 2 && s.prompt.isNotBlank() && !s.sending, modifier = Modifier.testTag("ask-send")) {
                        Text("Ask ${s.selected.size} agents")
                    }
                    if (s.selected.size < 2) Text("Pick at least two.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            s.error?.let { item { ErrorBanner(it, onRetry = null) } }
            items(s.cards, key = { it.agentId }) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusDot(when (c.status) { "done" -> StatusColors.online(); "failed" -> StatusColors.problem(); "stopped" -> StatusColors.offline(); else -> StatusColors.busy() })
                            Text(c.agentName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(listOfNotNull(c.seconds?.let { "${(it * 10).toInt() / 10.0}s" }, "${c.tools} tools".takeIf { c.tools > 0 }, c.status.uppercase())
                                .joinToString(" · "), style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        c.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        if (c.text.isNotEmpty()) MarkdownText(c.text)
                        c.sessionId?.let { id -> TextButton(onClick = { onOpenChat(id) }) { Text("Continue in chat") } }
                    }
                }
            }
        }
    }
}
