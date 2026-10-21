package llc.applabs.switchboard.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import llc.applabs.design.tokens.MonoLabel
import llc.applabs.design.tokens.StatusColors
import llc.applabs.switchboard.domain.model.Agent
import llc.applabs.switchboard.domain.model.Columns
import llc.applabs.switchboard.domain.model.Task
import llc.applabs.switchboard.domain.model.TaskStep
import llc.applabs.switchboard.ui.common.EmptyNote
import llc.applabs.switchboard.ui.common.ErrorBanner
import llc.applabs.switchboard.ui.common.Loading
import llc.applabs.switchboard.ui.common.MarkdownText
import llc.applabs.switchboard.ui.common.MonoChip
import llc.applabs.switchboard.ui.common.SectionLabel
import llc.applabs.switchboard.ui.common.StatusDot
import llc.applabs.switchboard.ui.common.shortTime

@Composable
internal fun boardColumnColor(c: String): Color = columnColor(c)

@Composable
private fun columnColor(c: String): Color = when (c) {
    Columns.WORKING -> StatusColors.busy()
    Columns.NEEDS_YOU -> StatusColors.problem()
    Columns.REVIEW -> MaterialTheme.colorScheme.primary
    Columns.DONE -> StatusColors.online()
    else -> StatusColors.offline()
}

private fun reasonText(t: Task): String? = when (t.reason) {
    null -> null
    "approval" -> "Waiting for an approval"
    "question" -> "The agent asked a question"
    "failed" -> "Step failed: ${t.reasonDetail ?: ""}".trimEnd(':', ' ')
    "stopped" -> "The step was stopped"
    "unavailable" -> t.reasonDetail ?: "The agent isn't available"
    "limit_runs" -> "Limit reached: ${t.reasonDetail ?: "runs"}"
    "limit_time" -> "Time limit reached: ${t.reasonDetail ?: ""}".trimEnd(':', ' ')
    "plan" -> "The coordinator proposed a plan. Approve it to start the sub-cards."
    "plan_invalid" -> t.reasonDetail ?: "The coordinator's plan can't be used."
    else -> t.reasonDetail ?: t.reason
}

private fun stepAgentName(s: TaskStep, agents: List<Agent>): String =
    (s.ranOnAgentId ?: s.agentId)?.let { id -> agents.firstOrNull { it.id == id }?.name } ?: s.tag?.let { "any #$it" } ?: "agent"

private fun stepLabel(s: TaskStep, agents: List<Agent>): String = when (s.kind) {
    "plan" -> "Plan · ${stepAgentName(s, agents)}"
    "combine" -> "Combine · ${stepAgentName(s, agents)}"
    else -> "${s.position + 1} · ${stepAgentName(s, agents)}"
}

// ── board ───────────────────────────────────────────────────────────────────

