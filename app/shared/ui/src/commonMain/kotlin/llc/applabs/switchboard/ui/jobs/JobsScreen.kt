package llc.applabs.switchboard.ui.jobs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import llc.applabs.design.tokens.MonoLabel
import llc.applabs.design.tokens.StatusColors
import llc.applabs.switchboard.domain.AgentsRepository
import llc.applabs.switchboard.domain.ControlRepository
import llc.applabs.switchboard.domain.LiveEvents
import llc.applabs.switchboard.domain.model.Agent
import llc.applabs.switchboard.domain.model.Job
import llc.applabs.switchboard.domain.model.JobInput
import llc.applabs.switchboard.domain.model.RelaySetup
import llc.applabs.switchboard.domain.model.RelayStatus
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import llc.applabs.switchboard.domain.model.UserEvent
import llc.applabs.switchboard.ui.common.EmptyNote
import llc.applabs.switchboard.ui.common.ErrorBanner
import llc.applabs.switchboard.ui.common.MonoChip
import llc.applabs.switchboard.ui.common.StatusDot
import llc.applabs.switchboard.ui.common.friendly
import llc.applabs.switchboard.ui.common.shortTime

enum class JobFilter { All, Paused, Failed }

data class JobsUiState(
    val agents: List<Agent> = emptyList(),
    val jobs: List<Job> = emptyList(),
    val filter: JobFilter = JobFilter.All,
    val loading: Boolean = true,
    val error: String? = null,
    /** Per agent: can its job results reach this hub (owner and admins see this). */
    val relay: Map<String, RelayStatus> = emptyMap(),
    /** An open setup: the agent and the command to run on its computer. */
    val setup: Pair<String, RelaySetup>? = null,
    /** A result being opened ("agentId/jobId"), so its button waits. */
    val opening: String? = null,
) {
    val visible get() = jobs.filter {
        when (filter) { JobFilter.All -> true; JobFilter.Paused -> it.state == "paused"; JobFilter.Failed -> it.lastResult == "failure" }
    }
}

/** Jobs (spec: Jobs): scheduled work on every agent that runs jobs; controls only with job permission. */
class JobsViewModel(private val agents: AgentsRepository, private val control: ControlRepository, live: LiveEvents, private val canSeeRelay: Boolean = false) : ViewModel() {
    private val _state = MutableStateFlow(JobsUiState())
    val state: StateFlow<JobsUiState> = _state

    init {
        load()
        viewModelScope.launch { live.events.collect { if (it is UserEvent.JobUpdated || it is UserEvent.Resync) load() } }
    }

