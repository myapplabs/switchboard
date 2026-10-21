package llc.applabs.switchboard.ui.sessions

import androidx.compose.foundation.background
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import llc.applabs.design.tokens.MonoLabel
import llc.applabs.switchboard.domain.AgentsRepository
import llc.applabs.switchboard.domain.ChatRepository
import llc.applabs.switchboard.domain.LiveEvents
import llc.applabs.switchboard.domain.model.ChatSession
import llc.applabs.switchboard.domain.model.UserEvent
import llc.applabs.switchboard.ui.common.EmptyNote
import llc.applabs.switchboard.ui.common.ErrorBanner
import llc.applabs.switchboard.ui.common.friendly
import llc.applabs.switchboard.ui.common.shortTime

data class SessionsUiState(val query: String = "", val sessions: List<ChatSession> = emptyList(), val loading: Boolean = true, val error: String? = null)

/** Own and shared conversations, newest first, searchable (spec: Sessions). */
@OptIn(FlowPreview::class)
class SessionsViewModel(private val chat: ChatRepository, live: LiveEvents) : ViewModel() {
    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state

    init {
        viewModelScope.launch { _state.map { it.query }.distinctUntilChanged().debounce(250).collect { load() } }
        viewModelScope.launch {
            live.events.collect { if (it is UserEvent.SessionUpdated || it is UserEvent.SessionRemoved || it is UserEvent.Resync) load() }
        }
    }

    fun query(q: String) = _state.update { it.copy(query = q) }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try { _state.update { it.copy(sessions = chat.sessions(it.query).items) } }
        catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
        _state.update { it.copy(loading = false) }
    }

    fun delete(id: String) = viewModelScope.launch {
        _state.update { s -> s.copy(sessions = s.sessions.filterNot { it.id == id }) }
        try { chat.delete(id) } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) }; load() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(viewModel: SessionsViewModel, agents: AgentsRepository, myUserId: String, onOpen: (String) -> Unit) {
    val s by viewModel.state.collectAsState()
    val agentList by agents.agents.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Conversations") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(s.query, viewModel::query, placeholder = { Text("Search titles and messages") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            s.error?.let { ErrorBanner(it, onRetry = { viewModel.load() }, modifier = Modifier.padding(horizontal = 16.dp)) }
            PullToRefreshBox(isRefreshing = s.loading && s.sessions.isNotEmpty(), onRefresh = { viewModel.load() }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (!s.loading && s.sessions.isEmpty()) item {
                        EmptyNote(if (s.query.isBlank()) "No conversations yet" else "Nothing found",
                            if (s.query.isBlank()) "Tap an agent on the Agents tab to start one." else "Try other words.")
                    }
                    items(s.sessions, key = { it.id }) { c ->
                        val agentName = agentList.firstOrNull { it.id == c.agentId }?.name ?: "agent"
                        if (c.ownerUserId == myUserId) {
                            val dismiss = rememberSwipeToDismissBoxState()
                            if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) viewModel.delete(c.id)
                            SwipeToDismissBox(dismiss, enableDismissFromStartToEnd = false, backgroundContent = {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                                    Text("Delete", color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }) { SessionRow(c, agentName, onOpen) }
                        } else SessionRow(c, agentName, onOpen)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(c: ChatSession, agentName: String, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable { onOpen(c.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(shortTime(c.updatedAt), style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(agentName.uppercase(), style = MonoLabel, color = MaterialTheme.colorScheme.primary)
            if (c.sharedWith.isShared) Text("SHARED", style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c.activeRunId != null) Text("WORKING", style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        c.lastMessagePreview?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}
