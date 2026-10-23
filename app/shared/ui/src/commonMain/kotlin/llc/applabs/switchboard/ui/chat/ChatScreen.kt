package llc.applabs.switchboard.ui.chat

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import org.jetbrains.compose.resources.decodeToImageBitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import llc.applabs.switchboard.domain.model.Attachment
import llc.applabs.switchboard.domain.model.PickedFile
import llc.applabs.switchboard.ui.common.AppIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import llc.applabs.switchboard.ui.common.unwrapJobResult
import llc.applabs.switchboard.ui.common.MonoChip
import llc.applabs.switchboard.ui.common.StatusDot
import llc.applabs.switchboard.ui.common.statusColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, myUserId: String, onBack: () -> Unit,
               pickPhoto: (suspend () -> PickedFile?)? = null, pickFile: (suspend () -> PickedFile?)? = null,
               openFile: ((String, String, ByteArray) -> Unit)? = null) {
    val s by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { viewModel.effects.collect { if (it is ChatUiEffect.Toast) snackbar.showSnackbar(it.text) } }
    LaunchedEffect(s.deleted) { if (s.deleted) onBack() }
    // Always to the very end of the newest message (a long answer included): straight there when the chat opens,
    // smoothly as answers stream in.
    var opened by remember { mutableStateOf(false) }
    val lastIndex = s.messages.size - 1 + (if (s.running) 1 else 0)   // the run's status line comes last while it runs
    val currentLast by rememberUpdatedState(lastIndex)
    LaunchedEffect(s.messages.size, s.messages.lastOrNull()?.text?.length, s.running) {
        if (s.messages.isEmpty()) return@LaunchedEffect
        if (!opened) { list.scrollToItem(lastIndex, Int.MAX_VALUE); opened = true }
        else list.animateScrollToItem(lastIndex, Int.MAX_VALUE)
    }
    // When the list gets shorter or taller (the keyboard opening or closing), stay at the end if you were there.
    LaunchedEffect(list) {
        var height = 0
        var atEnd = true
        snapshotFlow { list.layoutInfo.viewportSize.height to !list.canScrollForward }.collect { (h, end) ->
            if (h != height && height != 0 && atEnd && currentLast >= 0) list.scrollToItem(currentLast, Int.MAX_VALUE) else atEnd = end
            height = h
        }
    }
    val owner = s.session?.ownerUserId == myUserId

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
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
        // The keyboard's space replaces the bottom inset already in [pad], instead of adding to it.
        Column(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize().imePadding()) {
            if (s.loading && s.session == null) { Loading(Modifier.weight(1f)); return@Column }
            LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(s.messages, key = { it.id }) { m ->
                    if (m.role == "user") UserBubble(m, mine = m.authorUserId == null || m.authorUserId == myUserId, load = viewModel::attachmentBytes,
                        onOpen = openFile?.let { open -> { a: Attachment -> viewModel.openAttachment(a, open) } })
                    else AssistantMessage(m, onAnswer = viewModel::answer, onDecide = viewModel::decide)
                }
                if (s.running) item { RunStatusLine(s.runStatus) }
            }
            s.error?.let { ErrorBanner(it, onRetry = { viewModel.load() }, modifier = Modifier.padding(12.dp)) }
            Composer(s, viewModel::onInput, viewModel::send, viewModel::stop,
                onPhoto = pickPhoto?.let { p -> { viewModel.addFile(p) } }, onFile = pickFile?.let { f -> { viewModel.addFile(f) } },
                onRemove = viewModel::removePending)
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
private fun UserBubble(m: ChatMessage, mine: Boolean, load: suspend (String) -> ByteArray, onOpen: ((Attachment) -> Unit)?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (m.kind == "steer") Text("GUIDANCE", style = MonoLabel, color = MaterialTheme.colorScheme.primary)
        m.attachments.forEach { a ->
            if (a.isImage) AttachmentImage(a, load, onOpen)
            else FileChip(a.name, sizeLabel(a.size), onClick = onOpen?.let { { it(a) } })
        }
        if (m.text.isNotEmpty()) Text(m.text, modifier = Modifier.widthIn(max = 320.dp)
            .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}

/** A sent photo, loaded from the hub; tap to open it full size in another app. */
@OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)
@Composable
private fun AttachmentImage(a: Attachment, load: suspend (String) -> ByteArray, onOpen: ((Attachment) -> Unit)?) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, a.id) {
        value = runCatching { load(a.id).decodeToImageBitmap() }.getOrNull()
    }
    val shape = RoundedCornerShape(12.dp)
    val mod = Modifier.widthIn(max = 240.dp).heightIn(max = 320.dp).clip(shape)
        .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open ${a.name}") { onOpen(a) } else Modifier)
    image?.let { Image(it, contentDescription = a.name, modifier = mod, contentScale = ContentScale.Fit) }
        ?: Box(Modifier.size(160.dp, 120.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(a.name, style = MonoLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
}

/** A file: its name and size (or state), tappable to open, with an optional remove button. */
@Composable
private fun FileChip(name: String, detail: String, onClick: (() -> Unit)? = null, onRemove: (() -> Unit)? = null, error: Boolean = false) {
    Row(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .then(if (onClick != null) Modifier.clickable(onClickLabel = "Open $name", onClick = onClick) else Modifier)
        .padding(start = 10.dp, end = if (onRemove != null) 2.dp else 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(AppIcons.File, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MonoLabel, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        onRemove?.let { IconButton(onClick = it, modifier = Modifier.size(32.dp)) { Icon(AppIcons.Close, contentDescription = "Remove $name", modifier = Modifier.size(16.dp)) } }
    }
}

private fun sizeLabel(size: Long) = when {
    size >= 1_000_000 -> "${(size / 100_000) / 10.0} MB"
    size >= 1_000 -> "${size / 1_000} KB"
    else -> "$size bytes"
}

@Composable
private fun AssistantMessage(m: ChatMessage, onAnswer: (String, String) -> Unit, onDecide: (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (m.kind == "job_result") Text("SCHEDULED JOB", style = MonoLabel, color = MaterialTheme.colorScheme.primary)
        m.tools.forEach { ToolCard(it) }
        if (m.text.isNotEmpty()) SelectionContainer { MarkdownText(if (m.kind == "job_result") unwrapJobResult(m.text) else m.text) }
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
private fun Composer(s: ChatUiState, onInput: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit,
                     onPhoto: (() -> Unit)?, onFile: (() -> Unit)?, onRemove: (Int) -> Unit) {
    var attachMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.pending.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.pending.forEach { p ->
                FileChip(p.name, when { p.error != null -> p.error; p.attachment == null -> "Adding…"; else -> sizeLabel(p.attachment.size) },
                    onRemove = { onRemove(p.localId) }, error = p.error != null)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if ((onPhoto != null || onFile != null) && !s.running) Box {
                IconButton(onClick = { attachMenu = true }, modifier = Modifier.testTag("attach")) {
                    Icon(AppIcons.Attach, contentDescription = "Add a photo or file", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(attachMenu, onDismissRequest = { attachMenu = false }) {
                    onPhoto?.let { DropdownMenuItem(text = { Text("Photo") }, onClick = { attachMenu = false; it() }) }
                    onFile?.let { DropdownMenuItem(text = { Text("File") }, onClick = { attachMenu = false; it() }) }
                }
            }
            OutlinedTextField(s.input, onInput, modifier = Modifier.weight(1f).testTag("composer"), maxLines = 5, enabled = !s.running || s.canSteer,
                shape = RoundedCornerShape(24.dp),
                placeholder = { Text(when {
                    s.running -> if (s.canSteer) "Add guidance…" else "Working…"
                    // Files added: say that the instructions go with them, in this same message.
                    s.pending.isNotEmpty() -> "What should ${s.agent?.name ?: "the agent"} do with " + (if (s.pending.size == 1) "it?" else "them?")
                    else -> "Message ${s.agent?.name ?: ""}"
                }, maxLines = 1) })
            when {
                s.running && !(s.canSteer && s.input.isNotBlank()) ->
                    FilledTonalIconButton(onClick = onStop, modifier = Modifier.testTag("stop")) { Icon(AppIcons.Stop, contentDescription = "Stop") }
                else -> FilledIconButton(onClick = onSend, modifier = Modifier.testTag("send"),
                    enabled = if (s.running) s.input.isNotBlank() else s.canSend && !s.sending && s.agent?.isAvailable != false) {
                    Icon(AppIcons.Send, contentDescription = if (s.running) "Send guidance" else "Send")
                }
            }
        }
    }
}
