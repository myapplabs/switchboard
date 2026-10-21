package llc.applabs.switchboard.hub

import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// API shapes — names and fields exactly as contract/openapi.yaml (snake_case on the wire).

val hubJson = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }

fun now(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

private val idRandom = SecureRandom()
private const val ID_CHARS = "0123456789abcdefghjkmnpqrstvwxyz"

/** Sortable-ish id: prefix + time (ms, base32) + 10 random chars, e.g. `agt_01j…`. */
fun newId(prefix: String): String {
    val t = java.lang.Long.toString(System.currentTimeMillis(), 32).padStart(9, '0')
    val r = (1..10).map { ID_CHARS[idRandom.nextInt(ID_CHARS.length)] }.joinToString("")
    return "${prefix}_$t$r"
}

enum class Role { owner, admin, member, restricted }

@Serializable
data class HubInfo(
    val name: String,
    @SerialName("hub_version") val hubVersion: String,
    @SerialName("api_version") val apiVersion: String,
    @SerialName("setup_required") val setupRequired: Boolean,
    @SerialName("public_url_mode") val publicUrlMode: Boolean = false,
)

@Serializable
data class HubRef(val id: String, val name: String)

@Serializable
data class MePermissions(
    @SerialName("manage_members") val manageMembers: Boolean,
    @SerialName("manage_agents") val manageAgents: Boolean,
    @SerialName("view_activity") val viewActivity: Boolean,
    val approve: Boolean,
)

@Serializable
data class Me(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    /** The group this hub serves (contract 0.3.0 `hub`; was `household`). */
    val hub: HubRef,
    val permissions: MePermissions,
)

/** What the agent's adapter supports; the app shows only these features. */
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
    val chat: Boolean,
    @SerialName("manage_jobs") val manageJobs: Boolean = false,
    @SerialName("manage_agent") val manageAgent: Boolean = false,
)

@Serializable
data class ProfileOut(
    val id: String,
    val name: String,
    val port: Int? = null,
    @SerialName("key_set") val keySet: Boolean,
    @SerialName("key_last4") val keyLast4: String? = null,
    @SerialName("toolset_summary") val toolsetSummary: String? = null,
)

@Serializable
data class AgentOut(
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
    val capabilities: Capabilities,
    @SerialName("my_permissions") val myPermissions: AgentPermissions,
    /** Specialty (contract 0.4.0): what the agent is good at, its tags and cost level. */
    val about: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("cost_level") val costLevel: String? = null,
    /** The hub's coordinator agent (0.4.0; at most one; the owner sets it). */
    val coordinator: Boolean = false,
    /** Owner only. */
    @SerialName("base_url") val baseUrl: String? = null,
    /** Owner only; keys are never returned. */
    val profiles: List<ProfileOut>? = null,
)

@Serializable
data class ProfileInput(
    val name: String,
    val port: Int? = null,
    @SerialName("api_key") val apiKey: String,
    @SerialName("toolset_summary") val toolsetSummary: String? = null,
)

@Serializable
data class AgentInput(
    val type: String,
    val name: String,
    @SerialName("base_url") val baseUrl: String,
    val profiles: List<ProfileInput>,
)

@Serializable
data class ProfilePatch(
    val id: String? = null,
    val name: String,
    val port: Int? = null,
    @SerialName("api_key") val apiKey: String? = null,
    @SerialName("toolset_summary") val toolsetSummary: String? = null,
    val remove: Boolean = false,
)

@Serializable
data class AgentPatch(
    val name: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
    val profiles: List<ProfilePatch>? = null,
    /** Specialty fields (owner or admin; no connection test). An empty cost_level clears it. */
    val about: String? = null,
    val tags: List<String>? = null,
    @SerialName("cost_level") val costLevel: String? = null,
    /** Owner only: make this the hub's coordinator (true) or not (false). */
    val coordinator: Boolean? = null,
)

@Serializable
data class Check(val name: String, val ok: Boolean, val profile: String? = null, val detail: String? = null)

@Serializable
data class ConnectionTest(val ok: Boolean, val checks: List<Check>, val capabilities: Capabilities? = null)

@Serializable
data class Items<T>(val items: List<T>)

@Serializable
data class Tokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

/** Sign-in result (contract Session: Tokens + me). */
@Serializable
data class Session(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    val me: Me,
) {
    constructor(t: Tokens, me: Me) : this(t.accessToken, t.refreshToken, t.expiresIn, me)
}

