package llc.applabs.switchboard.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import llc.applabs.switchboard.domain.AgentsRepository
import llc.applabs.switchboard.domain.TasksRepository
import llc.applabs.switchboard.domain.model.Agent
import llc.applabs.switchboard.domain.model.Columns
import llc.applabs.switchboard.domain.model.Task
import llc.applabs.switchboard.domain.model.TaskInput
import llc.applabs.switchboard.domain.model.TaskStepInput
import llc.applabs.switchboard.ui.common.friendly

// ── board ───────────────────────────────────────────────────────────────────

data class BoardUiState(
    val tasks: List<Task> = emptyList(),
    /** FORGE tickets, when this person's FORGE is connected. */
    val forge: llc.applabs.switchboard.domain.model.ForgeBoard = llc.applabs.switchboard.domain.model.ForgeBoard(false),
    /** Shown above the board when a FORGE connection exists but isn't answering. */
    val forgeNote: String? = null,
    val agents: List<Agent> = emptyList(),
    /** The column shown; null = all, grouped. */
    val column: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    /** Sub-cards show inside their coordinated card, not on the board. */
    private val top: List<Task> get() = tasks.filter { it.parentId == null }
    fun count(c: String) = top.count { it.column == c } + forge.cards.count { it.column == c }
    val openCount: Int get() = top.count { it.column != Columns.DONE } + forge.cards.count { it.column != Columns.DONE }
    val shownForge: List<llc.applabs.switchboard.domain.model.ForgeCard>
        get() = if (column == null) forge.cards.filter { it.column != Columns.DONE } else forge.cards.filter { it.column == column }
    val shown: List<Task> get() = if (column == null) top.filter { it.column != Columns.DONE } else top.filter { it.column == column }
}

class BoardViewModel(private val tasks: TasksRepository, private val agents: AgentsRepository,
                     private val forge: llc.applabs.switchboard.domain.ForgeRepository? = null) : ViewModel() {
    private val _state = MutableStateFlow(BoardUiState())
    val state: StateFlow<BoardUiState> = _state

    init {
        forge?.let { f ->
            viewModelScope.launch { f.board.collect { b -> _state.update { it.copy(forge = b) } } }
            // FORGE is read again every 30 s while the board is open (the hub caches it briefly).
            viewModelScope.launch {
                var hasLinks: Boolean? = null
                while (true) {
                    runCatching { f.refresh() }
                    if (hasLinks == null) hasLinks = runCatching { f.bridges().isNotEmpty() }.getOrDefault(false)
                    val b = f.board.value
                    _state.update { it.copy(forgeNote = when {
                        b.connected && b.problem != null -> "FORGE: ${b.problem}"
                        !b.connected && hasLinks == true -> "FORGE isn't connected. Run forge switchboard connect where FORGE runs."
                        else -> null
                    }) }
                    kotlinx.coroutines.delay(30_000)
                }
            }
        }
        viewModelScope.launch {
            combine(tasks.tasks, agents.agents) { t, a -> t to a }.collect { (t, a) -> _state.update { it.copy(tasks = t, agents = a) } }
        }
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        try {
            if (agents.agents.value.isEmpty()) runCatching { agents.refresh() }
            tasks.refresh()
            _state.update { s ->
                // Open on the column that needs attention first.
                val start = s.column ?: listOf(Columns.NEEDS_YOU, Columns.REVIEW).firstOrNull { c -> s.count(c) > 0 }
                s.copy(loading = false, error = null, column = start)
            }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.friendly()) }
        }
    }

    fun show(column: String?) = _state.update { it.copy(column = column) }
}

// ── card ────────────────────────────────────────────────────────────────────

data class TaskUiState(
    val task: Task? = null,
    /** All cards (for sub-cards and the parent card). */
    val all: List<Task> = emptyList(),
    val agents: List<Agent> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val deleted: Boolean = false,
)

class TaskViewModel(private val id: String, private val tasks: TasksRepository, agents: AgentsRepository) : ViewModel() {
    private val _state = MutableStateFlow(TaskUiState())
    val state: StateFlow<TaskUiState> = _state

    init {
        viewModelScope.launch {
            combine(tasks.tasks, agents.agents) { t, a -> t to a }.collect { (all, a) ->
                _state.update { s -> s.copy(task = all.firstOrNull { it.id == id } ?: s.task, all = all, agents = a) }
            }
        }
        viewModelScope.launch {
            try { _state.update { it.copy(task = tasks.task(id)) } } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
        }
    }

    fun start() = act { tasks.move(id, Columns.READY) }
    fun toBacklog() = act { tasks.move(id, Columns.BACKLOG) }
    fun accept() = act { tasks.review(id, "accept") }
    fun sendBack(note: String, step: Int?) = act { tasks.review(id, "send_back", note.trim(), step) }
    fun close() = act { tasks.review(id, "close") }
    fun delete() = viewModelScope.launch {
        try { tasks.delete(id); _state.update { it.copy(deleted = true) } } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
    }

    private fun act(block: suspend () -> Task) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.update { it.copy(busy = true, error = null) }
        try { val t = block(); _state.update { it.copy(task = t) } } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
        finally { _state.update { it.copy(busy = false) } }
    }
}

