package llc.applabs.switchboard.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import llc.applabs.switchboard.domain.model.Activity
import llc.applabs.switchboard.domain.model.Approval
import llc.applabs.switchboard.domain.model.Task
import llc.applabs.switchboard.domain.model.TaskInput
import llc.applabs.switchboard.domain.model.TaskStepInput
import llc.applabs.switchboard.domain.model.AskAllItem
import llc.applabs.switchboard.domain.model.Job
import llc.applabs.switchboard.domain.model.JobInput
import llc.applabs.switchboard.domain.model.QuickCommand
import llc.applabs.switchboard.domain.model.Agent
import llc.applabs.switchboard.domain.model.AgentGrants
import llc.applabs.switchboard.domain.model.AgentInput
import llc.applabs.switchboard.domain.model.ChatSession
import llc.applabs.switchboard.domain.model.ConnectionTest
import llc.applabs.switchboard.domain.model.Device
import llc.applabs.switchboard.domain.model.HubInfo
import llc.applabs.switchboard.domain.model.Invite
import llc.applabs.switchboard.domain.model.InvitePreview
import llc.applabs.switchboard.domain.model.Me
import llc.applabs.switchboard.domain.model.Member
import llc.applabs.switchboard.domain.model.Message
import llc.applabs.switchboard.domain.model.ModelOption
import llc.applabs.switchboard.domain.model.Page
import llc.applabs.switchboard.domain.model.Run
import llc.applabs.switchboard.domain.model.RunEvent
import llc.applabs.switchboard.domain.model.ShareTarget
import llc.applabs.switchboard.domain.model.UserEvent

/** A hub error with the contract's stable [code] (`agent_offline`, `forbidden`, …) and a message safe to show. */
class HubException(
    val code: String,
    val status: Int,
    val title: String,
    val detail: String?,
    val retryAfterS: Int? = null,
) : Exception(detail ?: title) {
    /** What to show the person. */
    val friendly: String get() = detail ?: title

    companion object {
        const val UNREACHABLE = "hub_unreachable"
    }
}

sealed interface AuthState {
    data object Loading : AuthState
    /** Not signed in; [hubUrl] is the hub last used, if any. */
    data class SignedOut(val hubUrl: String?) : AuthState
    data class SignedIn(val hubUrl: String, val me: Me) : AuthState
}

/** Connecting to a hub and the person's account on it. */
interface AuthRepository {
    val state: StateFlow<AuthState>
    /** Restore the saved session at start-up. */
    suspend fun restore()
    /** What a hub address answers (name, whether the owner still has to pair). */
    suspend fun probe(hubUrl: String): HubInfo
    suspend fun previewInvite(hubUrl: String, code: String): InvitePreview
    /** First run (or another device): the setup code printed on the hub machine, then a passkey. */
    suspend fun pairOwner(hubUrl: String, setupCode: String, displayName: String, deviceName: String)
    /** A member joining with an invite, then a passkey. */
    suspend fun join(hubUrl: String, inviteCode: String, displayName: String, deviceName: String)
    suspend fun signInWithPasskey(hubUrl: String, deviceName: String)
    /** Debug builds only: sign in with a token from `switchboard-hub dev-token` (passkeys need the published domain). */
    suspend fun signInWithToken(hubUrl: String, token: String)
    suspend fun refreshMe()
    suspend fun signOut()
}

interface AgentsRepository {
    val agents: StateFlow<List<Agent>>
    suspend fun refresh()
    suspend fun test(input: AgentInput): ConnectionTest
    suspend fun add(input: AgentInput): Agent
    suspend fun remove(agentId: String)
    suspend fun models(agentId: String): List<ModelOption>
    suspend fun grants(agentId: String): AgentGrants
    suspend fun setGrants(agentId: String, grants: AgentGrants): AgentGrants
    /** Describe what an agent is good at (owner or admin). */
    suspend fun describe(agentId: String, about: String, tags: List<String>, costLevel: String?): Agent
    /** Owner: make this agent the hub's coordinator (or not). */
    suspend fun setCoordinator(agentId: String, on: Boolean)
}

