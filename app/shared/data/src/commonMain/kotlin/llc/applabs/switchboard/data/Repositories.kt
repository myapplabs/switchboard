package llc.applabs.switchboard.data

import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import llc.applabs.switchboard.domain.AgentsRepository
import llc.applabs.switchboard.domain.AuthRepository
import llc.applabs.switchboard.domain.AuthState
import llc.applabs.switchboard.domain.ChatRepository
import llc.applabs.switchboard.domain.ControlRepository
import llc.applabs.switchboard.domain.model.Approval
import llc.applabs.switchboard.domain.model.JobResultOpened
import llc.applabs.switchboard.domain.model.RelaySetup
import llc.applabs.switchboard.domain.model.RelayStatus
import llc.applabs.switchboard.domain.model.AskAllItem
import kotlinx.serialization.json.JsonPrimitive
import llc.applabs.switchboard.domain.model.Job as AgentJob
import llc.applabs.switchboard.domain.model.JobInput
import llc.applabs.switchboard.domain.model.QuickCommand
import llc.applabs.switchboard.domain.MembersRepository
import llc.applabs.switchboard.domain.HubException
import llc.applabs.switchboard.domain.LiveEvents
import llc.applabs.switchboard.domain.Passkeys
import llc.applabs.switchboard.domain.model.Activity
import llc.applabs.switchboard.domain.model.Agent
import llc.applabs.switchboard.domain.model.AgentGrants
import llc.applabs.switchboard.domain.model.AgentInput
import llc.applabs.switchboard.domain.model.ChatSession
import llc.applabs.switchboard.domain.model.ConnectionTest
import llc.applabs.switchboard.domain.model.Device
import llc.applabs.switchboard.domain.model.HubInfo
import llc.applabs.switchboard.domain.model.Invite
import llc.applabs.switchboard.domain.model.InvitePreview
import llc.applabs.switchboard.domain.model.Items
import llc.applabs.switchboard.domain.model.Me
import llc.applabs.switchboard.domain.model.Member
import llc.applabs.switchboard.domain.model.Message
import llc.applabs.switchboard.domain.model.ModelOption
import llc.applabs.switchboard.domain.model.Page
import llc.applabs.switchboard.domain.model.Run
import llc.applabs.switchboard.domain.model.RunEvent
import llc.applabs.switchboard.domain.model.ShareTarget
import llc.applabs.switchboard.domain.model.UserEvent

@Serializable
private data class Ceremony(@SerialName("ceremony_id") val ceremonyId: String, val options: JsonObject)

@Serializable
private data class SessionDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    val me: Me,
)

class AuthRepositoryImpl(private val client: HubClient, private val passkeys: Passkeys) : AuthRepository {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    override val state: StateFlow<AuthState> = _state.asStateFlow()
    private val tokens get() = client.tokens

    init {
        client.onSignedOut = { _state.value = AuthState.SignedOut(tokens.hubUrl) }
    }

    override suspend fun restore() {
        val url = tokens.hubUrl
        if (url == null || tokens.accessToken == null) { _state.value = AuthState.SignedOut(url); return }
        try {
            refreshMe()
        } catch (e: HubException) {
            val cached = tokens.cachedMe?.let { runCatching { client.json.decodeFromString(Me.serializer(), it) }.getOrNull() }
            _state.value = if (e.code == HubException.UNREACHABLE && cached != null) AuthState.SignedIn(url, cached) else AuthState.SignedOut(url)
        }
    }

    override suspend fun probe(hubUrl: String): HubInfo {
        val info: HubInfo = client.call(HttpMethod.Get, "/hub", auth = false, hubUrl = hubUrl)
        if (tokens.accessToken == null) tokens.hubUrl = HubClient.normalizeHubUrl(hubUrl)   // remembered for next time
        return info
    }

    override suspend fun previewInvite(hubUrl: String, code: String): InvitePreview =
        client.call(HttpMethod.Get, "/invites/${code.trim().encodeURLParameter()}", auth = false, hubUrl = hubUrl)

