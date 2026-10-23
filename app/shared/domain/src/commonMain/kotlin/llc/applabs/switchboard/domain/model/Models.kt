package llc.applabs.switchboard.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// The hub's API types (contract: ../api/openapi.yaml, 0.2.0). Unknown fields are ignored and enums are
// kept as strings, so a newer hub never breaks an older app.

@Serializable
data class HubInfo(
    val name: String,
    @SerialName("hub_version") val hubVersion: String,
    @SerialName("api_version") val apiVersion: String,
    @SerialName("setup_required") val setupRequired: Boolean,
    @SerialName("public_url_mode") val publicUrlMode: Boolean = false,
)

object Roles {
    const val OWNER = "owner"
    const val ADMIN = "admin"
    const val MEMBER = "member"
    const val RESTRICTED = "restricted"
}

@Serializable
data class HubRef(val id: String, val name: String)

@Serializable
data class MePermissions(
    @SerialName("manage_members") val manageMembers: Boolean = false,
    @SerialName("manage_agents") val manageAgents: Boolean = false,
    @SerialName("view_activity") val viewActivity: Boolean = false,
    val approve: Boolean = false,
)

@Serializable
data class Me(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    /** The group this hub serves. */
    val hub: HubRef,
    val permissions: MePermissions,
) {
    val isOwner get() = role == Roles.OWNER
}

@Serializable
data class Capabilities(
    val chat: Boolean = false,
    val streaming: Boolean = false,
    val stop: Boolean = false,
    @SerialName("tool_events") val toolEvents: Boolean = false,
    val approvals: Boolean = false,
    val jobs: Boolean = false,
    @SerialName("model_switching") val modelSwitching: Boolean = false,
    @SerialName("per_user_memory") val perUserMemory: Boolean = false,
    val images: Boolean = false,
    val clarify: Boolean = false,
    val steer: Boolean = false,
    val reasoning: Boolean = false,
)

@Serializable
data class AgentPermissions(
    val chat: Boolean = false,
    @SerialName("manage_jobs") val manageJobs: Boolean = false,
    @SerialName("manage_agent") val manageAgent: Boolean = false,
)

@Serializable
data class AgentProfile(
    val id: String,
    val name: String,
    val port: Int? = null,
    @SerialName("key_set") val keySet: Boolean = false,
    @SerialName("key_last4") val keyLast4: String? = null,
    @SerialName("toolset_summary") val toolsetSummary: String? = null,
)

object AgentStatus {
    const val ONLINE = "online"
    const val BUSY = "busy"
    const val OFFLINE = "offline"
    const val KEY_REJECTED = "key_rejected"
}

@Serializable
data class Agent(
    val id: String,
    val type: String,
    val name: String,
    val status: String,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    val activity: String? = null,
    val host: String? = null,
    @SerialName("current_model") val currentModel: String? = null,
    @SerialName("active_runs") val activeRuns: Int = 0,
    @SerialName("job_count") val jobCount: Int? = null,
    val capabilities: Capabilities = Capabilities(),
    @SerialName("my_permissions") val myPermissions: AgentPermissions = AgentPermissions(),
    @SerialName("base_url") val baseUrl: String? = null,
    val profiles: List<AgentProfile>? = null,
    /** Specialty (contract 0.4.0): what it's good at, tags, cost level. */
    val about: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("cost_level") val costLevel: String? = null,
    /** The hub's coordinator (plans cards into sub-cards). */
    val coordinator: Boolean = false,
) {
    val isAvailable get() = status == AgentStatus.ONLINE || status == AgentStatus.BUSY
}

// ── task board (contract 0.4.0) ─────────────────────────────────────────────

object Columns {
    const val BACKLOG = "backlog"
    const val READY = "ready"
    const val WORKING = "working"
    const val NEEDS_YOU = "needs_you"
    const val REVIEW = "review"
    const val DONE = "done"
    val all = listOf(BACKLOG, READY, WORKING, NEEDS_YOU, REVIEW, DONE)
    fun label(c: String) = when (c) {
        BACKLOG -> "Backlog"; READY -> "Ready"; WORKING -> "Working"; NEEDS_YOU -> "Needs you"; REVIEW -> "Review"; DONE -> "Done"; else -> c
    }
}

@Serializable
data class TaskStepInput(@SerialName("agent_id") val agentId: String? = null, val tag: String? = null, @SerialName("model_id") val modelId: String? = null)