@Serializable
data class RefreshIn(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class RegisterOptionsIn(
    @SerialName("setup_code") val setupCode: String? = null,
    @SerialName("invite_code") val inviteCode: String? = null,
    @SerialName("display_name") val displayName: String,
    @SerialName("device_name") val deviceName: String? = null,
)

@Serializable
data class PasskeyCeremony(@SerialName("ceremony_id") val ceremonyId: String, val options: kotlinx.serialization.json.JsonObject)

@Serializable
data class PasskeyResult(
    @SerialName("ceremony_id") val ceremonyId: String,
    val credential: kotlinx.serialization.json.JsonObject,
    @SerialName("device_name") val deviceName: String? = null,
    /** ios | android (contract 0.2.0), shown in the device list. */
    val platform: String? = null,
)

// ── conversations and runs (contract 0.2.0) ─────────────────────────────────

@Serializable
data class SessionUsage(
    @SerialName("input_tokens") val inputTokens: Long,
    @SerialName("output_tokens") val outputTokens: Long,
    @SerialName("estimated_cost_usd") val estimatedCostUsd: Double? = null,
)

@Serializable
data class ChatSessionOut(
    val id: String,
    @SerialName("agent_id") val agentId: String,
    val title: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    @SerialName("model_id") val modelId: String?,
    @SerialName("model_label") val modelLabel: String?,
    @SerialName("last_message_preview") val lastMessagePreview: String?,
    @SerialName("active_run_id") val activeRunId: String?,
    @SerialName("shared_with") val sharedWith: ShareOut,
    val usage: SessionUsage?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class SessionCreate(
    @SerialName("agent_id") val agentId: String,
    val title: String? = null,
    @SerialName("model_id") val modelId: String? = null,
)

@Serializable
data class ShareIn(val everyone: Boolean? = null, @SerialName("user_ids") val userIds: List<String>? = null)

@Serializable
data class ShareOut(val everyone: Boolean, @SerialName("user_ids") val userIds: List<String>)

@Serializable
data class SessionPatch(
    val title: String? = null,
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("shared_with") val sharedWith: ShareIn? = null,
)

@Serializable
data class ToolCallOut(
    val id: String,
    val tool: String,
    val preview: String?,
    val state: String,
    @SerialName("duration_s") val durationS: Double?,
    @SerialName("output_excerpt") val outputExcerpt: String?,
)

@Serializable
data class ClarificationOut(
    val id: String,
    val question: String,
    val choices: List<String>,
    @SerialName("multi_select") val multiSelect: Boolean,
    val status: String,
    val answer: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("answered_by") val answeredBy: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class MessageOut(
    val id: String,
    val role: String,
    val kind: String,
    @SerialName("author_user_id") val authorUserId: String?,
    val text: String,
    @SerialName("run_id") val runId: String?,
    val status: String?,
    @SerialName("tool_calls") val toolCalls: List<ToolCallOut>,
    val clarifications: List<ClarificationOut>,
    @SerialName("model_label") val modelLabel: String?,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class Page<T>(val items: List<T>, @SerialName("next_cursor") val nextCursor: String? = null)

@Serializable
data class RunInputIn(
    val text: String,
    @SerialName("model_id") val modelId: String? = null,
)

@Serializable
data class RunOut(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("agent_id") val agentId: String,
    val status: String,
    @SerialName("model_label") val modelLabel: String?,
    @SerialName("message_id") val messageId: String,
    val output: String?,
    val error: String?,
    val usage: kotlinx.serialization.json.JsonObject?,
    @SerialName("last_event_id") val lastEventId: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("finished_at") val finishedAt: String?,
)

@Serializable
data class SteerIn(val text: String)

@Serializable
data class AskAllIn(@SerialName("agent_ids") val agentIds: List<String>, val text: String)

@Serializable
data class AskAllItem(
    @SerialName("agent_id") val agentId: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    val problem: Problem? = null,
)

@Serializable
data class AnswerIn(val answer: kotlinx.serialization.json.JsonElement)

@Serializable
data class ModelOut(
    val id: String,
    val label: String,
    @SerialName("is_paid") val isPaid: Boolean,
    @SerialName("is_local") val isLocal: Boolean,
    @SerialName("is_default") val isDefault: Boolean,
    @SerialName("daily_limit") val dailyLimit: Int? = null,
    @SerialName("used_today") val usedToday: Int? = null,
)