    override suspend fun pairOwner(hubUrl: String, setupCode: String, displayName: String, deviceName: String) =
        register(hubUrl, buildJsonObject { put("setup_code", setupCode.trim()); put("display_name", displayName.trim()); put("device_name", deviceName) }, deviceName)

    override suspend fun join(hubUrl: String, inviteCode: String, displayName: String, deviceName: String) =
        register(hubUrl, buildJsonObject { put("invite_code", inviteCode.trim()); put("display_name", displayName.trim()); put("device_name", deviceName) }, deviceName)

    private suspend fun register(hubUrl: String, body: JsonObject, deviceName: String) {
        val c: Ceremony = client.call(HttpMethod.Post, "/auth/passkeys/register/options", body, auth = false, hubUrl = hubUrl)
        val credential = passkeys.create(c.options.toString())
        finish(hubUrl, client.call(HttpMethod.Post, "/auth/passkeys/register/verify", verifyBody(c, credential, deviceName), auth = false, hubUrl = hubUrl))
    }

    override suspend fun signInWithPasskey(hubUrl: String, deviceName: String) {
        val c: Ceremony = client.call(HttpMethod.Post, "/auth/passkeys/login/options", "{}", auth = false, hubUrl = hubUrl)
        val credential = passkeys.get(c.options.toString())
        finish(hubUrl, client.call(HttpMethod.Post, "/auth/passkeys/login/verify", verifyBody(c, credential, deviceName), auth = false, hubUrl = hubUrl))
    }

    private fun verifyBody(c: Ceremony, credentialJson: String, deviceName: String) = buildJsonObject {
        put("ceremony_id", c.ceremonyId)
        put("credential", client.json.parseToJsonElement(credentialJson).jsonObject)
        put("device_name", deviceName)
        put("platform", passkeys.platform)
    }

    private fun finish(hubUrl: String, s: SessionDto) {
        tokens.hubUrl = HubClient.normalizeHubUrl(hubUrl)
        tokens.accessToken = s.accessToken
        tokens.refreshToken = s.refreshToken
        tokens.cachedMe = client.json.encodeToString(Me.serializer(), s.me)
        _state.value = AuthState.SignedIn(tokens.hubUrl!!, s.me)
    }

    override suspend fun signInWithToken(hubUrl: String, token: String) {
        val previous = Triple(tokens.hubUrl, tokens.accessToken, tokens.refreshToken)
        tokens.hubUrl = HubClient.normalizeHubUrl(hubUrl)
        tokens.accessToken = token.trim()
        tokens.refreshToken = null
        try {
            refreshMe()
        } catch (e: Exception) {
            tokens.hubUrl = previous.first; tokens.accessToken = previous.second; tokens.refreshToken = previous.third
            throw e
        }
    }

    override suspend fun refreshMe() {
        val me: Me = client.call(HttpMethod.Get, "/me")
        tokens.cachedMe = client.json.encodeToString(Me.serializer(), me)
        _state.value = AuthState.SignedIn(tokens.hubUrl!!, me)
    }

    override suspend fun signOut() {
        runCatching { client.call<Unit>(HttpMethod.Post, "/auth/logout", "{}") }
        tokens.clearSession()
        _state.value = AuthState.SignedOut(tokens.hubUrl)
    }

    override suspend fun deleteAccount(confirmHubName: String?) {
        client.call<Unit>(HttpMethod.Delete, "/me", buildJsonObject { confirmHubName?.let { put("confirm", it) } })
        tokens.clearSession()
        _state.value = AuthState.SignedOut(tokens.hubUrl)
    }

    /** The hub revoked this account or device. */
    fun revoked() { tokens.clearSession(); _state.value = AuthState.SignedOut(tokens.hubUrl) }
}