@Serializable
data class TaskInput(
    val title: String,
    val instructions: String = "",
    val steps: List<TaskStepInput>,
    @SerialName("review_each_step") val reviewEachStep: Boolean = false,
    val column: String = Columns.BACKLOG,
    /** Give it to the hub's coordinator (no steps). */
    val coordinated: Boolean = false,
)

@Serializable
data class PlanItem(val title: String, val instructions: String = "", @SerialName("agent_id") val agentId: String)

@Serializable
data class TaskStep(
    val id: String,
    val position: Int,
    /** work, or the coordinator's plan / combine. */
    val kind: String = "work",
    @SerialName("agent_id") val agentId: String? = null,
    val tag: String? = null,
    @SerialName("model_id") val modelId: String? = null,
    val status: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("ran_on_agent_id") val ranOnAgentId: String? = null,
    val result: String? = null,
    val note: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
)

@Serializable
data class TaskLimits(
    @SerialName("max_steps") val maxSteps: Int = 6,
    @SerialName("max_runs") val maxRuns: Int = 12,
    @SerialName("max_send_backs") val maxSendBacks: Int = 3,
    @SerialName("max_minutes") val maxMinutes: Int = 120,
)

@Serializable
data class Task(
    val id: String,
    val title: String,
    val instructions: String = "",
    val column: String,
    val reason: String? = null,
    @SerialName("reason_detail") val reasonDetail: String? = null,
    val outcome: String? = null,
    val source: String = "switchboard",
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_by_name") val createdByName: String = "",
    @SerialName("review_each_step") val reviewEachStep: Boolean = false,
    val steps: List<TaskStep> = emptyList(),
    @SerialName("runs_used") val runsUsed: Int = 0,
    @SerialName("send_backs") val sendBacks: Int = 0,
    val limits: TaskLimits = TaskLimits(),
    val position: Double = 0.0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("parent_id") val parentId: String? = null,
    val coordinated: Boolean = false,
    val plan: List<PlanItem>? = null,
    val children: List<String> = emptyList(),
) {
    /** The step running or next to run. */
    val currentStep: TaskStep? get() = steps.firstOrNull { it.status != "done" }
}

@Serializable
data class ProfileInput(
    val name: String,
    val port: Int? = null,
    @SerialName("api_key") val apiKey: String,
    @SerialName("toolset_summary") val toolsetSummary: String? = null,
)

@Serializable
data class AgentInput(
    val type: String = "hermes",
    val name: String,
    @SerialName("base_url") val baseUrl: String,
    val profiles: List<ProfileInput>,
)

@Serializable
data class Check(val name: String, val ok: Boolean, val profile: String? = null, val detail: String? = null)

@Serializable
data class ConnectionTest(val ok: Boolean, val checks: List<Check>, val capabilities: Capabilities? = null)

@Serializable
data class ShareTarget(val everyone: Boolean = false, @SerialName("user_ids") val userIds: List<String> = emptyList()) {
    val isShared get() = everyone || userIds.isNotEmpty()
}

@Serializable
data class SessionUsage(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("estimated_cost_usd") val estimatedCostUsd: Double? = null,
)

