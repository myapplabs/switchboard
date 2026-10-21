package llc.applabs.switchboard.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import llc.applabs.design.tokens.MonoBody
import llc.applabs.design.tokens.MonoLabel
import llc.applabs.design.tokens.StatusColors
import llc.applabs.switchboard.domain.ForgeRepository
import llc.applabs.switchboard.domain.model.Columns
import llc.applabs.switchboard.domain.model.ForgeBridge
import llc.applabs.switchboard.domain.model.ForgeBridgeCreated
import llc.applabs.switchboard.domain.model.ForgeCard
import llc.applabs.switchboard.domain.model.ForgeRun
import llc.applabs.switchboard.domain.model.ForgeStart
import llc.applabs.switchboard.ui.common.EmptyNote
import llc.applabs.switchboard.ui.common.ErrorBanner
import llc.applabs.switchboard.ui.common.MonoChip
import llc.applabs.switchboard.ui.common.SectionLabel
import llc.applabs.switchboard.ui.common.StatusDot
import llc.applabs.switchboard.ui.common.friendly
import llc.applabs.switchboard.ui.common.shortTime

private fun usd(v: Double): String { val cents = kotlin.math.round(v * 100).toLong(); return "$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0') }

/** "M1 — iOS platform parity" → "M1" (the full name is on the ticket's screen). */
private fun milestoneCode(m: String) = m.substringBefore(" —").substringBefore(" -").trim()

private fun forgeStatus(s: String) = when (s) {
    "draft" -> "Draft"; "todo" -> "To do"; "in_progress" -> "In progress"; "in_review" -> "In review"; "done" -> "Done"; else -> s
}

/** A FORGE ticket on the board (read from FORGE; FORGE stays the system of record). */
@Composable
fun ForgeCardRow(c: ForgeCard, showColumn: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("forge-${c.key}").clickable(onClickLabel = "Open ticket", onClick = onClick)
        .semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull("FORGE ticket ${c.key}", c.title, Columns.label(c.column), c.waitingFor?.let { "waiting for $it" }).joinToString(", ")
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(boardColumnColor(c.column))
                Text(c.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
                if (showColumn) Text(Columns.label(c.column).uppercase(), style = MonoLabel, color = boardColumnColor(c.column))
            }
            c.waitingFor?.let { Text("FORGE waits for your $it", style = MaterialTheme.typography.bodySmall, color = StatusColors.problem()) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoChip("FORGE", color = MaterialTheme.colorScheme.primary)
                MonoChip("${c.project} · ${c.key}")
                c.milestone?.let { MonoChip(milestoneCode(it)) }
            }
        }
    }
}

// ── one ticket ──────────────────────────────────────────────────────────────

data class ForgeTicketUiState(
    val card: ForgeCard? = null,
    val run: ForgeRun? = null,
    val preview: ForgeStart? = null,
    val busy: Boolean = false,
    val done: String? = null,
    val error: String? = null,
)

class ForgeTicketViewModel(private val id: String, private val forge: ForgeRepository) : ViewModel() {
    private val _state = MutableStateFlow(ForgeTicketUiState())
    val state: StateFlow<ForgeTicketUiState> = _state

    init {
        viewModelScope.launch {
            forge.board.collect { b -> b.cards.firstOrNull { it.id == id }?.let { c -> _state.update { it.copy(card = c) }; loadRun(c) } }
        }
    }

    private suspend fun loadRun(c: ForgeCard) {
        val runId = c.runId ?: return
        if (c.column !in setOf(Columns.NEEDS_YOU, Columns.WORKING, Columns.REVIEW) || _state.value.run?.runId == runId) return
        try { _state.update { it.copy(run = forge.run(runId)) } } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
    }