class AgentsRepositoryImpl(private val client: HubClient, live: LiveEvents, scope: CoroutineScope) : AgentsRepository {
    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    override val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    init {
        scope.launch {
            live.events.collect { e ->
                when (e) {
                    is UserEvent.AgentStatusChanged -> _agents.update { list ->
                        list.map { if (it.id == e.agentId) it.copy(status = e.status, activity = e.activity, activeRuns = e.activeRuns, lastSeenAt = e.lastSeenAt) else it }
                    }
                    is UserEvent.AgentRemoved -> _agents.update { list -> list.filterNot { it.id == e.agentId } }
                    is UserEvent.AgentChanged, UserEvent.PermissionsChanged, UserEvent.Resync -> runCatching { refresh() }
                    else -> Unit
                }
            }
        }
    }

    override suspend fun refresh() { _agents.value = client.call<Items<Agent>>(HttpMethod.Get, "/agents").items }
    override suspend fun test(input: AgentInput): ConnectionTest = client.call(HttpMethod.Post, "/agents/test", input)
    override suspend fun add(input: AgentInput): Agent = client.call<Agent>(HttpMethod.Post, "/agents", input).also { refresh() }
    override suspend fun remove(agentId: String) { client.call<Unit>(HttpMethod.Delete, "/agents/$agentId"); refresh() }
    override suspend fun models(agentId: String): List<ModelOption> = client.call<Items<ModelOption>>(HttpMethod.Get, "/agents/$agentId/models").items
    override suspend fun grants(agentId: String): AgentGrants = client.call(HttpMethod.Get, "/agents/$agentId/grants")
    override suspend fun setGrants(agentId: String, grants: AgentGrants): AgentGrants = client.call(HttpMethod.Put, "/agents/$agentId/grants", grants)
    override suspend fun describe(agentId: String, about: String, tags: List<String>, costLevel: String?): Agent =
        client.call<Agent>(HttpMethod.Patch, "/agents/$agentId", buildJsonObject {
            put("about", about); put("tags", kotlinx.serialization.json.JsonArray(tags.map(::JsonPrimitive))); put("cost_level", costLevel ?: "")
        }).also { a -> _agents.update { list -> list.map { if (it.id == a.id) a else it } } }
    override suspend fun setCoordinator(agentId: String, on: Boolean) {
        client.call<Agent>(HttpMethod.Patch, "/agents/$agentId", buildJsonObject { put("coordinator", on) })
        refresh()
    }
}

class ChatRepositoryImpl(private val client: HubClient, private val retryDelaysMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000)) : ChatRepository {
    override suspend fun sessions(query: String?, agentId: String?): Page<ChatSession> {
        val params = listOfNotNull(query?.takeIf { it.isNotBlank() }?.let { "q=${it.encodeURLParameter()}" }, agentId?.let { "agent_id=$it" }, "limit=100")
        return client.call(HttpMethod.Get, "/sessions?" + params.joinToString("&"))
    }
    override suspend fun create(agentId: String, modelId: String?): ChatSession =
        client.call(HttpMethod.Post, "/sessions", buildJsonObject { put("agent_id", agentId); modelId?.let { put("model_id", it) } })
    override suspend fun session(id: String): ChatSession = client.call(HttpMethod.Get, "/sessions/$id")
    override suspend fun rename(id: String, title: String): ChatSession = client.call(HttpMethod.Patch, "/sessions/$id", buildJsonObject { put("title", title) })
    override suspend fun switchModel(id: String, modelId: String): ChatSession = client.call(HttpMethod.Patch, "/sessions/$id", buildJsonObject { put("model_id", modelId) })
    override suspend fun share(id: String, target: ShareTarget): ChatSession =
        client.call(HttpMethod.Patch, "/sessions/$id", buildJsonObject { put("shared_with", client.json.encodeToJsonElement(ShareTarget.serializer(), target)) })
    override suspend fun delete(id: String) = client.call<Unit>(HttpMethod.Delete, "/sessions/$id")
    override suspend fun messages(id: String): List<Message> = client.call<Page<Message>>(HttpMethod.Get, "/sessions/$id/messages?limit=100").items
    override suspend fun send(sessionId: String, text: String): Run = client.call(HttpMethod.Post, "/sessions/$sessionId/runs", buildJsonObject { put("text", text) })
    override suspend fun stop(runId: String) = client.call<Unit>(HttpMethod.Post, "/runs/$runId/stop", "{}")
    override suspend fun answer(runId: String, clarificationId: String, answer: JsonElement) =
        client.call<Unit>(HttpMethod.Post, "/runs/$runId/clarifications/$clarificationId", buildJsonObject { put("answer", answer) })
    override suspend fun steer(runId: String, text: String) = client.call<Unit>(HttpMethod.Post, "/runs/$runId/steer", buildJsonObject { put("text", text) })

