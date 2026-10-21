package llc.applabs.switchboard.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import llc.applabs.switchboard.domain.AgentsRepository
import llc.applabs.switchboard.domain.ChatRepository
import llc.applabs.switchboard.domain.ControlRepository
import llc.applabs.switchboard.domain.LiveEvents
import llc.applabs.switchboard.domain.model.Agent
import llc.applabs.switchboard.domain.model.ChatSession
import llc.applabs.switchboard.domain.model.Clarification
import llc.applabs.switchboard.domain.model.Message
import llc.applabs.switchboard.domain.model.ModelOption
import llc.applabs.switchboard.domain.model.RunEvent
import llc.applabs.switchboard.domain.model.ShareTarget
import llc.applabs.switchboard.domain.model.ToolCall
import llc.applabs.switchboard.domain.model.UserEvent
import llc.applabs.switchboard.ui.common.friendly

/** A message as the chat shows it; assistant messages fill in while their run streams. */
data class ChatMessage(
    val id: String,
    val role: String,
    val kind: String = "message",
    val text: String = "",
    val authorUserId: String? = null,
    val status: String? = null,
    val runId: String? = null,
    val tools: List<ToolCall> = emptyList(),
    val questions: List<Question> = emptyList(),
    val approvals: List<ApprovalCard> = emptyList(),
    val modelLabel: String? = null,
)

data class Question(val clarification: Clarification, val canAnswer: Boolean)

/** An approval request in the chat; [decision] once decided. */
data class ApprovalCard(val id: String, val command: String, val description: String?, val allowed: List<String>,
                        val canDecide: Boolean, val decision: String? = null, val decidedBy: String? = null)

data class ChatUiState(
    val session: ChatSession? = null,
    val agent: Agent? = null,
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val runId: String? = null,
    val runStatus: String? = null,
    val models: List<ModelOption> = emptyList(),
    val loading: Boolean = true,
    val sending: Boolean = false,
    val error: String? = null,
    val deleted: Boolean = false,
) {
    val running get() = runId != null
    val canSteer get() = running && agent?.capabilities?.steer == true
}

sealed interface ChatUiEffect { data class Toast(val text: String) : ChatUiEffect }