    fun filter(f: JobFilter) = _state.update { it.copy(filter = f) }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try {
            if (agents.agents.value.isEmpty()) agents.refresh()
            val withJobs = agents.agents.value.filter { it.capabilities.jobs }
            val jobs = withJobs.flatMap { a -> runCatching { control.jobs(a.id) }.getOrDefault(emptyList()) }
            val relay = if (!canSeeRelay) emptyMap() else withJobs.mapNotNull { a -> runCatching { a.id to control.relayStatus(a.id) }.getOrNull() }.toMap()
            _state.update { it.copy(agents = withJobs, relay = relay, jobs = jobs.sortedWith(compareBy({ it.state == "paused" }, { it.nextRunAt ?: "~" }))) }
        } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
        _state.update { it.copy(loading = false) }
    }

    fun action(job: Job, action: String) = act { control.jobAction(job.agentId, job.id, action) }
    fun delete(job: Job) = act { control.deleteJob(job.agentId, job.id) }
    fun create(agentId: String, input: JobInput) = act { control.createJob(agentId, input) }
    fun resultsHere(job: Job, on: Boolean) = act { control.setResultsHere(job.agentId, job.id, on) }

    /** Open a conversation in which the agent shows a run's result ([at]: one of `recentRuns`, null: the last). */
    fun openResult(job: Job, at: String?, onOpen: (String) -> Unit) = viewModelScope.launch {
        val key = "${job.agentId}/${job.id}"
        if (_state.value.opening != null) return@launch
        _state.update { it.copy(opening = key, error = null) }
        try { onOpen(control.openJobResult(job.agentId, job.id, at).sessionId) }
        catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
        finally { _state.update { it.copy(opening = null) } }
    }

    /** Start (or redo) an agent's setup; watches until the agent connects. */
    fun connect(agentId: String) = viewModelScope.launch {
        if (_state.value.setup?.first == agentId) return@launch   // already showing a command: keep it
        try {
            val setup = control.relaySetup(agentId)
            _state.update { it.copy(setup = agentId to setup) }
            while (isActive && _state.value.setup?.first == agentId) {
                val st = runCatching { control.relayStatus(agentId) }.getOrNull()
                if (st != null) _state.update { it.copy(relay = it.relay + (agentId to st)) }
                if (st?.status == "connected") break
                delay(3_000)
            }
        } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
    }

    fun closeSetup() = _state.update { it.copy(setup = null) }
    fun disconnect(agentId: String) = act { control.removeRelay(agentId) }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        try { block(); load() } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(viewModel: JobsViewModel, isOwner: Boolean = false, onOpenChat: (String) -> Unit = {}) {
    val s by viewModel.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Job?>(null) }
    val manage = { job: Job -> s.agents.firstOrNull { it.id == job.agentId }?.myPermissions?.manageJobs == true }
    val canChat = { job: Job -> s.agents.firstOrNull { it.id == job.agentId }?.myPermissions?.chat == true }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Jobs") }, actions = {
            if (s.agents.any { it.myPermissions.manageJobs }) TextButton(onClick = { creating = true }, modifier = Modifier.testTag("new-job")) { Text("New job") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JobFilter.entries.forEach { f -> FilterChip(selected = s.filter == f, onClick = { viewModel.filter(f) }, label = { Text(f.name) }) }
            }
            s.error?.let { ErrorBanner(it, onRetry = { viewModel.load() }, modifier = Modifier.padding(16.dp)) }
            PullToRefreshBox(isRefreshing = s.loading && s.jobs.isNotEmpty(), onRefresh = { viewModel.load() }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!s.loading && s.visible.isEmpty()) item {
                        EmptyNote(if (s.agents.isEmpty()) "No agents run jobs" else "No jobs here",
                            if (s.agents.isEmpty()) "Jobs appear for agents that support scheduled work." else "Scheduled work your agents run shows up here.")
                    }
                    if (isOwner) items(s.agents.filter { (s.relay[it.id]?.status ?: "off") != "connected" }, key = { "relay-${it.id}" }) { a ->
                        RelayCard(a.name, s.relay[a.id] ?: RelayStatus(), onConnect = { viewModel.connect(a.id) })
                    }
                    items(s.visible, key = { "${it.agentId}/${it.id}" }) { job ->
                        JobCard(job, agentName = s.agents.firstOrNull { it.id == job.agentId }?.name ?: "", manage = manage(job),
                            relayStatus = s.relay[job.agentId]?.status,
                            onAction = { viewModel.action(job, it) }, onDelete = { confirmDelete = job },
                            onResultsHere = { viewModel.resultsHere(job, it) }, onOpenResults = { job.resultsSessionId?.let(onOpenChat) },
                            canShowResult = canChat(job), opening = s.opening == "${job.agentId}/${job.id}",
                            onShowResult = { at -> viewModel.openResult(job, at, onOpenChat) })
                    }
                }
            }
        }
    }
    s.setup?.let { (agentId, setup) ->
        SetupDialog(s.agents.firstOrNull { it.id == agentId }?.name ?: "the agent", setup, s.relay[agentId] ?: RelayStatus("waiting"),
            onDismiss = { viewModel.closeSetup(); viewModel.load() })
    }
    if (creating) NewJobDialog(s.agents.filter { it.myPermissions.manageJobs }, onCreate = { a, i -> creating = false; viewModel.create(a, i) }, onDismiss = { creating = false })
    confirmDelete?.let { job ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete \"${job.name}\"?") }, text = { Text("The agent stops running it.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; viewModel.delete(job) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun JobCard(job: Job, agentName: String, manage: Boolean, relayStatus: String?, onAction: (String) -> Unit, onDelete: () -> Unit,
                    onResultsHere: (Boolean) -> Unit, onOpenResults: () -> Unit,
                    canShowResult: Boolean = false, opening: Boolean = false, onShowResult: (String?) -> Unit = {}) {
    Card(Modifier.fillMaxWidth().testTag("job-${job.name}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(when { job.state == "paused" -> StatusColors.offline(); job.lastResult == "failure" -> StatusColors.problem()
                    job.state == "running" -> StatusColors.busy(); else -> StatusColors.online() })
                Text(job.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                Text(job.state.uppercase(), style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoChip(agentName)
                MonoChip(job.schedule)
                job.lastResult?.let { MonoChip(if (it == "success") "last ok" else "last failed", color = if (it == "success") StatusColors.online() else StatusColors.problem()) }
            }
            Text(listOfNotNull(job.nextRunAt?.takeIf { job.state != "paused" }?.let { "Next ${it.take(16).replace('T', ' ')} UTC" },
                job.lastRunAt?.let { shortTime(it).let { t -> when { t == "now" -> "Last ran just now"; t.contains('-') -> "Last ran $t"; else -> "Last ran $t ago" } } }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            job.lastError?.takeIf { job.lastResult == "failure" }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3)
            }
            if (job.recentRuns.size > 1) {
                val ok = job.recentRuns.count { it.result == "success" }
                val failed = job.recentRuns.count { it.result == "failure" }
                Row(Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "Last ${job.recentRuns.size} runs: $ok succeeded" + if (failed > 0) ", $failed failed" else ""
                }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent", style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Oldest on the left, newest on the right, like a timeline.
                    job.recentRuns.reversed().forEach { r ->
                        // Tapping a run shows its result (a touch target bigger than the dot).
                        Box(Modifier.size(24.dp).then(if (canShowResult && !opening) Modifier.clickable(onClickLabel = "Show this run's result") { onShowResult(r.at) } else Modifier),
                            contentAlignment = Alignment.Center) {
                            StatusDot(when (r.result) { "success" -> StatusColors.online(); "failure" -> StatusColors.problem(); else -> StatusColors.offline() }, Modifier.size(8.dp))
                        }
                    }
                }
            }
            // Only a connected agent's jobs can be switched over: until then Hermes refuses to run them.
            if (job.resultsHere && relayStatus != null && relayStatus != "connected")
                Text("$agentName isn't connected for job results, so Hermes may not run this job. Connect it again, or turn this off.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (manage && (relayStatus == "connected" || job.resultsHere)) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Send results here", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(job.resultsHere, onCheckedChange = onResultsHere, modifier = Modifier.testTag("results-${job.name}"))
            }
            Row {
                if (canShowResult && job.lastRunAt != null) TextButton(onClick = { onShowResult(null) }, enabled = !opening,
                    modifier = Modifier.testTag("show-result-${job.name}")) { Text(if (opening) "Opening…" else "Show last result") }
                if (job.resultsSessionId != null) TextButton(onClick = onOpenResults) { Text("Open results") }
            }
            if (manage) Row {
                if (job.state == "paused") TextButton(onClick = { onAction("resume") }) { Text("Resume") } else TextButton(onClick = { onAction("pause") }) { Text("Pause") }
                TextButton(onClick = { onAction("run") }) { Text(if (job.lastResult == "failure") "Retry now" else "Run now") }
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun NewJobDialog(agents: List<Agent>, onCreate: (String, JobInput) -> Unit, onDismiss: () -> Unit) {
    var agentId by remember { mutableStateOf(agents.firstOrNull()?.id) }
    var name by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New job") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (agents.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                agents.forEach { a -> FilterChip(selected = agentId == a.id, onClick = { agentId = a.id }, label = { Text(a.name) }) }
            }
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(schedule, { schedule = it }, label = { Text("Schedule") }, placeholder = { Text("every 6h · 0 9 * * *") }, singleLine = true)
            OutlinedTextField(prompt, { prompt = it }, label = { Text("What should the agent do?") }, minLines = 3)
        }
    }, confirmButton = {
        TextButton(onClick = { agentId?.let { onCreate(it, JobInput(name.trim(), schedule.trim(), prompt)) } },
            enabled = agentId != null && name.isNotBlank() && schedule.isNotBlank() && prompt.isNotBlank()) { Text("Create") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** For the owner: an agent whose job results can't reach the hub yet. */
@Composable
private fun RelayCard(agentName: String, status: RelayStatus, onConnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Job results from $agentName", style = MaterialTheme.typography.titleSmall)
            Text(when (status.status) {
                "disconnected" -> "$agentName isn't connected right now${status.lastSeenAt?.let { " (last seen ${shortTime(it)})" } ?: ""}. Check that its Hermes gateway is running, or set it up again."
                "waiting" -> "Set up, but $agentName hasn't connected yet. Run the setup command on its computer."
                else -> "See what $agentName's jobs produce, in a conversation here, like a chat. Uses Hermes's relay, which is still experimental in Hermes."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onConnect) { Text(if (status.status == "off") "Connect" else "Set up again") }
        }
    }
}

@Composable
private fun SetupDialog(agentName: String, setup: RelaySetup, status: RelayStatus, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val connected = status.status == "connected"
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (connected) "$agentName is connected" else "Connect $agentName") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (connected) Text("Its job results can now come here. Turn on \"Send results here\" for the jobs you want.")
            else {
                Text("On $agentName's computer, in a terminal, as the account Hermes runs as, run:")
                Text(setup.command, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.semantics { contentDescription = "Setup command" })
                TextButton(onClick = { clipboard.setText(AnnotatedString(setup.command)) }) { Text("Copy command") }
                Text("It works once, for 10 minutes. It saves a key for $agentName only and restarts its Hermes gateway. Waiting for $agentName to connect…",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(if (connected) "Done" else "Close") } })
}