    fun preview() = act { val c = _state.value.card ?: return@act; _state.update { it.copy(preview = forge.start(c.project, c.key, confirmed = false)) } }
    fun start() = act {
        val c = _state.value.card ?: return@act
        val r = forge.start(c.project, c.key, confirmed = true)
        _state.update { it.copy(preview = null, done = if (r.started) "Started in FORGE. It waits for your plan approval." else "FORGE didn't start it.") }
    }
    fun dismissPreview() = _state.update { it.copy(preview = null) }
    fun decide(decision: String, note: String?) = act {
        val runId = _state.value.card?.runId ?: return@act
        forge.decide(runId, decision, note)
        _state.update { it.copy(run = null, done = if (decision == "approve") "Approved. FORGE continues the run." else "Sent back to FORGE with your note.") }
    }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.update { it.copy(busy = true, error = null) }
        try { block() } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } } finally { _state.update { it.copy(busy = false) } }
    }
}

/** The review's recommendation from forge_run (APPROVE / REJECT + reason), when FORGE has one. */
private fun recommendation(run: ForgeRun?): String? {
    val r = run?.review as? JsonObject ?: return null
    val verdict = (r["recommendation"] as? JsonPrimitive)?.content ?: (r["verdict"] as? JsonPrimitive)?.content ?: return null
    val reason = (r["reason"] as? JsonPrimitive)?.content
    return listOfNotNull(verdict.uppercase(), reason).joinToString(": ")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ForgeTicketScreen(viewModel: ForgeTicketViewModel, onBack: () -> Unit) {
    val s by viewModel.state.collectAsState()
    var rejecting by remember { mutableStateOf(false) }
    val c = s.card
    Scaffold(topBar = { TopAppBar(title = { Text(c?.key ?: "FORGE ticket") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { pad ->
        if (c == null) { EmptyNote("Not on the board", "FORGE isn't connected, or the ticket is gone.", Modifier.padding(pad).padding(16.dp)); return@Scaffold }
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(c.title, style = MaterialTheme.typography.titleLarge) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MonoChip("FORGE", color = MaterialTheme.colorScheme.primary)
                    MonoChip(c.project)
                    MonoChip(forgeStatus(c.status))
                    c.milestone?.let { MonoChip(it) }
                    c.grade?.let { MonoChip("spec $it") }
                }
            }
            s.run?.let { r ->
                item { SectionLabel("Run") }
                item {
                    Text(listOfNotNull(r.phase.lowercase().replace('_', ' '), r.costUsd?.let { "$" + ((it * 100).toInt() / 100.0) }).joinToString(" · "),
                        style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                r.interventionReason?.let { item { Text(it, color = StatusColors.problem()) } }
                recommendation(r)?.let { item { Text("FORGE's recommendation: $it", style = MaterialTheme.typography.bodyMedium) } }
            }
            s.done?.let { item { Text(it, color = StatusColors.online()) } }
            s.error?.let { item { ErrorBanner(it, onRetry = null) } }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        c.column == Columns.NEEDS_YOU && c.runId != null -> {
                            Text("FORGE waits for your ${c.waitingFor ?: "decision"}.", modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
                            Button(onClick = { viewModel.decide("approve", null) }, enabled = !s.busy, modifier = Modifier.testTag("forge-approve")) { Text("Approve") }
                            OutlinedButton(onClick = { rejecting = true }, enabled = !s.busy) { Text("Reject with a note") }
                        }
                        c.status in setOf("draft", "todo") && s.done == null ->
                            Button(onClick = { viewModel.preview() }, enabled = !s.busy, modifier = Modifier.testTag("forge-start")) { Text("Start in FORGE…") }
                        else -> Text("Managed in FORGE. Decisions come here when FORGE waits for you.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    s.preview?.let { p ->
        AlertDialog(onDismissRequest = viewModel::dismissPreview, title = { Text("Start ${c?.key} in FORGE?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Spec: ${p.specVerdict ?: "?"}${p.specScore?.let { " (${it.toInt()})" } ?: ""}")
                val e = p.estimate
                val typical = e?.typical; val low = e?.low; val high = e?.high
                Text(if (typical == null) "Estimated cost: not enough history yet"
                    else "Estimated cost: about ${usd(typical)}" + (if (low != null && high != null) " (${usd(low)}–${usd(high)})" else ""))
                e?.basis?.let { Text("From $it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("FORGE researches and plans, then waits for your plan approval.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = { viewModel.start() }, enabled = !s.busy) { Text("Start") } },
            dismissButton = { TextButton(onClick = viewModel::dismissPreview) { Text("Cancel") } })
    }
    if (rejecting) {
        var note by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { rejecting = false }, title = { Text("Reject") }, text = {
            OutlinedTextField(note, { note = it }, label = { Text("What should change?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        }, confirmButton = { TextButton(onClick = { rejecting = false; viewModel.decide("reject", note.trim()) }, enabled = note.isNotBlank()) { Text("Reject") } },
            dismissButton = { TextButton(onClick = { rejecting = false }) { Text("Cancel") } })
    }
}

// ── connections ─────────────────────────────────────────────────────────────

data class ForgeLinksUiState(
    val bridges: List<ForgeBridge> = emptyList(),
    val created: ForgeBridgeCreated? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class ForgeLinksViewModel(private val forge: ForgeRepository) : ViewModel() {
    private val _state = MutableStateFlow(ForgeLinksUiState())
    val state: StateFlow<ForgeLinksUiState> = _state
    init { load() }

    fun load() = viewModelScope.launch {
        try { _state.update { it.copy(bridges = forge.bridges(), error = null) } } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
    }
    fun connect(name: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        try { val c = forge.connect(name.trim()); _state.update { it.copy(created = c) }; load() }
        catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } } finally { _state.update { it.copy(busy = false) } }
    }
    fun closeCode() { _state.update { it.copy(created = null) }; load() }
    fun revoke(id: String) = viewModelScope.launch {
        try { forge.revoke(id); load() } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
    }
}

/** More → FORGE: connect a FORGE with a one-time code (the token never reaches the app), see and revoke connections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgeLinksScreen(viewModel: ForgeLinksViewModel, hubAddress: String, onBack: () -> Unit) {
    val s by viewModel.state.collectAsState()
    var naming by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("FORGE") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Show your FORGE tickets on the board and decide FORGE's gates here. FORGE keeps running where it runs; " +
                    "it connects out to this hub, and nothing on that computer is opened to the network.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            s.error?.let { item { ErrorBanner(it, onRetry = { viewModel.load() }) } }
            item { SectionLabel("Connections") }
            if (s.bridges.isEmpty()) item { Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(s.bridges, key = { it.id }) { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (b.connected) StatusColors.online() else StatusColors.offline())
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(b.name)
                        Text(when { b.connected -> "Connected"; !b.paired -> "Waiting for the code"; else -> "Last seen ${b.lastSeenAt?.let(::shortTime) ?: "never"}" },
                            style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { viewModel.revoke(b.id) }) { Text("Revoke") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item { Button(onClick = { naming = true }, enabled = !s.busy, modifier = Modifier.padding(top = 8.dp).testTag("forge-connect")) { Text("Connect a FORGE") } }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { naming = false }, title = { Text("Connect a FORGE") }, text = {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name it") }, placeholder = { Text("laptop") }, singleLine = true)
        }, confirmButton = { TextButton(onClick = { naming = false; viewModel.connect(name) }, enabled = name.isNotBlank()) { Text("Get a code") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } })
    }
    s.created?.let { c ->
        AlertDialog(onDismissRequest = viewModel::closeCode, title = { Text("Code for ${c.bridge.name}") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.code, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("forge-code"))
                Text("Works once, for 10 minutes. On the computer where FORGE runs:", style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text("forge switchboard connect --hub ${hubAddress.removePrefix("https://").removePrefix("http://")} --code ${c.code}", style = MonoBody) }
            }
        }, confirmButton = { TextButton(onClick = viewModel::closeCode) { Text("Done") } })
    }
}
