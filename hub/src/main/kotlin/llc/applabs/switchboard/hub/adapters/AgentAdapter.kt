package llc.applabs.switchboard.hub.adapters

import llc.applabs.switchboard.hub.Capabilities
import llc.applabs.switchboard.hub.ConnectionTest

/** One agent profile the hub can call: its URL (with the profile's port) and API key. */
data class ProfileTarget(val name: String, val url: String, val apiKey: String)

/** Result of one health probe. */
sealed interface Health {
    /** Answering. `activeRuns` from the agent's own runtime report, when it gives one. */
    data class Up(val activeRuns: Int, val activity: String?) : Health
    /** Not answering (refused, timed out, DNS, 5xx). `reason` is for the owner. */
    data class Down(val reason: String) : Health
    /** Answering, but rejecting the key (401/403) — the owner must fix it; polling stops. */
    data object KeyRejected : Health
}

/**
 * The hub's view of one agent type (product spec: AgentAdapter). The app never sees agent APIs; the
 * hub's API stays agent-neutral and the app branches only on [Capabilities].
 *
 * M0 covers connection, health and capabilities; runs, approvals and jobs arrive with M1/M3.
 */
interface AgentAdapter {
    val type: String

    /** Liveness and activity (no key needed where the agent allows it). */
    suspend fun health(target: ProfileTarget): Health

    /** Is the key still accepted? Called periodically by the poller. */
    suspend fun keyAccepted(target: ProfileTarget): Boolean?

    /** Full check of every profile before an agent is added or changed, with a reason per check. */
    suspend fun test(targets: List<ProfileTarget>): ConnectionTest

    /** Capabilities as last discovered (from [test]). */
    suspend fun capabilities(target: ProfileTarget): Capabilities?
}

// ── runs (M1) ────────────────────────────────────────────────────────────────

/** One turn for the agent. [history] only when the agent has no session to continue (hybrid fallback). */
data class RunRequest(
    val input: String,
    /** The conversation's own session on the agent (continued when the agent keeps sessions). */
    val agentSessionId: String,
    /** Per-user memory scope (Hermes `X-Hermes-Session-Key`). */
    val userScope: String,
    val history: List<HistoryMessage>? = null,
    /** The conversation's model, when the agent switches models per run. */
    val model: String? = null,
    /** Same value for a retried request, so the agent does not start the run twice. */
    val idempotencyKey: String,
)

data class HistoryMessage(val role: String, val content: String)

/** What an agent says during a run, already in the hub's terms; `seq` is the agent's own counter. */
sealed interface AgentEvent {
    val seq: Long
    val at: String?
    data class Delta(override val seq: Long, override val at: String?, val text: String) : AgentEvent
    data class ToolStarted(override val seq: Long, override val at: String?, val toolCallId: String?, val tool: String, val preview: String?) : AgentEvent
    data class ToolCompleted(override val seq: Long, override val at: String?, val toolCallId: String?, val tool: String, val error: Boolean, val durationS: Double?, val excerpt: String?) : AgentEvent
    data class Reasoning(override val seq: Long, override val at: String?, val text: String) : AgentEvent
    data class Clarify(override val seq: Long, override val at: String?, val requestId: String, val generation: Int?, val question: String,
                       val choices: List<String>, val multiSelect: Boolean, val timeoutS: Long?) : AgentEvent
    /** The agent wants permission for [command]; [choices] are the agent's (once / session / always / deny). */
    data class ApprovalRequested(override val seq: Long, override val at: String?, val requestId: String?, val generation: Int?,
                                 val command: String, val description: String?, val choices: List<String>, val timeoutS: Long?) : AgentEvent
    /** Decided on the agent's side (by the hub, another client, or a timeout). */
    data class ApprovalResolved(override val seq: Long, override val at: String?, val requestId: String?, val choice: String?) : AgentEvent
    data class Completed(override val seq: Long, override val at: String?, val output: String?, val usage: Usage?) : AgentEvent
    data class Failed(override val seq: Long, override val at: String?, val error: String) : AgentEvent
    data class Cancelled(override val seq: Long, override val at: String?) : AgentEvent
    /** Anything else (logged, otherwise ignored — new agent events must not break the hub). */
    data class Other(override val seq: Long, override val at: String?, val name: String) : AgentEvent
}

