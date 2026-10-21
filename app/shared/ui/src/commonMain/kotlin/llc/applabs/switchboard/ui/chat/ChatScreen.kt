package llc.applabs.switchboard.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import llc.applabs.design.tokens.MonoBody
import llc.applabs.design.tokens.MonoLabel
import llc.applabs.design.tokens.StatusColors
import llc.applabs.switchboard.domain.model.ToolCall
import llc.applabs.switchboard.ui.common.ErrorBanner
import llc.applabs.switchboard.ui.common.Loading
import llc.applabs.switchboard.ui.common.MarkdownText
import llc.applabs.switchboard.ui.common.MonoChip
import llc.applabs.switchboard.ui.common.StatusDot
import llc.applabs.switchboard.ui.common.statusColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, myUserId: String, onBack: () -> Unit) {
    val s by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { viewModel.effects.collect { if (it is ChatUiEffect.Toast) snackbar.showSnackbar(it.text) } }
    LaunchedEffect(s.deleted) { if (s.deleted) onBack() }
    LaunchedEffect(s.messages.size, s.messages.lastOrNull()?.text?.length) { if (s.messages.isNotEmpty()) list.animateScrollToItem(s.messages.size - 1) }
    val owner = s.session?.ownerUserId == myUserId

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                title = {
                    Column(Modifier.clickable(enabled = owner) { renaming = true }) {
                        Text(s.session?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.agent?.let { StatusDot(statusColor(it.status)); Text(it.name, style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                            if (s.agent?.capabilities?.modelSwitching == true) {
                                val label = s.session?.modelLabel ?: s.models.firstOrNull { it.isDefault }?.label
                                label?.let { MonoChip(shortModel(it), Modifier.clickable(enabled = owner) { picking = true }.testTag("model"),
                                    color = MaterialTheme.colorScheme.primary) }
                            }
                            if (s.session?.sharedWith?.isShared == true) Text("SHARED", style = MonoLabel, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                actions = {
                    if (owner) Box {
                        TextButton(onClick = { menu = true }) { Text("⋯") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text(if (s.session?.sharedWith?.everyone == true) "Stop sharing" else "Share with everyone") },
                                onClick = { menu = false; viewModel.toggleShareWithEveryone() })
                            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; viewModel.delete() })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (s.loading && s.session == null) { Loading(Modifier.weight(1f)); return@Column }
            LazyColumn(state = list, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(s.messages, key = { it.id }) { m ->
                    if (m.role == "user") UserBubble(m, mine = m.authorUserId == null || m.authorUserId == myUserId)
                    else AssistantMessage(m, onAnswer = viewModel::answer, onDecide = viewModel::decide)
                }
                if (s.running) item { RunStatusLine(s.runStatus) }
            }
            s.error?.let { ErrorBanner(it, onRetry = { viewModel.load() }, modifier = Modifier.padding(12.dp)) }
            Composer(s, viewModel::onInput, viewModel::send, viewModel::stop)
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(s.session?.title.orEmpty()) }
        AlertDialog(onDismissRequest = { renaming = false }, title = { Text("Rename") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { renaming = false; viewModel.rename(title) }, enabled = title.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } })
    }
    if (picking) ModalBottomSheet(onDismissRequest = { picking = false }) {
        Text("Model", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        LazyColumn {
            items(s.models, key = { it.id }) { m ->
                Row(Modifier.fillMaxWidth().clickable { picking = false; viewModel.switchModel(m.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m.label, modifier = Modifier.weight(1f))
                    if (m.isDefault) MonoChip("default")
                    MonoChip(if (m.isLocal) "local" else if (m.isPaid) "paid" else "free")
                    m.dailyLimit?.let { MonoChip("${m.usedToday ?: 0}/$it today") }
                }
            }
        }
    }
}

/** `nemotron-3-ultra-550b-a55b:free` → `nemotron-3-ultra…` (the picker shows the full name). */
private fun shortModel(label: String) = label.removeSuffix(":free").let { if (it.length > 18) it.take(17) + "…" else it }

@Composable
private fun UserBubble(m: ChatMessage, mine: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (m.kind == "steer") Text("GUIDANCE", style = MonoLabel, color = MaterialTheme.colorScheme.primary)
        Text(m.text, modifier = Modifier.widthIn(max = 320.dp)
            .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AssistantMessage(m: ChatMessage, onAnswer: (String, String) -> Unit, onDecide: (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        m.tools.forEach { ToolCard(it) }
        if (m.text.isNotEmpty()) SelectionContainer { MarkdownText(m.text) }
        m.questions.forEach { q -> QuestionCard(q, onAnswer) }
        m.approvals.forEach { a -> ApprovalRequestCard(a, onDecide) }
        when (m.status) {
            "failed" -> Text("Didn't finish", style = MonoLabel, color = StatusColors.problem())
            "cancelled" -> Text("Stopped", style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            "partial" -> if (m.runId == null) Text("Incomplete", style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        m.modelLabel?.let { if (m.status == "complete") Text(it, style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ToolCard(t: ToolCall) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).clickable { open = !open }.padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(when (t.state) { "running" -> StatusColors.busy(); "error" -> StatusColors.problem(); else -> StatusColors.online() })
            Text(t.tool.uppercase(), style = MonoLabel)
            Text(t.preview.orEmpty(), style = MonoBody, maxLines = if (open) 20 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            t.durationS?.let { Text("${(it * 10).toInt() / 10.0}s", style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (open && !t.outputExcerpt.isNullOrBlank()) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SelectionContainer { Text(t.outputExcerpt!!, style = MonoBody) }
        }
    }
}

@Composable
private fun QuestionCard(q: Question, onAnswer: (String, String) -> Unit) {
    val c = q.clarification
    val open = c.status == "pending"
    Column(Modifier.fillMaxWidth().border(1.dp, if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("QUESTION", style = MonoLabel, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
        Text(c.question, style = MaterialTheme.typography.bodyLarge)
        if (!open) Text(if (c.status == "answered") "Answered: ${ChatViewModel.answerText(c.answer)}" else "No longer waiting (${c.status})",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (!q.canAnswer) Text("Waiting for the person who asked.", style = MaterialTheme.typography.bodySmall)
        else {
            c.choices.forEach { choice -> OutlinedButton(onClick = { onAnswer(c.id, choice) }, modifier = Modifier.fillMaxWidth()) { Text(choice) } }
            var text by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, placeholder = { Text(if (c.choices.isEmpty()) "Your answer" else "Or type an answer") },
                    modifier = Modifier.weight(1f).testTag("answer"))
                Button(onClick = { onAnswer(c.id, text) }, enabled = text.isNotBlank()) { Text("Send") }
            }
        }
    }
}

private val decisionLabels = mapOf("approve_once" to "Allow once", "approve_session" to "Allow for this chat",
    "approve_always" to "Always allow", "deny" to "Deny")

/** An agent asking before it runs something risky (spec: Chat, approval requests). */
@Composable
private fun ApprovalRequestCard(a: ApprovalCard, onDecide: (String, String) -> Unit) {
    val open = a.decision == null
    Column(Modifier.fillMaxWidth().border(2.dp, if (open) StatusColors.busy() else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("APPROVAL NEEDED", style = MonoLabel, color = StatusColors.busy(), modifier = Modifier.semantics { heading() })
        a.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        SelectionContainer { Text(a.command, style = MonoBody, modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(8.dp)) }
        when {
            !open -> Text("${decisionLabels[a.decision] ?: a.decision}" + when (a.decidedBy) { "agent" -> " (decided on the agent)"; "expired" -> " (expired)"; else -> "" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            !a.canDecide -> Text("Waiting for someone who can approve it.", style = MaterialTheme.typography.bodySmall)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                a.allowed.filter { it != "deny" && it != "approve_always" }.forEach { d ->
                    Button(onClick = { onDecide(a.id, d) }, modifier = Modifier.testTag(d)) { Text(decisionLabels[d] ?: d) }
                }
                if ("deny" in a.allowed) OutlinedButton(onClick = { onDecide(a.id, "deny") }, modifier = Modifier.testTag("deny")) { Text("Deny") }
            }
        }
        if (open && a.canDecide && "approve_always" in a.allowed)
            TextButton(onClick = { onDecide(a.id, "approve_always") }) { Text("Always allow this command (for everyone)", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun RunStatusLine(status: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        StatusDot(StatusColors.busy())
        Text(when (status) {
            "waiting_for_input" -> "WAITING FOR YOUR ANSWER"
            "waiting_for_approval" -> "WAITING FOR APPROVAL"
            "stopping" -> "STOPPING…"
            else -> "WORKING…"
        }, style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Composer(s: ChatUiState, onInput: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(s.input, onInput, modifier = Modifier.weight(1f).testTag("composer"), maxLines = 5, enabled = !s.running || s.canSteer,
            placeholder = { Text(if (s.running) (if (s.canSteer) "Add guidance…" else "Working…") else "Message ${s.agent?.name ?: ""}") })
        if (s.running) {
            if (s.canSteer && s.input.isNotBlank()) FilledTonalButton(onClick = onSend) { Text("Guide") }
            Button(onClick = onStop, modifier = Modifier.testTag("stop")) { Text("Stop") }
        } else Button(onClick = onSend, enabled = s.input.isNotBlank() && !s.sending && s.agent?.isAvailable != false, modifier = Modifier.testTag("send")) { Text("Send") }
    }
}
