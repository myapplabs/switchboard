package llc.applabs.switchboard.ui.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import llc.applabs.switchboard.domain.AgentsRepository
import llc.applabs.switchboard.domain.MembersRepository
import llc.applabs.switchboard.domain.LiveEvents
import llc.applabs.switchboard.domain.model.Activity
import llc.applabs.switchboard.domain.model.Agent
import llc.applabs.switchboard.domain.model.AgentGrants
import llc.applabs.switchboard.domain.model.Grant
import llc.applabs.switchboard.domain.model.Invite
import llc.applabs.switchboard.domain.model.Member
import llc.applabs.switchboard.domain.model.Roles
import llc.applabs.switchboard.domain.model.UserEvent
import llc.applabs.switchboard.ui.common.friendly

data class MembersUiState(
    val members: List<Member> = emptyList(),
    val invites: List<Invite> = emptyList(),
    val created: Invite? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** Members (owner and admins): people, roles, invites. */
class MembersViewModel(private val members: MembersRepository, live: LiveEvents) : ViewModel() {
    private val _state = MutableStateFlow(MembersUiState())
    val state: StateFlow<MembersUiState> = _state

    init {
        load()
        viewModelScope.launch { live.events.collect { if (it is UserEvent.MemberJoined) load() } }
    }

    fun load() = act {
        _state.update { it.copy(members = members.members(), invites = members.invites()) }
    }

    fun setRole(userId: String, role: String) = act { members.setRole(userId, role); load() }
    fun remove(userId: String) = act { members.remove(userId); load() }
    fun invite(role: String, note: String) = act { _state.update { it.copy(created = members.invite(role, note)) }; load() }
    fun revoke(id: String) = act { members.revokeInvite(id); load() }
    fun dismissCreated() = _state.update { it.copy(created = null) }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try { block() } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
        _state.update { it.copy(loading = false) }
    }
}

/** One role's access to one agent, as the editor shows it. */
data class RoleAccess(val role: String, val canChat: Boolean, val profileId: String?, val dailyLimit: String)

data class AccessUiState(
    val agents: List<Agent> = emptyList(),
    val agentId: String? = null,
    val rows: List<RoleAccess> = emptyList(),
    /** Grants for single people: kept as they are when the role rows are saved. */
    val personal: List<Grant> = emptyList(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

/** Agent access (owner): per agent, which roles may chat, on which profile (tool level), with a daily limit. */
class AgentAccessViewModel(private val agents: AgentsRepository) : ViewModel() {
    private val _state = MutableStateFlow(AccessUiState())
    val state: StateFlow<AccessUiState> = _state

    init {
        viewModelScope.launch {
            runCatching { agents.refresh() }
            val list = agents.agents.value
            _state.update { it.copy(agents = list) }
            list.firstOrNull()?.let { select(it.id) }
        }
    }

    fun select(agentId: String) = viewModelScope.launch {
        _state.update { it.copy(agentId = agentId, error = null, saved = false) }
        try {
            val g = agents.grants(agentId).grants
            _state.update { s ->
                s.copy(personal = g.filter { it.userId != null }, rows = ROLES.map { role ->
                    val r = g.firstOrNull { it.role == role }
                    RoleAccess(role, canChat = r?.canChat ?: (role == Roles.ADMIN), profileId = r?.profileId, dailyLimit = r?.dailyRunLimit?.toString().orEmpty())
                })
            }
        } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
    }

    fun edit(row: RoleAccess) = _state.update { s -> s.copy(saved = false, rows = s.rows.map { if (it.role == row.role) row else it }) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        val id = s.agentId ?: return@launch
        _state.update { it.copy(saving = true, error = null) }
        try {
            val grants = s.rows.map { Grant(role = it.role, profileId = it.profileId, canChat = it.canChat, dailyRunLimit = it.dailyLimit.toIntOrNull()) } + s.personal
            agents.setGrants(id, AgentGrants(grants))
            _state.update { it.copy(saved = true) }
        } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
        _state.update { it.copy(saving = false) }
    }

    companion object { val ROLES = listOf(Roles.ADMIN, Roles.MEMBER, Roles.RESTRICTED) }
}

data class ActivityUiState(val items: List<Activity> = emptyList(), val names: Map<String, String> = emptyMap(), val loading: Boolean = true, val error: String? = null)

/** The activity log (metadata only: who used which agent and model, changes to the members). */
class ActivityViewModel(private val members: MembersRepository, private val agents: AgentsRepository) : ViewModel() {
    private val _state = MutableStateFlow(ActivityUiState())
    val state: StateFlow<ActivityUiState> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val names = members.members().associate { it.userId to it.displayName } + agents.agents.value.associate { it.id to it.name }
            _state.update { it.copy(items = members.activity().items, names = names) }
        } catch (e: Exception) { _state.update { it.copy(error = e.friendly()) } }
        _state.update { it.copy(loading = false) }
    }
}