    override fun events(runId: String, afterSeq: Long): Flow<RunEvent> = flow {
        var last = afterSeq
        var attempt = 0
        while (true) {
            var finished = false
            try {
                client.sse("/runs/$runId/events", last.takeIf { it > 0 }?.toString()).collect { e ->
                    if (e.event == HubClient.OPENED) return@collect
                    val event = EventParser.run(client.json, e.data) ?: return@collect
                    if (event.seq <= last) return@collect
                    last = event.seq
                    attempt = 0
                    emit(event)
                    if (event.isTerminal) finished = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HubException) {
                if (e.code != HubException.UNREACHABLE || attempt >= retryDelaysMs.size) throw e
            }
            if (finished) return@flow
            // The stream ended early (phone changed network, hub restarted): resume after the last event.
            if (attempt >= retryDelaysMs.size) throw HubException(HubException.UNREACHABLE, 0, "Connection lost", "Lost the connection to the hub during this answer.")
            delay(retryDelaysMs[attempt++])
        }
    }
}

class ControlRepositoryImpl(private val client: HubClient) : ControlRepository {
    override suspend fun approvals(): List<Approval> = client.call<Items<Approval>>(HttpMethod.Get, "/approvals").items
    override suspend fun decide(approvalId: String, decision: String): Approval =
        client.call(HttpMethod.Post, "/approvals/$approvalId/decision", buildJsonObject { put("decision", decision) })
    override suspend fun jobs(agentId: String): List<AgentJob> = client.call<Items<AgentJob>>(HttpMethod.Get, "/agents/$agentId/jobs").items
    override suspend fun createJob(agentId: String, input: JobInput): AgentJob = client.call(HttpMethod.Post, "/agents/$agentId/jobs", input)
    override suspend fun jobAction(agentId: String, jobId: String, action: String): AgentJob = client.call(HttpMethod.Post, "/agents/$agentId/jobs/$jobId/$action", "{}")
    override suspend fun deleteJob(agentId: String, jobId: String) = client.call<Unit>(HttpMethod.Delete, "/agents/$agentId/jobs/$jobId")
    override suspend fun setResultsHere(agentId: String, jobId: String, on: Boolean): AgentJob =
        client.call(HttpMethod.Patch, "/agents/$agentId/jobs/$jobId", buildJsonObject { put("results_here", on) })
    override suspend fun openJobResult(agentId: String, jobId: String, at: String?): JobResultOpened =
        client.call(HttpMethod.Post, "/agents/$agentId/jobs/$jobId/result", buildJsonObject { at?.let { put("at", it) } })
    override suspend fun relayStatus(agentId: String): RelayStatus = client.call(HttpMethod.Get, "/agents/$agentId/relay")
    override suspend fun relaySetup(agentId: String): RelaySetup {
        val s = client.call<RelaySetup>(HttpMethod.Post, "/agents/$agentId/relay/setup", "{}")
        val hub = client.tokens.hubUrl?.let { HubClient.normalizeHubUrl(it) } ?: ""
        return s.copy(command = "curl -fsSL '$hub${s.path}' | sh")
    }
    override suspend fun removeRelay(agentId: String) = client.call<Unit>(HttpMethod.Delete, "/agents/$agentId/relay")
    override suspend fun quickCommands(): List<QuickCommand> = client.call<Items<QuickCommand>>(HttpMethod.Get, "/quick-commands").items
    override suspend fun addQuickCommand(label: String, prompt: String, agentId: String?, everyone: Boolean): QuickCommand =
        client.call(HttpMethod.Post, "/quick-commands", buildJsonObject {
            put("label", label); put("prompt", prompt); agentId?.let { put("agent_id", it) }; put("scope", if (everyone) "everyone" else "personal")
        })
    override suspend fun deleteQuickCommand(id: String) = client.call<Unit>(HttpMethod.Delete, "/quick-commands/$id")
    override suspend fun askAll(agentIds: List<String>, text: String): List<AskAllItem> =
        client.call<Items<AskAllItem>>(HttpMethod.Post, "/ask-all", buildJsonObject {
            put("agent_ids", kotlinx.serialization.json.JsonArray(agentIds.map(::JsonPrimitive))); put("text", text)
        }).items
}

class MembersRepositoryImpl(private val client: HubClient) : MembersRepository {
    override suspend fun members(): List<Member> = client.call<Items<Member>>(HttpMethod.Get, "/members").items
    override suspend fun setRole(userId: String, role: String): Member = client.call(HttpMethod.Patch, "/members/$userId", buildJsonObject { put("role", role) })
    override suspend fun remove(userId: String) = client.call<Unit>(HttpMethod.Delete, "/members/$userId")
    override suspend fun invites(): List<Invite> = client.call<Items<Invite>>(HttpMethod.Get, "/invites").items
    override suspend fun invite(role: String, note: String?): Invite =
        client.call(HttpMethod.Post, "/invites", buildJsonObject { put("role", role); note?.takeIf { it.isNotBlank() }?.let { put("note", it) } })
    override suspend fun revokeInvite(id: String) = client.call<Unit>(HttpMethod.Delete, "/invites/$id")
    override suspend fun activity(userId: String?, agentId: String?): Page<Activity> {
        val params = listOfNotNull(userId?.let { "user_id=$it" }, agentId?.let { "agent_id=$it" }, "limit=100")
        return client.call(HttpMethod.Get, "/activity?" + params.joinToString("&"))
    }
    override suspend fun devices(): List<Device> = client.call<Items<Device>>(HttpMethod.Get, "/devices").items
    override suspend fun removeDevice(id: String) = client.call<Unit>(HttpMethod.Delete, "/devices/$id")
}

/** `/me/events` while signed in: reconnects with `Last-Event-ID`, backing off to 30 s. */
class LiveEventsImpl(private val client: HubClient, auth: StateFlow<AuthState>, scope: CoroutineScope) : LiveEvents {
    private val _events = MutableSharedFlow<UserEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<UserEvent> = _events.asSharedFlow()
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private var job: Job? = null
    /** Set by the container: the hub revoked this account. */
    var onRevoked: () -> Unit = {}

    init {
        scope.launch {
            auth.collect { state ->
                job?.cancel(); _connected.value = false
                if (state is AuthState.SignedIn) job = scope.launch { loop() }
            }
        }
    }

    private suspend fun loop() {
        var lastId: String? = null
        var backoff = 1_000L
        while (true) {
            try {
                client.sse("/me/events", lastId).collect { e ->
                    _connected.value = true
                    backoff = 1_000L
                    if (e.event == HubClient.OPENED) return@collect
                    e.id?.let { lastId = it }
                    val event = EventParser.user(client.json, e.data) ?: return@collect
                    if (event == UserEvent.AccountRevoked) { onRevoked(); return@collect }
                    _events.emit(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is HubException && e.status == 401) return
            }
            _connected.value = false
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }
}