data class Usage(val inputTokens: Long, val outputTokens: Long)

/** The agent's view of a run, for reconnecting and recovery. */
data class AgentRunStatus(val state: String, val output: String?, val error: String?, val usage: Usage?) {
    val finished get() = state in setOf("completed", "failed", "cancelled")
}

/** The agent's own session behind a conversation. */
data class AgentSessionInfo(val exists: Boolean, val title: String? = null, val estimatedCostUsd: Double? = null)

/** The agent refused to start a run, with a contract error code (`agent_busy`, `agent_key_rejected`, …). */
class AgentRunException(val code: String, message: String, val retryAfterS: Int? = null) : Exception(message)

/** Run operations; an adapter without chat support never gets these calls (capabilities.chat). */
interface AgentRuns {
    /** Start a turn; returns the agent's run id. */
    suspend fun startRun(target: ProfileTarget, request: RunRequest): String
    /** The run's events, from the start (the caller skips `seq`s it has seen). Ends after the terminal event. */
    fun events(target: ProfileTarget, agentRunId: String): kotlinx.coroutines.flow.Flow<AgentEvent>
    suspend fun runStatus(target: ProfileTarget, agentRunId: String): AgentRunStatus?
    suspend fun stop(target: ProfileTarget, agentRunId: String)
    suspend fun answer(target: ProfileTarget, agentRunId: String, requestId: String, generation: Int?, answer: kotlinx.serialization.json.JsonElement)
    suspend fun steer(target: ProfileTarget, agentRunId: String, text: String): Boolean
    /** Send an approval decision: once / session / always / deny. Throws AgentRunException when no longer pending. */
    suspend fun decide(target: ProfileTarget, agentRunId: String, requestId: String?, generation: Int?, choice: String)
    /** The conversation's agent session; `exists = false` also when the agent keeps no sessions. */
    suspend fun session(target: ProfileTarget, agentSessionId: String, userScope: String): AgentSessionInfo
    /** Models the agent can run (its current one first); null when it can't say. */
    suspend fun models(target: ProfileTarget): List<AgentModel>?

    /** Delete the agent's session; true when it is gone (deleted or never existed). */
    suspend fun deleteSession(target: ProfileTarget, agentSessionId: String, userScope: String): Boolean
}

/** A model an agent can run. [id] is what the hub sends back as the run's model (`provider::model` for Hermes). */
data class AgentModel(val id: String, val label: String, val isPaid: Boolean, val isLocal: Boolean, val isDefault: Boolean)

// ── jobs (M3) ────────────────────────────────────────────────────────────────

/** A scheduled job as the agent reports it, in the hub's terms. */
data class AgentJob(
    val id: String, val name: String, val prompt: String?, val schedule: String, val state: String,
    val repeat: Int?, val lastRunAt: String?, val lastResult: String?, val nextRunAt: String?,
    /** The agent's message for the last failed run, if it keeps one. */
    val lastError: String? = null,
    /** Where the agent sends results (Hermes `deliver`: "local", "telegram", "relay:<id>", comma-separated). */
    val deliver: String? = null,
)

data class JobFields(val name: String? = null, val schedule: String? = null, val prompt: String? = null, val repeat: Int? = null, val deliver: String? = null)

interface AgentJobs {
    suspend fun jobs(target: ProfileTarget): List<AgentJob>
    suspend fun createJob(target: ProfileTarget, fields: JobFields): AgentJob
    suspend fun updateJob(target: ProfileTarget, id: String, fields: JobFields): AgentJob
    suspend fun deleteJob(target: ProfileTarget, id: String)
    /** pause / resume / run */
    suspend fun jobAction(target: ProfileTarget, id: String, action: String): AgentJob
}
