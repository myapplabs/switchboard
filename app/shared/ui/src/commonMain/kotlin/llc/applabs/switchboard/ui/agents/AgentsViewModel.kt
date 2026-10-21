package llc.applabs.switchboard.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import llc.applabs.switchboard.domain.AgentsRepository
import llc.applabs.switchboard.domain.ChatRepository
import llc.applabs.switchboard.domain.ControlRepository
import llc.applabs.switchboard.domain.model.Approval
import llc.applabs.switchboard.domain.model.QuickCommand
import llc.applabs.switchboard.domain.model.UserEvent
import llc.applabs.switchboard.domain.LiveEvents
import llc.applabs.switchboard.domain.model.Agent
import llc.applabs.switchboard.domain.model.AgentInput
import llc.applabs.switchboard.domain.model.AgentStatus
import llc.applabs.switchboard.domain.model.ConnectionTest
import llc.applabs.switchboard.domain.model.ProfileInput
import llc.applabs.switchboard.ui.common.friendly

data class AgentsUiState(
    val agents: List<Agent> = emptyList(),
    /** Approvals waiting for this person (spec: approval routing). */
    val approvals: List<Approval> = emptyList(),
    val commands: List<QuickCommand> = emptyList(),
    val connected: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val starting: String? = null,
)

sealed interface AgentsUiEffect {
    data class OpenChat(val sessionId: String) : AgentsUiEffect
}

/** The home: every agent the person may use, live. Offline ones sink to the bottom (spec: Agents). */
class AgentsViewModel(private val agents: AgentsRepository, private val chat: ChatRepository, live: LiveEvents,
                      private val control: ControlRepository) : ViewModel() {
    private val local = MutableStateFlow(AgentsUiState())
    val state: StateFlow<AgentsUiState> = combine(local, agents.agents, live.connected) { s, list, connected ->
        s.copy(agents = list.sortedWith(compareBy<Agent> { !it.isAvailable }.thenBy { it.name.lowercase() }), connected = connected)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AgentsUiState())
    private val _effects = Channel<AgentsUiEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        refresh()
        viewModelScope.launch {
            live.events.collect { e ->
                if (e is UserEvent.ApprovalRequested || e is UserEvent.ApprovalDecided || e is UserEvent.Resync) loadApprovals()
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        local.update { it.copy(loading = true, error = null) }
        try {
            agents.refresh()
            local.update { it.copy(commands = runCatching { control.quickCommands() }.getOrDefault(it.commands)) }
            loadApprovals()
        } catch (e: Exception) { local.update { it.copy(error = e.friendly()) } }
        local.update { it.copy(loading = false) }
    }

    private suspend fun loadApprovals() {
        runCatching { control.approvals() }.onSuccess { list -> local.update { it.copy(approvals = list.filter { a -> a.allowedDecisions.isNotEmpty() }) } }
    }

    fun decide(approvalId: String, decision: String) = viewModelScope.launch {
        try { control.decide(approvalId, decision); loadApprovals() } catch (e: Exception) { local.update { it.copy(error = e.friendly()) } }
    }

    /** A quick command: a new conversation with its agent (or the first available one) and its prompt sent. */
    fun runCommand(c: QuickCommand) = viewModelScope.launch {
        val agent = c.agentId?.let { id -> state.value.agents.firstOrNull { it.id == id } } ?: state.value.agents.firstOrNull { it.isAvailable && it.myPermissions.chat }
        if (agent == null) { local.update { it.copy(error = "No agent is available for \"${c.label}\".") }; return@launch }
        local.update { it.copy(starting = agent.id, error = null) }
        try {
            val session = chat.create(agent.id)
            chat.send(session.id, c.prompt)
            _effects.send(AgentsUiEffect.OpenChat(session.id))
        } catch (e: Exception) { local.update { it.copy(error = e.friendly()) } }
        finally { local.update { it.copy(starting = null) } }
    }

    fun addCommand(label: String, prompt: String, everyone: Boolean) = viewModelScope.launch {
        try { control.addQuickCommand(label, prompt, null, everyone); local.update { it.copy(commands = control.quickCommands()) } }
        catch (e: Exception) { local.update { it.copy(error = e.friendly()) } }
    }

    fun deleteCommand(id: String) = viewModelScope.launch {
        try { control.deleteQuickCommand(id); local.update { it.copy(commands = control.quickCommands()) } }
        catch (e: Exception) { local.update { it.copy(error = e.friendly()) } }
    }

    /** Tap an agent: a new conversation with it. */
    fun startChat(agent: Agent) = viewModelScope.launch {
        if (local.value.starting != null) return@launch
        local.update { it.copy(starting = agent.id, error = null) }
        try {
            _effects.send(AgentsUiEffect.OpenChat(chat.create(agent.id).id))
        } catch (e: Exception) {
            local.update { it.copy(error = e.friendly()) }
        } finally {
            local.update { it.copy(starting = null) }
        }
    }
}

data class AddAgentUiState(
    val name: String = "",
    val address: String = "",
    val apiKey: String = "",
    val busy: Boolean = false,
    val test: ConnectionTest? = null,
    val error: String? = null,
    val added: Boolean = false,
)

/** The owner adds an agent: a live connection test with a reason per check (spec F2). */
class AddAgentViewModel(private val agents: AgentsRepository) : ViewModel() {
    private val _state = MutableStateFlow(AddAgentUiState())
    val state: StateFlow<AddAgentUiState> = _state

    fun name(v: String) = _state.update { it.copy(name = v, test = null, error = null) }
    fun address(v: String) = _state.update { it.copy(address = v, test = null, error = null) }
    fun apiKey(v: String) = _state.update { it.copy(apiKey = v, test = null, error = null) }

    private fun input() = _state.value.let { s ->
        AgentInput(name = s.name.trim(), baseUrl = s.address.trim().let { if (it.startsWith("http")) it else "http://$it" },
            profiles = listOf(ProfileInput(name = "main", apiKey = s.apiKey.trim())))
    }

    fun test() = act { _state.update { it.copy(test = agents.test(input())) } }
    fun add() = act { agents.add(input()); _state.update { it.copy(added = true) } }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        try { block() } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
        _state.update { it.copy(busy = false) }
    }

    companion object { val HINT_STATUS = AgentStatus.ONLINE }
}