// ── new card ────────────────────────────────────────────────────────────────

/** One step being written: an agent, or "any agent tagged". */
data class StepDraft(val agentId: String? = null, val tag: String? = null)

data class NewTaskUiState(
    val agents: List<Agent> = emptyList(),
    val title: String = "",
    val instructions: String = "",
    val steps: List<StepDraft> = listOf(StepDraft()),
    val reviewEachStep: Boolean = false,
    val startNow: Boolean = true,
    /** Let the hub's coordinator plan it instead of picking steps. */
    val coordinated: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val created: String? = null,
) {
    val tags: List<String> get() = agents.flatMap { it.tags }.distinct().sorted()
    val coordinator: Agent? get() = agents.firstOrNull { it.coordinator }
    val canSave get() = title.isNotBlank() && (coordinated || steps.all { it.agentId != null || it.tag != null }) && !saving
}

class NewTaskViewModel(private val tasks: TasksRepository, private val agents: AgentsRepository, val maxSteps: Int) : ViewModel() {
    private val _state = MutableStateFlow(NewTaskUiState())
    val state: StateFlow<NewTaskUiState> = _state

    init {
        viewModelScope.launch {
            if (agents.agents.value.isEmpty()) runCatching { agents.refresh() }
            val usable = agents.agents.value.filter { it.myPermissions.chat }
            // One agent: it's the obvious choice.
            _state.update { s -> s.copy(agents = usable, steps = if (usable.size == 1) listOf(StepDraft(usable.first().id)) else s.steps) }
        }
    }

    fun title(t: String) = _state.update { it.copy(title = t.take(120)) }
    fun instructions(t: String) = _state.update { it.copy(instructions = t) }
    fun reviewEachStep(on: Boolean) = _state.update { it.copy(reviewEachStep = on) }
    fun startNow(on: Boolean) = _state.update { it.copy(startNow = on) }
    fun coordinated(on: Boolean) = _state.update { it.copy(coordinated = on) }
    fun pickAgent(i: Int, id: String) = setStep(i, StepDraft(agentId = id))
    fun pickTag(i: Int, tag: String) = setStep(i, StepDraft(tag = tag))
    fun addStep() = _state.update { if (it.steps.size < maxSteps) it.copy(steps = it.steps + StepDraft()) else it }
    fun removeStep(i: Int) = _state.update { if (it.steps.size > 1) it.copy(steps = it.steps.filterIndexed { j, _ -> j != i }) else it }
    private fun setStep(i: Int, d: StepDraft) = _state.update { s -> s.copy(steps = s.steps.mapIndexed { j, old -> if (j == i) d else old }) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        if (!s.canSave) return@launch
        _state.update { it.copy(saving = true, error = null) }
        try {
            val column = if (s.startNow) Columns.READY else Columns.BACKLOG
            val t = if (s.coordinated) tasks.create(TaskInput(s.title.trim(), s.instructions.trim(), emptyList(), column = column, coordinated = true))
                else tasks.create(TaskInput(s.title.trim(), s.instructions.trim(), s.steps.map { TaskStepInput(it.agentId, it.tag) },
                    reviewEachStep = s.reviewEachStep && s.steps.size > 1, column = column))
            _state.update { it.copy(created = t.id) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.friendly()) }
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }
}

// ── specialties ─────────────────────────────────────────────────────────────

data class SpecialtyDraft(val about: String, val tags: String, val costLevel: String?)

data class SpecialtiesUiState(
    val agents: List<Agent> = emptyList(),
    val drafts: Map<String, SpecialtyDraft> = emptyMap(),
    val saving: String? = null,
    val saved: String? = null,
    val error: String? = null,
)

/** Owner and admins describe what each agent is good at (spec F33). */
class SpecialtiesViewModel(private val agents: AgentsRepository) : ViewModel() {
    private val _state = MutableStateFlow(SpecialtiesUiState())
    val state: StateFlow<SpecialtiesUiState> = _state

    init {
        viewModelScope.launch {
            runCatching { agents.refresh() }
            val list = agents.agents.value
            _state.update { it.copy(agents = list, drafts = list.associate { a -> a.id to SpecialtyDraft(a.about, a.tags.joinToString(", "), a.costLevel) }) }
        }
    }

    fun edit(id: String, f: (SpecialtyDraft) -> SpecialtyDraft) = _state.update { s ->
        s.copy(drafts = s.drafts + (id to f(s.drafts[id] ?: SpecialtyDraft("", "", null))), saved = null)
    }

    fun setCoordinator(id: String, on: Boolean) = viewModelScope.launch {
        try {
            agents.setCoordinator(id, on)
            _state.update { s -> s.copy(agents = agents.agents.value.ifEmpty { s.agents }) }
        } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
    }

    fun save(id: String) = viewModelScope.launch {
        val d = _state.value.drafts[id] ?: return@launch
        _state.update { it.copy(saving = id, error = null) }
        try {
            val tags = d.tags.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val a = agents.describe(id, d.about.trim(), tags, d.costLevel)
            _state.update { s -> s.copy(agents = s.agents.map { if (it.id == id) a else it }, saved = id) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.friendly()) }
        } finally {
            _state.update { it.copy(saving = null) }
        }
    }
}