@Serializable
data class ChatSession(
    val id: String,
    @SerialName("agent_id") val agentId: String,
    val title: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("model_label") val modelLabel: String? = null,
    @SerialName("last_message_preview") val lastMessagePreview: String? = null,
    @SerialName("active_run_id") val activeRunId: String? = null,
    @SerialName("shared_with") val sharedWith: ShareTarget = ShareTarget(),
    val usage: SessionUsage? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ToolCall(
    val id: String,
    val tool: String,
    val preview: String? = null,
    val state: String,
    @SerialName("duration_s") val durationS: Double? = null,
    @SerialName("output_excerpt") val outputExcerpt: String? = null,
)

@Serializable
data class Clarification(
    val id: String,
    val question: String,
    val choices: List<String> = emptyList(),
    @SerialName("multi_select") val multiSelect: Boolean = false,
    val status: String,
    val answer: JsonElement? = null,
    @SerialName("answered_by") val answeredBy: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class Message(
    val id: String,
    val role: String,
    val kind: String = "message",
    @SerialName("author_user_id") val authorUserId: String? = null,
    val text: String = "",
    @SerialName("run_id") val runId: String? = null,
    val status: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall> = emptyList(),
    val clarifications: List<Clarification> = emptyList(),
    @SerialName("model_label") val modelLabel: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class Page<T>(val items: List<T>, @SerialName("next_cursor") val nextCursor: String? = null)

@Serializable
data class Items<T>(val items: List<T>)

@Serializable
data class Run(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("agent_id") val agentId: String,
    val status: String,
    @SerialName("model_label") val modelLabel: String? = null,
    @SerialName("message_id") val messageId: String,
    val output: String? = null,
    val error: String? = null,
    @SerialName("last_event_id") val lastEventId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("finished_at") val finishedAt: String? = null,
)

@Serializable
data class ModelOption(
    val id: String,
    val label: String,
    @SerialName("is_paid") val isPaid: Boolean,
    @SerialName("is_local") val isLocal: Boolean = false,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("daily_limit") val dailyLimit: Int? = null,
    @SerialName("used_today") val usedToday: Int? = null,
)

@Serializable
data class Member(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    @SerialName("last_active_at") val lastActiveAt: String? = null,
    @SerialName("joined_at") val joinedAt: String? = null,
)

@Serializable
data class Invite(
    val id: String,
    val role: String,
    val note: String? = null,
    @SerialName("created_by") val createdBy: String,
    @SerialName("expires_at") val expiresAt: String,
    val code: String? = null,
    val url: String? = null,
)

@Serializable
data class InvitePreview(
    @SerialName("hub_name") val hubName: String,
    @SerialName("invited_by") val invitedBy: String,
    val role: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class Activity(
    val id: String,
    val at: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("model_label") val modelLabel: String? = null,
    val action: String,
    val detail: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class Device(
    val id: String,
    val platform: String,
    val name: String,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    @SerialName("this_device") val thisDevice: Boolean = false,
    @SerialName("push_enabled") val pushEnabled: Boolean = false,
    @SerialName("notify_prefs") val notifyPrefs: Map<String, Boolean> = emptyMap(),
)

/** Notification kinds (the hub's notify_prefs keys). */
object NotifyKinds {
    const val APPROVAL = "approval_needed"
    const val QUESTION = "question_asked"
    const val RUN_FINISHED = "run_finished"
    const val JOB_FAILED = "job_failed"
    const val MEMBER_JOINED = "member_joined"
    const val TASK_NEEDS_YOU = "task_needs_you"
    const val TASK_REVIEW = "task_review"
    val all = listOf(APPROVAL, QUESTION, RUN_FINISHED, JOB_FAILED, MEMBER_JOINED, TASK_NEEDS_YOU, TASK_REVIEW)
}

@Serializable
data class ModelGrant(@SerialName("model_id") val modelId: String, @SerialName("daily_limit") val dailyLimit: Int? = null)

@Serializable
data class Grant(
    val role: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("can_chat") val canChat: Boolean,
    @SerialName("can_manage_jobs") val canManageJobs: Boolean = false,
    @SerialName("daily_run_limit") val dailyRunLimit: Int? = null,
    val models: List<ModelGrant> = emptyList(),
)

@Serializable
data class AgentGrants(val grants: List<Grant>)

@Serializable
data class Approval(
    val id: String,
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("agent_id") val agentId: String,
    @SerialName("requested_by") val requestedBy: String,
    val command: String,
    val description: String? = null,
    val status: String,
    @SerialName("allowed_decisions") val allowedDecisions: List<String> = emptyList(),
    val decision: String? = null,
    @SerialName("decided_by") val decidedBy: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class Job(
    val id: String,
    @SerialName("agent_id") val agentId: String,
    val name: String,
    val prompt: String? = null,
    val schedule: String,
    val state: String,
    val repeat: Int? = null,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("last_result") val lastResult: String? = null,
    @SerialName("next_run_at") val nextRunAt: String? = null,
    /** Contract 0.4.0: the failure message of the last run, and the runs the hub has recorded (newest first). */
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("recent_runs") val recentRuns: List<JobRun> = emptyList(),
)

@Serializable
data class JobRun(val at: String, val result: String? = null, val summary: String? = null)

@Serializable
data class JobInput(val name: String, val schedule: String, val prompt: String, val repeat: Int? = null)

@Serializable
data class AskAllProblem(val code: String, val title: String = "", val detail: String? = null)

@Serializable
data class AskAllItem(
    @SerialName("agent_id") val agentId: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    val problem: AskAllProblem? = null,
)

@Serializable
data class QuickCommand(
    val id: String,
    val label: String,
    val prompt: String,
    @SerialName("agent_id") val agentId: String? = null,
    val scope: String,
)

/** One event of a run's stream (contract events.md → Run events). [seq] resumes the stream. */
sealed interface RunEvent {
    val seq: Long
    data class Started(override val seq: Long, val messageId: String, val modelLabel: String?) : RunEvent
    data class Delta(override val seq: Long, val text: String) : RunEvent
    data class ToolStarted(override val seq: Long, val id: String, val tool: String, val preview: String?) : RunEvent
    data class ToolCompleted(override val seq: Long, val id: String, val tool: String, val state: String, val durationS: Double?, val excerpt: String?) : RunEvent
    data class Reasoning(override val seq: Long, val text: String) : RunEvent
    data class ClarifyRequested(override val seq: Long, val clarification: Clarification, val canAnswer: Boolean) : RunEvent
    data class ClarifyAnswered(override val seq: Long, val clarificationId: String, val outcome: String, val answer: JsonElement?) : RunEvent
    data class SteerAccepted(override val seq: Long, val text: String, val by: String) : RunEvent
    data class ApprovalRequested(override val seq: Long, val approvalId: String, val command: String, val description: String?,
                                 val allowedDecisions: List<String>, val canDecide: Boolean) : RunEvent
    data class ApprovalDecided(override val seq: Long, val approvalId: String, val decision: String, val decidedBy: String) : RunEvent
    data class Status(override val seq: Long, val status: String) : RunEvent
    data class Completed(override val seq: Long, val output: String) : RunEvent
    data class Failed(override val seq: Long, val error: String, val code: String) : RunEvent
    data class Cancelled(override val seq: Long, val by: String?) : RunEvent
    data class Other(override val seq: Long, val type: String) : RunEvent

    val isTerminal get() = this is Completed || this is Failed || this is Cancelled
}

/** What changes live for the signed-in user (contract events.md → User events). */
sealed interface UserEvent {
    data class AgentStatusChanged(val agentId: String, val status: String, val activity: String?, val activeRuns: Int, val lastSeenAt: String?) : UserEvent
    data class AgentChanged(val agentId: String) : UserEvent
    data class AgentRemoved(val agentId: String) : UserEvent
    data class SessionUpdated(val sessionId: String) : UserEvent
    data class SessionRemoved(val sessionId: String) : UserEvent
    data class RunFinished(val runId: String, val sessionId: String, val status: String) : UserEvent
    data class ClarifyRequested(val runId: String, val sessionId: String, val question: String) : UserEvent
    data class MemberJoined(val userId: String, val displayName: String) : UserEvent
    data class ApprovalRequested(val approvalId: String, val runId: String, val sessionId: String, val command: String) : UserEvent
    data class ApprovalDecided(val approvalId: String, val decision: String) : UserEvent
    data class JobUpdated(val agentId: String, val jobId: String) : UserEvent
    data class TaskChanged(val taskId: String, val column: String) : UserEvent
    data class TaskRemoved(val taskId: String) : UserEvent
    data object PermissionsChanged : UserEvent
    data object AccountRevoked : UserEvent
    data object Resync : UserEvent
}

// ── FORGE on the board (contract 0.4.0 /forge) ─────────────────────────────

@Serializable
data class ForgeCard(
    val id: String,
    val project: String,
    val key: String,
    val title: String,
    /** FORGE's own status: draft, todo, in_progress, in_review, done. */
    val status: String,
    val column: String,
    val milestone: String? = null,
    val grade: String? = null,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("waiting_for") val waitingFor: String? = null,
    val phase: String? = null,
)

@Serializable
data class ForgeBoard(val connected: Boolean, val cards: List<ForgeCard> = emptyList(), val problem: String? = null)

@Serializable
data class ForgeBridge(
    val id: String, val name: String, val connected: Boolean, val paired: Boolean = false,
    @SerialName("last_seen_at") val lastSeenAt: String? = null, @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ForgeBridgeCreated(val bridge: ForgeBridge, val code: String, @SerialName("code_expires_at") val codeExpiresAt: String)

/** forge_start_ticket's answer: a preview (started false) or the started run. */
@Serializable
data class ForgeStart(
    val started: Boolean = false,
    @SerialName("spec_verdict") val specVerdict: String? = null,
    @SerialName("spec_score") val specScore: Double? = null,
    /** FORGE's cost range from history: {low, typical, high, basis, …}; null without enough history. */
    @SerialName("estimate_usd") val estimate: ForgeEstimate? = null,
    @SerialName("run_id") val runId: String? = null,
)

@Serializable
data class ForgeEstimate(val low: Double? = null, val typical: Double? = null, val high: Double? = null, val basis: String? = null)

/** forge_run's answer, the parts the app shows. */
@Serializable
data class ForgeRun(
    @SerialName("run_id") val runId: String,
    val title: String = "",
    val phase: String = "",
    @SerialName("waiting_for") val waitingFor: String? = null,
    @SerialName("cost_usd") val costUsd: Double? = null,
    val review: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("intervention_reason") val interventionReason: String? = null,
)