class ChatViewModel(
    private val sessionId: String,
    private val myUserId: String,
    private val chat: ChatRepository,
    private val agents: AgentsRepository,
    private val live: LiveEvents,
    private val control: ControlRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private val _effects = Channel<ChatUiEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()
    private var stream: Job? = null

    init {
        load()
        viewModelScope.launch {
            live.events.collect { e ->
                when (e) {
                    // Someone else's turn in a shared conversation, or a run started elsewhere.
                    is UserEvent.SessionUpdated -> if (e.sessionId == sessionId && !_state.value.running) reloadAndAttach()
                    is UserEvent.SessionRemoved -> if (e.sessionId == sessionId) _state.update { it.copy(deleted = true) }
                    else -> Unit
                }
            }
        }
    }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try { reloadAndAttach() } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
        _state.update { it.copy(loading = false) }
    }

    private suspend fun reloadAndAttach() {
        val session = chat.session(sessionId)
        val agent = agents.agents.value.firstOrNull { it.id == session.agentId } ?: runCatching { agents.refresh() }
            .let { agents.agents.value.firstOrNull { it.id == session.agentId } }
        val messages = chat.messages(sessionId).map(::toUi)
        _state.update { it.copy(session = session, agent = agent, messages = messages) }
        if (_state.value.models.isEmpty() && agent?.capabilities?.modelSwitching == true)
            runCatching { agents.models(agent.id) }.onSuccess { m -> _state.update { it.copy(models = m) } }
        // A run in progress: replay its events from the start into a fresh copy of its reply.
        session.activeRunId?.let { runId ->
            if (_state.value.runId == runId) return
            _state.update { s -> s.copy(messages = s.messages.map { if (it.runId == runId && it.role == "assistant") it.copy(text = "", tools = emptyList(), questions = emptyList()) else it }) }
            attach(runId)
        }
    }

    fun onInput(text: String) = _state.update { it.copy(input = text) }

    fun send() {
        val s = _state.value
        val text = s.input.trim()
        if (text.isEmpty() || s.sending) return
        if (s.running) { if (s.canSteer) steer(text); return }
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            try {
                val run = chat.send(sessionId, text)
                _state.update { st ->
                    st.copy(input = "", messages = st.messages + ChatMessage("local-${run.id}", "user", text = text, authorUserId = myUserId) +
                        ChatMessage(run.messageId, "assistant", status = "partial", runId = run.id, modelLabel = run.modelLabel))
                }
                attach(run.id)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.friendly()) }
            } finally {
                _state.update { it.copy(sending = false) }
            }
        }
    }

    private fun steer(text: String) = viewModelScope.launch {
        val runId = _state.value.runId ?: return@launch
        try { chat.steer(runId, text); _state.update { it.copy(input = "") } }
        catch (e: Exception) { _effects.send(ChatUiEffect.Toast(e.friendly())) }
    }

    private fun attach(runId: String) {
        stream?.cancel()
        _state.update { it.copy(runId = runId, runStatus = "running") }
        stream = viewModelScope.launch {
            try {
                chat.events(runId).collect { e -> apply(runId, e) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.friendly()) }
            } finally {
                _state.update { if (it.runId == runId) it.copy(runId = null, runStatus = null) else it }
            }
        }
    }

    /** One run event → the chat. */
    private fun apply(runId: String, e: RunEvent) = _state.update { s ->
        fun reply(edit: (ChatMessage) -> ChatMessage): List<ChatMessage> {
            val i = s.messages.indexOfLast { it.runId == runId && it.role == "assistant" }
            return if (i < 0) s.messages + edit(ChatMessage("run-$runId", "assistant", runId = runId, status = "partial"))
            else s.messages.toMutableList().also { it[i] = edit(it[i]) }
        }
        when (e) {
            is RunEvent.Started -> s.copy(messages = reply { it.copy(modelLabel = e.modelLabel ?: it.modelLabel) })
            is RunEvent.Delta -> s.copy(messages = reply { it.copy(text = it.text + e.text) })
            is RunEvent.ToolStarted -> s.copy(messages = reply { m -> m.copy(tools = m.tools.filterNot { it.id == e.id } + ToolCall(e.id, e.tool, e.preview, "running")) })
            is RunEvent.ToolCompleted -> s.copy(messages = reply { m ->
                m.copy(tools = m.tools.map { if (it.id == e.id) it.copy(state = e.state, durationS = e.durationS, outputExcerpt = e.excerpt ?: it.outputExcerpt) else it })
            })
            is RunEvent.ClarifyRequested -> s.copy(messages = reply { m ->
                m.copy(questions = m.questions.filterNot { it.clarification.id == e.clarification.id } + Question(e.clarification, e.canAnswer))
            })
            is RunEvent.ClarifyAnswered -> s.copy(messages = reply { m ->
                m.copy(questions = m.questions.map { q ->
                    if (q.clarification.id == e.clarificationId) q.copy(clarification = q.clarification.copy(status = e.outcome, answer = e.answer)) else q })
            })
            is RunEvent.ApprovalRequested -> s.copy(messages = reply { m ->
                m.copy(approvals = m.approvals.filterNot { it.id == e.approvalId } +
                    ApprovalCard(e.approvalId, e.command, e.description, e.allowedDecisions, e.canDecide))
            })
            is RunEvent.ApprovalDecided -> s.copy(messages = reply { m ->
                m.copy(approvals = m.approvals.map { if (it.id == e.approvalId) it.copy(decision = e.decision, decidedBy = e.decidedBy) else it })
            })
            is RunEvent.SteerAccepted -> s.copy(messages = s.messages + ChatMessage("steer-${e.seq}", "user", kind = "steer", text = e.text, authorUserId = e.by))
            is RunEvent.Status -> s.copy(runStatus = e.status)
            is RunEvent.Completed -> s.copy(messages = reply { it.copy(status = "complete", text = it.text.ifEmpty { e.output }) })
            is RunEvent.Failed -> s.copy(messages = reply { it.copy(status = "failed") }, error = e.error)
            is RunEvent.Cancelled -> s.copy(messages = reply { it.copy(status = "cancelled") })
            is RunEvent.Reasoning, is RunEvent.Other -> s
        }
    }

    fun stop() = viewModelScope.launch {
        val runId = _state.value.runId ?: return@launch
        _state.update { it.copy(runStatus = "stopping") }
        try { chat.stop(runId) } catch (e: Exception) { _effects.send(ChatUiEffect.Toast(e.friendly())) }
    }

    fun answer(clarificationId: String, answer: String) = viewModelScope.launch {
        val runId = _state.value.runId ?: return@launch
        try { chat.answer(runId, clarificationId, JsonPrimitive(answer)) } catch (e: Exception) { _effects.send(ChatUiEffect.Toast(e.friendly())) }
    }

    fun decide(approvalId: String, decision: String) = viewModelScope.launch {
        try { control.decide(approvalId, decision) } catch (e: Exception) { _effects.send(ChatUiEffect.Toast(e.friendly())) }
    }

    fun rename(title: String) = act { _state.update { it.copy(session = chat.rename(sessionId, title.trim())) } }
    fun switchModel(modelId: String) = act { _state.update { it.copy(session = chat.switchModel(sessionId, modelId)) } }
    fun toggleShareWithEveryone() = act {
        val shared = _state.value.session?.sharedWith?.everyone == true
        _state.update { it.copy(session = chat.share(sessionId, ShareTarget(everyone = !shared))) }
        _effects.send(ChatUiEffect.Toast(if (shared) "No longer shared" else "Shared with everyone who can use this agent"))
    }
    fun delete() = act { chat.delete(sessionId); _state.update { it.copy(deleted = true) } }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: Exception) { _effects.send(ChatUiEffect.Toast(e.friendly())) }
    }

    companion object {
        fun toUi(m: Message) = ChatMessage(
            id = m.id, role = m.role, kind = m.kind, text = m.text, authorUserId = m.authorUserId, status = m.status, runId = m.runId,
            tools = m.toolCalls, questions = m.clarifications.map { Question(it, canAnswer = it.status == "pending") }, modelLabel = m.modelLabel,
        )

        /** An answer as text (lists joined). */
        fun answerText(a: JsonElement?): String = when (a) {
            null -> ""
            is JsonArray -> a.joinToString(", ") { it.jsonPrimitive.content }
            else -> runCatching { a.jsonPrimitive.content }.getOrDefault(a.toString())
        }
    }
}