/** The board (spec F23): column chips with counts, one column at a time on phones; "Open" shows every unfinished card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(viewModel: BoardViewModel, myUserId: String, onOpen: (String) -> Unit, onNew: () -> Unit, onOpenForge: (String) -> Unit = {}) {
    val s by viewModel.state.collectAsState()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Board") }, actions = { TextButton(onClick = onNew, modifier = Modifier.testTag("new-card")) { Text("New card") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.column == null, onClick = { viewModel.show(null) }, label = { Text("Open ${s.openCount}") })
                Columns.all.forEach { c ->
                    FilterChip(selected = s.column == c, onClick = { viewModel.show(c) }, label = { Text("${Columns.label(c)} ${s.count(c)}") },
                        leadingIcon = { StatusDot(columnColor(c)) }, modifier = Modifier.testTag("column-$c"))
                }
            }
            s.forgeNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
            when {
                s.loading -> Loading()
                s.error != null && s.tasks.isEmpty() -> ErrorBanner(s.error!!, onRetry = { viewModel.refresh() }, modifier = Modifier.padding(16.dp))
                s.shown.isEmpty() && s.shownForge.isEmpty() -> EmptyNote(
                    if (s.tasks.isEmpty()) "No cards yet" else "Nothing in ${s.column?.let(Columns::label) ?: "progress"}",
                    if (s.tasks.isEmpty()) "Make a card, give it to one agent or several in turn, and review the result when they're done." else "",
                    Modifier.padding(16.dp))
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(s.shown, key = { it.id }) { t -> TaskCard(t, s.agents, showColumn = s.column == null, mine = t.createdBy == myUserId) { onOpen(t.id) } }
                    items(s.shownForge, key = { it.id }) { c -> ForgeCardRow(c, showColumn = s.column == null) { onOpenForge(c.id) } }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(t: Task, agents: List<Agent>, showColumn: Boolean, mine: Boolean, onClick: () -> Unit) {
    val step = t.currentStep ?: t.steps.lastOrNull()
    val who = step?.let { stepAgentName(it, agents) }
    Card(Modifier.fillMaxWidth().testTag("card-${t.title}").clickable(onClickLabel = "Open card", onClick = onClick)
        .semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(t.title, Columns.label(t.column), reasonText(t), who?.let { "agent $it" }).joinToString(", ")
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(columnColor(t.column))
                Text(t.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
                if (showColumn) Text(Columns.label(t.column).uppercase(), style = MonoLabel, color = columnColor(t.column))
            }
            reasonText(t)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.problem(), maxLines = 2) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t.steps.size > 1) MonoChip("step ${((t.currentStep ?: t.steps.last()).position + 1)}/${t.steps.size}")
                who?.let { MonoChip(it) }
                if (t.parentId != null) MonoChip("sub-card")
                if (t.coordinated) MonoChip("coordinated")
                if (!mine) MonoChip(t.createdByName)
                if (t.outcome == "not_used") MonoChip("not used")
            }
        }
    }
}

// ── card detail ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskScreen(viewModel: TaskViewModel, onOpenChat: (String) -> Unit, onOpenTask: (String) -> Unit, onBack: () -> Unit) {
    val s by viewModel.state.collectAsState()
    var sendingBack by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(s.deleted) { if (s.deleted) onBack() }
    val t = s.task
    Scaffold(topBar = { TopAppBar(title = { Text(t?.title ?: "Card", maxLines = 1) }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { pad ->
        if (t == null) { if (s.error != null) ErrorBanner(s.error!!, onRetry = null, modifier = Modifier.padding(pad).padding(16.dp)) else Loading(Modifier.padding(pad)); return@Scaffold }
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(columnColor(t.column))
                    Text(Columns.label(t.column).uppercase() + (t.outcome?.let { if (it == "not_used") " · NOT USED" else " · ACCEPTED" } ?: ""),
                        style = MonoLabel, color = columnColor(t.column), modifier = Modifier.semantics { heading() })
                }
                reasonText(t)?.let { Text(it, color = StatusColors.problem(), style = MaterialTheme.typography.bodyMedium) }
            }
            t.parentId?.let { pid ->
                item {
                    val parent = s.all.firstOrNull { it.id == pid }
                    TextButton(onClick = { onOpenTask(pid) }) { Text("Part of \"${parent?.title ?: "a coordinated card"}\" · reviewed with it") }
                }
            }
            if (t.instructions.isNotBlank()) item { Text(t.instructions, style = MaterialTheme.typography.bodyMedium) }
            item {
                Text("Runs ${t.runsUsed} of ${t.limits.maxRuns} · sent back ${t.sendBacks} of ${t.limits.maxSendBacks} · by ${t.createdByName}",
                    style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            s.error?.let { item { ErrorBanner(it, onRetry = null) } }
            item { Actions(t, s.busy, viewModel, onOpenChat, onSendBack = { sendingBack = true }, onDelete = { confirmDelete = true }) }
            t.plan?.let { plan ->
                item { SectionLabel(if (t.children.isEmpty()) "Proposed plan" else "Plan") }
                items(plan.size) { i ->
                    val p = plan[i]
                    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${i + 1}. ${p.title}", style = MaterialTheme.typography.titleSmall)
                        Text(s.agents.firstOrNull { it.id == p.agentId }?.name ?: "agent", style = MonoLabel, color = MaterialTheme.colorScheme.primary)
                        if (p.instructions.isNotBlank()) Text(p.instructions, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (t.children.isNotEmpty()) {
                item { SectionLabel("Sub-cards") }
                items(t.children, key = { "child-$it" }) { cid ->
                    val c = s.all.firstOrNull { it.id == cid }
                    Row(Modifier.fillMaxWidth().clickable { onOpenTask(cid) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(columnColor(c?.column ?: Columns.READY))
                        Text(c?.title ?: "Sub-card", modifier = Modifier.weight(1f))
                        Text((c?.column?.let(Columns::label) ?: "").uppercase(), style = MonoLabel, color = columnColor(c?.column ?: Columns.READY))
                    }
                }
            }
            item { SectionLabel(if (t.steps.size > 1) "Steps" else "Step") }
            items(t.steps, key = { it.id }) { step -> StepRow(step, t, s.agents, onOpenChat) }
        }
    }
    if (sendingBack && t != null) SendBackDialog(t, s.agents, onDismiss = { sendingBack = false }) { note, step -> sendingBack = false; viewModel.sendBack(note, step) }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this card?") }, text = { Text("Its step conversations stay in Chats.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(t: Task, busy: Boolean, vm: TaskViewModel, onOpenChat: (String) -> Unit, onSendBack: () -> Unit, onDelete: () -> Unit) {
    val running = t.steps.firstOrNull { it.status == "running" }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            t.column == Columns.NEEDS_YOU && t.reason == "plan" -> {
                Button(onClick = { vm.accept() }, enabled = !busy, modifier = Modifier.testTag("plan-approve")) { Text("Approve plan") }
                OutlinedButton(onClick = onSendBack, enabled = !busy && t.sendBacks < t.limits.maxSendBacks) { Text("Ask for another plan") }
                TextButton(onClick = { vm.close() }, enabled = !busy) { Text("Close") }
            }
            t.parentId != null && t.column == Columns.REVIEW -> Text("Reviewed with its card.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            t.coordinated && t.column == Columns.WORKING && running == null -> Text(t.reasonDetail ?: "Waiting for its sub-cards.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> when (t.column) {
            Columns.BACKLOG -> {
                Button(onClick = { vm.start() }, enabled = !busy, modifier = Modifier.testTag("card-start")) { Text("Start") }
                OutlinedButton(onClick = onDelete, enabled = !busy) { Text("Delete") }
            }
            Columns.READY -> OutlinedButton(onClick = { vm.toBacklog() }, enabled = !busy) { Text("Back to backlog") }
            Columns.WORKING -> running?.sessionId?.let { id -> Button(onClick = { onOpenChat(id) }) { Text("Watch it work") } }
            Columns.NEEDS_YOU -> {
                if (running?.sessionId != null) Button(onClick = { onOpenChat(running.sessionId!!) }) {
                    Text(if (t.reason == "question") "Answer the agent" else if (t.reason == "approval") "Review the request" else "Open conversation")
                }
                else {
                    if (t.reason?.startsWith("limit_") != true) Button(onClick = { vm.start() }, enabled = !busy) { Text("Try again") }
                    OutlinedButton(onClick = { vm.toBacklog() }, enabled = !busy) { Text("Back to backlog") }
                    OutlinedButton(onClick = { vm.close() }, enabled = !busy) { Text("Close") }
                }
            }
            Columns.REVIEW -> {
                Button(onClick = { vm.accept() }, enabled = !busy, modifier = Modifier.testTag("card-accept")) {
                    Text(if (t.steps.all { it.status == "done" }) "Accept" else "Accept, continue")
                }
                OutlinedButton(onClick = onSendBack, enabled = !busy && t.sendBacks < t.limits.maxSendBacks) { Text("Send back") }
                TextButton(onClick = { vm.close() }, enabled = !busy) { Text("Close") }
            }
            Columns.DONE -> OutlinedButton(onClick = onDelete, enabled = !busy) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun StepRow(step: TaskStep, t: Task, agents: List<Agent>, onOpenChat: (String) -> Unit) {
    // The latest result opens by itself, also when it arrives while the card is on screen.
    var open by remember(step.id, step.status, step.finishedAt) { mutableStateOf(step.status == "done" && step.id == t.steps.lastOrNull { it.status == "done" }?.id) }
    val color = when (step.status) { "done" -> StatusColors.online(); "running" -> StatusColors.busy(); "failed" -> StatusColors.problem(); else -> StatusColors.offline() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(color)
            Text(stepLabel(step, agents), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(listOfNotNull(step.finishedAt?.let(::shortTime) ?: step.startedAt?.let(::shortTime), step.status.uppercase()).joinToString(" · "),
                style = MonoLabel, color = color)
        }
        step.note?.let { Text("Sent back: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!step.result.isNullOrBlank()) {
            if (open) MarkdownText(step.result!!)
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide result" else "Show result") }
        }
        step.sessionId?.let { id -> TextButton(onClick = { onOpenChat(id) }) { Text("Open conversation") } }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendBackDialog(t: Task, agents: List<Agent>, onDismiss: () -> Unit, onSend: (String, Int?) -> Unit) {
    var note by remember { mutableStateOf("") }
    val done = t.steps.filter { it.status == "done" }
    var step by remember { mutableStateOf(done.lastOrNull()?.position) }
    val forPlan = t.reason == "plan"
    if (t.coordinated && !forPlan) step = t.steps.lastOrNull { it.status == "done" }?.position
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (forPlan) "Ask for another plan" else "Send back") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(note, { note = it }, label = { Text(if (forPlan) "What should the plan do differently?" else "What should change?") }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("send-back-note"))
            if (done.size > 1 && !t.coordinated) {
                Text("Redo from", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    done.forEach { d -> FilterChip(selected = step == d.position, onClick = { step = d.position }, label = { Text("${d.position + 1} · ${stepAgentName(d, agents)}") }) }
                }
            }
            Text("${t.limits.maxSendBacks - t.sendBacks} send-backs left.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = { onSend(note, step) }, enabled = note.isNotBlank()) { Text(if (forPlan) "Ask again" else "Send back") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ── new card ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewTaskScreen(viewModel: NewTaskViewModel, allowSteps: Boolean, onCreated: (String) -> Unit, onBack: () -> Unit) {
    val s by viewModel.state.collectAsState()
    LaunchedEffect(s.created) { s.created?.let(onCreated) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("New card") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            actions = { TextButton(onClick = { viewModel.save() }, enabled = s.canSave, modifier = Modifier.testTag("card-save")) { Text(if (s.startNow) "Start" else "Save") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OutlinedTextField(s.title, viewModel::title, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("card-title")) }
            item { OutlinedTextField(s.instructions, viewModel::instructions, label = { Text("Instructions") }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("card-instructions")) }
            s.coordinator?.takeIf { allowSteps }?.let { c ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Let ${c.name} plan it")
                            Text("The coordinator splits it into sub-cards for other agents. You approve the plan first.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(s.coordinated, viewModel::coordinated, modifier = Modifier.testTag("card-coordinated"))
                    }
                }
            }
            if (!s.coordinated) items(s.steps.size) { i ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(if (s.steps.size > 1) "Step ${i + 1}" else "Agent", Modifier.weight(1f))
                        if (s.steps.size > 1) TextButton(onClick = { viewModel.removeStep(i) }) { Text("Remove") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.agents.forEach { a ->
                            FilterChip(selected = s.steps[i].agentId == a.id, onClick = { viewModel.pickAgent(i, a.id) }, label = { Text(a.name) },
                                leadingIcon = { StatusDot(if (a.isAvailable) StatusColors.online() else StatusColors.offline()) })
                        }
                        s.tags.forEach { tag ->
                            FilterChip(selected = s.steps[i].tag == tag, onClick = { viewModel.pickTag(i, tag) }, label = { Text("any #$tag") })
                        }
                    }
                    s.steps[i].agentId?.let { id -> s.agents.firstOrNull { it.id == id } }?.let { a ->
                        val line = listOfNotNull(a.about.ifBlank { null }, a.costLevel?.let { "$it cost" }, a.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" })
                        if (line.isNotEmpty()) Text(line.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!s.coordinated && allowSteps && s.steps.size < viewModel.maxSteps) item {
                OutlinedButton(onClick = { viewModel.addStep() }, modifier = Modifier.testTag("add-step")) { Text("Add a step for another agent") }
            }
            if (!s.coordinated && s.steps.size > 1) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Review after each step", modifier = Modifier.weight(1f))
                    Switch(s.reviewEachStep, viewModel::reviewEachStep)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Start now")
                        Text("Otherwise it waits in Backlog.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(s.startNow, viewModel::startNow)
                }
            }
            s.error?.let { item { ErrorBanner(it, onRetry = null) } }
            item {
                Text("Each step runs in its own conversation with your permissions. You decide when the card is done.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── specialties ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpecialtiesScreen(viewModel: SpecialtiesViewModel, isOwner: Boolean, onBack: () -> Unit) {
    val s by viewModel.state.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("What agents are good at") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Shown when people pick an agent for a card, so work goes to the right model.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            s.error?.let { item { ErrorBanner(it, onRetry = null) } }
            items(s.agents, key = { it.id }) { a ->
                val d = s.drafts[a.id] ?: return@items
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(a.name, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(d.about, { v -> viewModel.edit(a.id) { it.copy(about = v.take(300)) } }, label = { Text("Good at") },
                        placeholder = { Text("Research and summaries; fast and cheap") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(d.tags, { v -> viewModel.edit(a.id) { it.copy(tags = v) } }, label = { Text("Tags") },
                        placeholder = { Text("research, writing") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("low", "medium", "high").forEach { c ->
                            FilterChip(selected = d.costLevel == c, onClick = { viewModel.edit(a.id) { it.copy(costLevel = if (it.costLevel == c) null else c) } },
                                label = { Text("$c cost") })
                        }
                    }
                    if (isOwner) Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Coordinator")
                            Text("Plans cards into sub-cards for the other agents. One per hub.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(a.coordinator, { on -> viewModel.setCoordinator(a.id, on) }, modifier = Modifier.testTag("coordinator-${a.name}"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.save(a.id) }, enabled = s.saving == null) { Text("Save") }
                        if (s.saved == a.id) Text("Saved", style = MonoLabel, color = StatusColors.online())
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
