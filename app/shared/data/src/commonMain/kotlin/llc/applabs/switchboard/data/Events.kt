package llc.applabs.switchboard.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import llc.applabs.switchboard.domain.model.Clarification
import llc.applabs.switchboard.domain.model.RunEvent
import llc.applabs.switchboard.domain.model.UserEvent

/** Event payloads (contract events.md) → domain events. Unknown types are kept as `Other` / dropped. */
object EventParser {
    private fun JsonObject.s(k: String): String? = this[k]?.takeUnless { it is JsonNull }?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
    private fun JsonObject.l(k: String): Long? = this[k]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }

    fun run(json: Json, data: String): RunEvent? {
        val o = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        val seq = o.l("seq") ?: return null
        return when (o.s("type")) {
            "run.started" -> RunEvent.Started(seq, o.s("message_id") ?: "", o.s("model_label"))
            "message.delta" -> RunEvent.Delta(seq, o.s("delta") ?: "")
            "tool.started" -> RunEvent.ToolStarted(seq, o.s("tool_call_id") ?: "tc_$seq", o.s("tool") ?: "tool", o.s("preview"))
            "tool.completed" -> RunEvent.ToolCompleted(seq, o.s("tool_call_id") ?: "tc_$seq", o.s("tool") ?: "tool", o.s("state") ?: "done",
                o["duration_s"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }, o.s("output_excerpt"))
            "reasoning" -> RunEvent.Reasoning(seq, o.s("text") ?: "")
            "clarify.requested" -> RunEvent.ClarifyRequested(seq, Clarification(
                id = o.s("clarification_id") ?: "", question = o.s("question") ?: "",
                choices = (o["choices"] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList(),
                multiSelect = o["multi_select"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true,
                status = "pending", expiresAt = o.s("expires_at"),
            ), canAnswer = o["can_answer"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } != false)
            "clarify.answered" -> RunEvent.ClarifyAnswered(seq, o.s("clarification_id") ?: "", o.s("outcome") ?: "answered", o["answer"])
            "steer.accepted" -> RunEvent.SteerAccepted(seq, o.s("text") ?: "", o.s("by") ?: "")
            "approval.requested" -> RunEvent.ApprovalRequested(seq, o.s("approval_id") ?: "", o.s("command") ?: "", o.s("description"),
                (o["allowed_decisions"] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList(),
                o["can_decide"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true)
            "approval.decided" -> RunEvent.ApprovalDecided(seq, o.s("approval_id") ?: "", o.s("decision") ?: "", o.s("decided_by") ?: "")
            "run.status" -> RunEvent.Status(seq, o.s("status") ?: "running")
            "run.completed" -> RunEvent.Completed(seq, o.s("output") ?: "")
            "run.failed" -> RunEvent.Failed(seq, o.s("error") ?: "The answer failed.", o.s("code") ?: "hub_error")
            "run.cancelled" -> RunEvent.Cancelled(seq, o.s("by"))
            else -> RunEvent.Other(seq, o.s("type") ?: "")
        }
    }

    fun user(json: Json, data: String): UserEvent? {
        val o = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        return when (o.s("type")) {
            "agent.status" -> UserEvent.AgentStatusChanged(o.s("agent_id") ?: return null, o.s("status") ?: "offline", o.s("activity"),
                o["active_runs"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: 0, o.s("last_seen_at"))
            "agent.changed" -> UserEvent.AgentChanged(o.s("agent_id") ?: return null)
            "agent.removed" -> UserEvent.AgentRemoved(o.s("agent_id") ?: return null)
            "session.updated" -> UserEvent.SessionUpdated(o.s("session_id") ?: return null)
            "session.removed" -> UserEvent.SessionRemoved(o.s("session_id") ?: return null)
            "run.finished" -> UserEvent.RunFinished(o.s("run_id") ?: return null, o.s("session_id") ?: "", o.s("status") ?: "")
            "clarify.requested" -> UserEvent.ClarifyRequested(o.s("run_id") ?: return null, o.s("session_id") ?: "", o.s("question") ?: "")
            "member.joined" -> UserEvent.MemberJoined(o.s("user_id") ?: return null, o.s("display_name") ?: "")
            "approval.requested" -> UserEvent.ApprovalRequested(o.s("approval_id") ?: return null, o.s("run_id") ?: "", o.s("session_id") ?: "", o.s("command") ?: "")
            "approval.decided" -> UserEvent.ApprovalDecided(o.s("approval_id") ?: return null, o.s("decision") ?: "")
            "job.updated" -> UserEvent.JobUpdated(o.s("agent_id") ?: return null, o.s("job_id") ?: "")
            "task.changed" -> UserEvent.TaskChanged(o.s("task_id") ?: return null, o.s("column") ?: "")
            "task.removed" -> UserEvent.TaskRemoved(o.s("task_id") ?: return null)
            "permissions.changed" -> UserEvent.PermissionsChanged
            "account.revoked" -> UserEvent.AccountRevoked
            "resync" -> UserEvent.Resync
            else -> null
        }
    }
}