/** FORGE tickets on the board, through the person's own bridge (FORGE stays the system of record). */
interface ForgeRepository {
    val board: StateFlow<llc.applabs.switchboard.domain.model.ForgeBoard>
    suspend fun refresh(fresh: Boolean = false)
    suspend fun bridges(): List<llc.applabs.switchboard.domain.model.ForgeBridge>
    suspend fun connect(name: String): llc.applabs.switchboard.domain.model.ForgeBridgeCreated
    suspend fun revoke(id: String)
    suspend fun start(project: String, ticket: String, confirmed: Boolean): llc.applabs.switchboard.domain.model.ForgeStart
    suspend fun run(runId: String): llc.applabs.switchboard.domain.model.ForgeRun
    suspend fun decide(runId: String, decision: String, note: String?)
}

/** The task board (M5): cards with steps on agents; only people end a card (review). */
interface TasksRepository {
    val tasks: StateFlow<List<Task>>
    suspend fun refresh()
    suspend fun task(id: String): Task
    suspend fun create(input: TaskInput): Task
    suspend fun update(id: String, title: String?, instructions: String?, steps: List<TaskStepInput>?): Task
    suspend fun move(id: String, column: String): Task
    /** decision: accept, send_back (with a note) or close. */
    suspend fun review(id: String, decision: String, note: String? = null, step: Int? = null): Task
    suspend fun delete(id: String)
}

interface ChatRepository {
    suspend fun sessions(query: String? = null, agentId: String? = null): Page<ChatSession>
    suspend fun create(agentId: String, modelId: String? = null): ChatSession
    suspend fun session(id: String): ChatSession
    suspend fun rename(id: String, title: String): ChatSession
    suspend fun switchModel(id: String, modelId: String): ChatSession
    suspend fun share(id: String, target: ShareTarget): ChatSession
    suspend fun delete(id: String)
    suspend fun messages(id: String): List<Message>
    suspend fun send(sessionId: String, text: String): Run
    /** The run's events from [afterSeq], resuming by itself if the connection drops; ends with the terminal event. */
    fun events(runId: String, afterSeq: Long = 0): Flow<RunEvent>
    suspend fun stop(runId: String)
    suspend fun answer(runId: String, clarificationId: String, answer: JsonElement)
    suspend fun steer(runId: String, text: String)
}

/** Push notifications: register this device (relay + hub) and per-kind preferences. */
interface PushRepository {
    /** Register with the relay and the hub; [token] from the platform (or the current one when null). False when push is unavailable. */
    suspend fun register(token: String? = null): Boolean
    suspend fun setPreference(kind: String, on: Boolean): Device?
}

/** M3: approvals, scheduled jobs, quick commands. */
interface ControlRepository {
    suspend fun approvals(): List<Approval>
    suspend fun decide(approvalId: String, decision: String): Approval
    suspend fun jobs(agentId: String): List<Job>
    suspend fun createJob(agentId: String, input: JobInput): Job
    suspend fun jobAction(agentId: String, jobId: String, action: String): Job
    suspend fun deleteJob(agentId: String, jobId: String)
    suspend fun quickCommands(): List<QuickCommand>
    suspend fun addQuickCommand(label: String, prompt: String, agentId: String?, everyone: Boolean): QuickCommand
    suspend fun deleteQuickCommand(id: String)
    suspend fun askAll(agentIds: List<String>, text: String): List<AskAllItem>
}

interface MembersRepository {
    suspend fun members(): List<Member>
    suspend fun setRole(userId: String, role: String): Member
    suspend fun remove(userId: String)
    suspend fun invites(): List<Invite>
    suspend fun invite(role: String, note: String?): Invite
    suspend fun revokeInvite(id: String)
    suspend fun activity(userId: String? = null, agentId: String? = null): Page<Activity>
    suspend fun devices(): List<Device>
    suspend fun removeDevice(id: String)
}

/** The signed-in person's live channel (`/me/events`), connected while signed in. */
interface LiveEvents {
    val events: SharedFlow<UserEvent>
    /** True while the hub's stream is connected (the header's connectivity dot). */
    val connected: StateFlow<Boolean>
}
