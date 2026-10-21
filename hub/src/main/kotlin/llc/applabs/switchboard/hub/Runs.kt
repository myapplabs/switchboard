package llc.applabs.switchboard.hub

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import llc.applabs.switchboard.hub.adapters.AgentEvent
import llc.applabs.switchboard.hub.adapters.AgentRunException
import llc.applabs.switchboard.hub.adapters.AgentRuns
import llc.applabs.switchboard.hub.adapters.HistoryMessage
import llc.applabs.switchboard.hub.adapters.ProfileTarget
import llc.applabs.switchboard.hub.adapters.RunRequest
import llc.applabs.switchboard.hub.adapters.Usage
import org.slf4j.LoggerFactory

/** One event of a run's stream as the app gets it (contract events.md → Run events). */
data class RunEvt(val seq: Long, val type: String, val json: String)

/**
 * Runs: one agent turn each (contract `/sessions/{id}/runs`, `/runs/{id}…`).
 *
 * Starting a run stores the user's message, starts the turn on the agent (continuing the
 * conversation's agent session, or sending the history when the agent has none), then a relay reads
 * the agent's stream: each event is translated, stored (`run_event`, the reply text, tool calls,
 * questions) and pushed to the phones watching. Phones reconnect with `Last-Event-ID` and get the rest
 * from the store. If the agent's stream drops, the relay asks the agent for the run's status and
 * reconnects, skipping events it has already seen; after a hub restart, [recover] resumes open runs.
 */
class Runs(
    private val db: Db,
    private val conversations: Conversations,
    private val agents: Agents,
    private val userEvents: UserEvents,
    private val permissions: Permissions,
    private val accounts: Accounts,
    private val approvals: Approvals,
    private val scope: CoroutineScope,
    private val retryDelaysMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000, 30_000, 30_000),
    private val historyBudgetChars: Int = 60_000,
) {
    private val log = LoggerFactory.getLogger(Runs::class.java)
    private val live = ConcurrentHashMap<String, MutableSharedFlow<RunEvt>>()
    private val seqs = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val starting = ConcurrentHashMap<String, Mutex>()
    private val relays = ConcurrentHashMap<String, Job>()
    /** Per run: the relay and the controls (answer, steer, stop) take turns, so an agent event that
     *  follows an answer is never handled before the answer is recorded. */
    private val turns = ConcurrentHashMap<String, Mutex>()
    /** Push notices (set by the hub). */
    var notifier: Notifier? = null
    /** Run events for the task board (set by the hub): run id and event type, after the event is stored. */
    var onEvent: ((runId: String, type: String) -> Unit)? = null
    private fun turn(runId: String) = turns.computeIfAbsent(runId) { Mutex() }

    // ── start ───────────────────────────────────────────────────────────────

    suspend fun start(user: HubUser, sessionId: String, input: RunInputIn): RunOut {
        val c = conversations.get(user, sessionId)
        val text = input.text.trim()
        if (text.isEmpty() || text.length > 100_000) throw HubException.invalid("text: 1–100000 characters")
        val agent = agents.record(c.agentId) ?: throw HubException.notFound("agent")
        val runsApi = agents.adapterFor(agent) as? AgentRuns ?: throw notSupported()
        when (agent.status) {
            "offline" -> throw HubException(HttpStatusCode.ServiceUnavailable, "agent_offline", "Agent offline",
                "${agent.name} does not answer right now. Try again when it is back online.")
            "key_rejected" -> throw HubException(HttpStatusCode.ServiceUnavailable, "agent_key_rejected", "Agent key rejected",
                "${agent.name} rejects the hub's key. The owner needs to update it.")
        }
        val modelId = input.modelId?.trim()?.takeIf { it.isNotEmpty() } ?: c.modelId
        if (input.modelId != null && !agent.capabilities.modelSwitching) throw HubException(HttpStatusCode.UnprocessableEntity,
            "not_supported", "Model switching not supported", "This agent runs one model.")
        // Permissions: the sender's access and limits; in a shared conversation also the least capable
        // profile and the models every participant may use (sharing never escalates).
        val own = permissions.checkRun(user, agent, modelId)
        val shared = with(conversations) { c.isShared }
        val access = if (!shared) own else permissions.sharedAccess(conversations.audience(c), agent)
            ?: throw HubException.forbidden("Someone in this conversation no longer has access to ${agent.name}.")
        if (modelId != null && access.models != null && modelId !in access.models)
            throw HubException.forbidden("Not everyone in this conversation may use that model.")
        val target = agents.targetFor(agent, access.profileId) ?: throw notSupported()
        val agentInput = if (shared) "[${user.displayName}]: $text" else text

        return starting.computeIfAbsent(sessionId) { Mutex() }.withLock {
            if (conversations.activeRunId(sessionId) != null) throw HubException(HttpStatusCode.Conflict, "run_active",
                "A run is active", "Wait for the current answer, or stop it, before sending another message.")
            val runId = newId("run")
            val history = historyIfNeeded(runsApi, target, c)
            val agentRunId = try {
                runsApi.startRun(target, RunRequest(agentInput, c.agentSessionId, conversations.memoryScope(c), history,
                    modelId?.takeIf { agent.capabilities.modelSwitching }, runId))
            } catch (e: AgentRunException) {
                throw when (e.code) {
                    "agent_busy" -> HubException(HttpStatusCode.TooManyRequests, "agent_busy", "Agent busy", e.message, retryAfterS = e.retryAfterS)
                    "agent_offline", "agent_key_rejected" -> HubException(HttpStatusCode.ServiceUnavailable, e.code, "Agent unavailable", e.message)
                    else -> HubException(HttpStatusCode.BadGateway, "agent_error", "The agent refused the message", e.message)
                }
            }
            val t = now()
            val userMsg = newId("msg")
            val replyMsg = newId("msg")
            val label = modelId?.let(Conversations::modelLabel)
            db.tx { conn ->
                fun exec(sql: String, vararg a: Any?) = conn.prepareStatement(sql).use { Db.bind(it, a); it.executeUpdate() }
                exec("INSERT INTO message (id, hub_id, session_id, role, kind, author_user_id, text, created_at) VALUES (?, ?, ?, 'user', 'message', ?, ?, ?)",
                    userMsg, c.hubId, c.id, user.userId, text, t)
                exec("INSERT INTO message (id, hub_id, session_id, role, kind, text, run_id, status, model_label, created_at) VALUES (?, ?, ?, 'assistant', 'message', '', ?, 'partial', ?, ?)",
                    replyMsg, c.hubId, c.id, runId, label, Instant.parse(t).plusMillis(1).toString())
                exec("""INSERT INTO run (id, hub_id, session_id, agent_id, user_id, agent_run_id, status, message_id, model_label, model_id, profile_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'running', ?, ?, ?, ?, ?)""", runId, c.hubId, c.id, c.agentId, user.userId, agentRunId, replyMsg, label,
                    modelId, access.profileId, t)
                exec("UPDATE chat_session SET last_message_preview = ?, updated_at = ? WHERE id = ?", text.take(200), t, c.id)
            }
            emit(runId, "run.started", mapOf("session_id" to c.id, "agent_id" to c.agentId, "model_label" to label, "message_id" to replyMsg))
            // Metadata only (spec: owners see who used which agent and model, never the content).
            accounts.activity(c.hubId, user.userId, c.agentId, "run_started",
                """{"model_label":${label?.let { "\"$it\"" } ?: "null"},"shared":$shared}""")
            conversations.changed(c.id)
            relay(runId)
            getRun(runId)!!
        }
    }

    /** The hybrid fallback: history only when the agent has no session to continue. */
    private suspend fun historyIfNeeded(runsApi: AgentRuns, target: ProfileTarget, c: ConversationRecord): List<HistoryMessage>? {
        val earlier = db.query("SELECT COUNT(*) FROM message WHERE session_id = ? AND role = 'assistant' AND text != ''", c.id) { it.getInt(1) }.single()
        if (earlier == 0) return null
        if (runsApi.session(target, c.agentSessionId, conversations.memoryScope(c)).exists) return null
        log.info("agent has no session {}: sending history from the hub", c.agentSessionId)
        val shared = with(conversations) { c.isShared }
        val names = mutableMapOf<String, String>()
        val all = db.query("SELECT role, text, author_user_id FROM message WHERE session_id = ? AND kind != 'steer' AND text != '' ORDER BY created_at DESC, rowid DESC", c.id) {
            val author = it.str("author_user_id")
            val content = if (shared && it.getString(1) == "user" && author != null)
                "[${names.getOrPut(author) { accounts.user(author)?.displayName ?: "Someone" }}]: ${it.getString(2)}" else it.getString(2)
            HistoryMessage(it.getString(1), content)
        }
        var used = 0
        return all.takeWhile { m -> (used + m.content.length <= historyBudgetChars).also { if (it) used += m.content.length } }.reversed()
    }

    // ── relay ───────────────────────────────────────────────────────────────

    private class RelayState(val runId: String, val messageId: String, val agentRunId: String, var lastSeq: Long,
                             val text: StringBuilder, var toolPosition: Int = 0)

    private fun relay(runId: String) {
        relays[runId] = scope.launch {
            try { relayLoop(runId) } catch (e: Exception) {
                log.error("relay for {} failed", runId, e)
                finish(runId, "interrupted", error = "The hub lost this run (${e::class.simpleName}).", code = "run_interrupted")
            } finally { relays.remove(runId) }
        }
    }

    private suspend fun relayLoop(runId: String) {
        val row = runRow(runId) ?: return
        val agent = agents.record(row.agentId)
        val runsApi = agent?.let { agents.adapterFor(it) as? AgentRuns }
        val target = agent?.let { agents.targetFor(it, row.profileId) }
        if (runsApi == null || target == null || row.agentRunId == null) {
            finish(runId, "interrupted", error = "The agent is no longer available.", code = "run_interrupted"); return
        }
        val state = RelayState(runId, row.messageId, row.agentRunId, row.agentLastSeq,
            StringBuilder(db.query("SELECT text FROM message WHERE id = ?", row.messageId) { it.getString(1) }.single()),
            toolPosition = db.query("SELECT COUNT(*) FROM tool_call WHERE run_id = ?", runId) { it.getInt(1) }.single())
        var attempt = 0
        while (true) {
            try {
                runsApi.events(target, state.agentRunId).collect { e ->
                    if (e.seq >= 0 && e.seq <= state.lastSeq) return@collect   // seen before a reconnect
                    turn(runId).withLock {
                        handle(state, e, target, runsApi)
                        if (e.seq >= 0) state.lastSeq = e.seq
                        // The reply so far and the position in the agent's stream are saved together, so a
                        // restart resumes exactly here (no lost or repeated text).
                        db.tx { c ->
                            c.prepareStatement("UPDATE run SET agent_last_seq = ? WHERE id = ?").use { Db.bind(it, arrayOf<Any?>(state.lastSeq, runId)); it.executeUpdate() }
                            c.prepareStatement("UPDATE message SET text = ? WHERE id = ? AND status = 'partial'").use { Db.bind(it, arrayOf<Any?>(state.text.toString(), state.messageId)); it.executeUpdate() }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.info("run {}: agent stream dropped ({}: {})", runId, e::class.simpleName, e.message)
            }
            if (isFinished(runId)) return
            // The stream ended without an end event: has the run finished on the agent?
            val status = runCatching { runsApi.runStatus(target, state.agentRunId) }.getOrNull()
            if (status?.finished == true) {
                flushText(state)
                when (status.state) {
                    "completed" -> finish(runId, "completed", output = state.text.toString().ifEmpty { status.output ?: "" }, usage = status.usage)
                    "cancelled" -> finish(runId, "cancelled")
                    else -> finish(runId, "failed", error = status.error ?: "The agent reported a failure.", code = "agent_error")
                }
                return
            }
            if (attempt >= retryDelaysMs.size) {
                flushText(state)
                finish(runId, "interrupted", error = "Lost contact with the agent during this answer.", code = "run_interrupted"); return
            }
            delay(retryDelaysMs[attempt++])
        }
    }

    private suspend fun handle(s: RelayState, e: AgentEvent, target: ProfileTarget, runsApi: AgentRuns) {
        when (e) {
            is AgentEvent.Delta -> {
                val delta = if (s.text.isEmpty()) e.text.trimStart() else e.text
                if (delta.isEmpty()) return
                s.text.append(delta)
                emit(s.runId, "message.delta", mapOf("delta" to delta))
            }
            is AgentEvent.ToolStarted -> {
                flushText(s)
                val id = e.toolCallId ?: "tc_${e.seq}"
                db.update("INSERT OR IGNORE INTO tool_call (id, run_id, message_id, tool, preview, state, position) VALUES (?, ?, ?, ?, ?, 'running', ?)",
                    id, s.runId, s.messageId, e.tool, e.preview, s.toolPosition++)
                emit(s.runId, "tool.started", mapOf("tool_call_id" to id, "tool" to e.tool, "preview" to e.preview))
            }
            is AgentEvent.ToolCompleted -> {
                val id = e.toolCallId ?: db.query("SELECT id FROM tool_call WHERE run_id = ? AND tool = ? AND state = 'running' ORDER BY position DESC LIMIT 1",
                    s.runId, e.tool) { it.getString(1) }.firstOrNull() ?: "tc_${e.seq}"
                val state = if (e.error) "error" else "done"
                db.update("UPDATE tool_call SET state = ?, duration_s = ?, output_excerpt = COALESCE(?, output_excerpt) WHERE run_id = ? AND id = ?",
                    state, e.durationS, e.excerpt, s.runId, id)
                emit(s.runId, "tool.completed", mapOf("tool_call_id" to id, "tool" to e.tool, "state" to state,
                    "duration_s" to e.durationS, "output_excerpt" to e.excerpt))
            }
            is AgentEvent.Reasoning -> if (e.text.isNotBlank()) emit(s.runId, "reasoning", mapOf("text" to e.text))
            is AgentEvent.Clarify -> {
                flushText(s)
                val id = newId("clr")
                val expires = e.timeoutS?.let { Instant.now().plusSeconds(it).toString() }
                db.update("""INSERT INTO clarification (id, run_id, message_id, agent_request_id, execution_generation, question, choices_json, multi_select, status, expires_at, created_at)
                             VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)""",
                    id, s.runId, s.messageId, e.requestId, e.generation, e.question,
                    buildJsonArray { e.choices.forEach { add(JsonPrimitive(it)) } }.toString(), e.multiSelect, expires, now())
                setStatus(s.runId, "waiting_for_input")
                emit(s.runId, "clarify.requested", mapOf("clarification_id" to id, "question" to e.question,
                    "choices" to buildJsonArray { e.choices.forEach { add(JsonPrimitive(it)) } }, "multi_select" to e.multiSelect,
                    "expires_at" to expires, "can_answer" to true))
                runRow(s.runId)?.let { r ->
                    userEvents.publish(listOf(r.userId), "clarify.requested", mapOf("clarification_id" to id, "agent_id" to r.agentId,
                        "session_id" to r.sessionId, "run_id" to s.runId, "question" to e.question))
                    notifier?.notify(listOf(r.userId), "question_asked", r.hubId,
                        mapOf("session_id" to r.sessionId, "run_id" to s.runId, "agent_id" to r.agentId))
                }
            }
            is AgentEvent.ApprovalRequested -> {
                flushText(s)
                val r = runRow(s.runId) ?: return
                val a = approvals.create(r.hubId, s.runId, r.sessionId, r.agentId, r.userId, e.requestId, e.generation,
                    e.command, e.description, e.choices, e.timeoutS?.let { Instant.now().plusSeconds(it).toString() })
                setStatus(s.runId, "waiting_for_approval")
                // Stored once with the requester's view; [personal] adjusts it for each viewer.
                val requester = accounts.user(r.userId)
                emit(s.runId, "approval.requested", mapOf("approval_id" to a.id, "command" to a.command, "description" to a.description,
                    "allowed_decisions" to jsonList(requester?.let { approvals.allowed(it, a) }.orEmpty()),
                    "can_decide" to (requester?.let { approvals.canDecide(it, a) } == true)))
                userEvents.publish(approvals.deciders(a), "approval.requested", mapOf("approval_id" to a.id, "agent_id" to a.agentId,
                    "session_id" to a.sessionId, "run_id" to a.runId, "requested_by" to a.requestedBy, "command" to a.command))
                notifier?.notify(approvals.deciders(a), "approval_needed", r.hubId,
                    mapOf("session_id" to a.sessionId, "run_id" to a.runId, "approval_id" to a.id, "agent_id" to a.agentId))
            }
            is AgentEvent.ApprovalResolved -> {
                // Decided on the agent's side: by us (already recorded), another client, or a timeout.
                val a = approvals.pendingFor(s.runId, e.requestId) ?: return
                val decision = Approvals.decision(e.choice)
                if (approvals.record(a.id, if (decision == "deny") "denied" else "approved", decision, "agent")) {
                    emit(s.runId, "approval.decided", mapOf("approval_id" to a.id, "decision" to decision, "decided_by" to "agent"))
                    userEvents.publish(approvals.deciders(a), "approval.decided", mapOf("approval_id" to a.id, "decision" to decision, "decided_by" to "agent"))
                    setStatus(s.runId, "running")
                }
            }
            is AgentEvent.Completed -> { flushText(s); finish(s.runId, "completed", output = s.text.toString().ifEmpty { e.output?.trim() ?: "" }, usage = e.usage) }
            is AgentEvent.Failed -> { flushText(s); finish(s.runId, "failed", error = e.error, code = "agent_error") }
            is AgentEvent.Cancelled -> { flushText(s); finish(s.runId, "cancelled") }
            is AgentEvent.Other -> log.debug("run {}: ignoring agent event {}", s.runId, e.name)
        }
    }

    private fun flushText(s: RelayState) {
        db.update("UPDATE message SET text = ? WHERE id = ?", s.text.toString(), s.messageId)
    }

    private fun setStatus(runId: String, status: String) {
        db.update("UPDATE run SET status = ? WHERE id = ?", status, runId)
        emit(runId, "run.status", mapOf("status" to status))
    }

    /** End a run once: the terminal event, the stored reply, the conversation's preview and usage. */
    private fun finish(runId: String, status: String, output: String? = null, error: String? = null, code: String? = null, usage: Usage? = null) {
        val r = runRow(runId) ?: return
        val usageJson = usage?.let { buildJsonObject { put("input_tokens", it.inputTokens); put("output_tokens", it.outputTokens) } }
        val updated = db.update("""UPDATE run SET status = ?, output = ?, error = ?, usage_json = ?, finished_at = ?
                                   WHERE id = ? AND status NOT IN ('completed', 'failed', 'cancelled', 'interrupted')""",
            status, output, error, usageJson?.toString(), now(), runId)
        if (updated == 0) return
        val text = db.query("SELECT text FROM message WHERE id = ?", r.messageId) { it.getString(1) }.single()
        val messageStatus = when (status) {
            "completed" -> "complete"
            "cancelled" -> "cancelled"
            else -> if (text.isNotEmpty()) "partial" else "failed"
        }
        db.update("UPDATE message SET status = ?, text = ? WHERE id = ?", messageStatus, if (status == "completed" && output != null) output else text, r.messageId)
        db.update("UPDATE clarification SET status = 'cancelled' WHERE run_id = ? AND status = 'pending'", runId)
        approvals.expireRun(runId).forEach { a ->
            userEvents.publish(approvals.deciders(a), "approval.decided", mapOf("approval_id" to a.id, "decision" to "deny", "decided_by" to "expired"))
        }
        when (status) {
            "completed" -> emit(runId, "run.completed", mapOf("output" to (output ?: text), "usage" to usageJson))
            "cancelled" -> emit(runId, "run.cancelled", mapOf("by" to r.stoppedBy))
            else -> emit(runId, "run.failed", mapOf("error" to (error ?: "The run failed."), "code" to (code ?: "agent_error")))
        }
        val preview = (output ?: text).take(200).ifEmpty { null }
        db.update("""UPDATE chat_session SET last_message_preview = COALESCE(?, last_message_preview), updated_at = ?,
                     input_tokens = input_tokens + ?, output_tokens = output_tokens + ? WHERE id = ?""",
            preview, now(), usage?.inputTokens ?: 0, usage?.outputTokens ?: 0, r.sessionId)
        val audience = conversations.record(r.sessionId)?.let { c -> conversations.audience(c).map { it.userId } } ?: listOf(r.userId)
        // Push only when it was worth waiting for (or failed): quick answers arrive while the app is open.
        val took = runCatching { java.time.Duration.between(Instant.parse(db.query("SELECT created_at FROM run WHERE id = ?", runId) { it.getString(1) }.single()), Instant.now()).seconds }.getOrDefault(0)
        if (took >= LONG_RUN_S || status != "completed")
            notifier?.notify(audience, "run_finished", r.hubId, mapOf("session_id" to r.sessionId, "run_id" to runId, "agent_id" to r.agentId))
        userEvents.publish(audience, "run.finished", mapOf("run_id" to runId, "session_id" to r.sessionId, "agent_id" to r.agentId, "status" to status))
        scope.launch { afterRun(r.sessionId) }
    }

    /** Title and cost from the agent's session (best effort), else a title from the first message. */
    private suspend fun afterRun(sessionId: String) {
        val c = conversations.record(sessionId) ?: return
        val agent = agents.record(c.agentId)
        val info = runCatching {
            val runsApi = agent?.let { agents.adapterFor(it) as? AgentRuns }
            val target = agent?.let { agents.primaryTarget(it) }
            if (runsApi != null && target != null) runsApi.session(target, c.agentSessionId, conversations.memoryScope(c)) else null
        }.getOrNull()
        if (c.titleIsDefault) {
            val title = info?.title?.takeIf { it.isNotBlank() }?.take(120)
                ?: db.query("SELECT text FROM message WHERE session_id = ? AND role = 'user' ORDER BY created_at LIMIT 1", sessionId) { it.getString(1) }
                    .firstOrNull()?.lineSequence()?.first()?.take(60)
            if (title != null) db.update("UPDATE chat_session SET title = ?, title_is_default = 0 WHERE id = ? AND title_is_default = 1", title, sessionId)
        }
        info?.estimatedCostUsd?.let { db.update("UPDATE chat_session SET estimated_cost_usd = ? WHERE id = ?", it, sessionId) }
        conversations.changed(sessionId)
    }

    // ── stream ──────────────────────────────────────────────────────────────

    fun emit(runId: String, type: String, fields: Map<String, Any?>) {
        val lock = locks.computeIfAbsent(runId) { Any() }
        val evt = synchronized(lock) {
            val seq = seqs.compute(runId) { _, v -> (v ?: db.query("SELECT COALESCE(MAX(seq), 0) FROM run_event WHERE run_id = ?", runId) { it.getLong(1) }.single()) + 1 }!!
            val payload = buildJsonObject {
                put("type", type); put("run_id", runId); put("seq", seq); put("at", now())
                fields.forEach { (k, v) -> put(k, toJson(v)) }
            }.toString()
            db.update("INSERT INTO run_event (run_id, seq, type, payload_json) VALUES (?, ?, ?, ?)", runId, seq, type, payload)
            RunEvt(seq, type, payload)
        }
        live.computeIfAbsent(runId) { MutableSharedFlow(extraBufferCapacity = 4096) }.tryEmit(evt)
        onEvent?.invoke(runId, type)
        if (type in TERMINAL) scope.launch { delay(60_000); live.remove(runId); seqs.remove(runId); locks.remove(runId); turns.remove(runId) }
    }

    /** The run's events after [lastEventId], then live ones, ending after the terminal event. */
    fun subscribe(user: HubUser, runId: String, lastEventId: Long?): Flow<RunEvt> {
        val r = access(user, runId)
        val after = lastEventId ?: 0
        val maxSeq = db.query("SELECT COALESCE(MAX(seq), 0) FROM run_event WHERE run_id = ?", runId) { it.getLong(1) }.single()
        if (r.finished && after >= maxSeq) return emptyFlow()
        val mayAnswer = r.userId == user.userId || conversations.record(r.sessionId)?.ownerUserId == user.userId
        val viewer = user
        var sent = after
        return live.computeIfAbsent(runId) { MutableSharedFlow(extraBufferCapacity = 4096) }
            .onSubscription {
                val stored = db.query("SELECT seq, type, payload_json FROM run_event WHERE run_id = ? AND seq > ? ORDER BY seq", runId, after) {
                    RunEvt(it.getLong(1), it.getString(2), it.getString(3))
                }
                for (e in stored) { emit(e) }
            }
            .filter { e -> (e.seq > sent).also { if (it) sent = e.seq } }
            .transformWhile { emit(personal(it, mayAnswer, viewer)); it.type !in TERMINAL }
    }

    /** One stored stream, several viewers: `can_answer` is true only for the person who may answer. */
    private fun personal(e: RunEvt, mayAnswer: Boolean, viewer: HubUser): RunEvt = when (e.type) {
        "clarify.requested" -> if (mayAnswer) e else {
            val json = hubJson.parseToJsonElement(e.json).jsonObject
            e.copy(json = JsonObject(json + ("can_answer" to JsonPrimitive(false))).toString())
        }
        "approval.requested" -> {
            val json = hubJson.parseToJsonElement(e.json).jsonObject
            val a = json["approval_id"]?.jsonPrimitive?.content?.let(approvals::get)
            if (a == null) e else e.copy(json = JsonObject(json + ("allowed_decisions" to jsonList(approvals.allowed(viewer, a))) +
                ("can_decide" to JsonPrimitive(approvals.canDecide(viewer, a) && a.status == "pending"))).toString())
        }
        else -> e
    }

    private fun jsonList(items: List<String>) = JsonArray(items.map(::JsonPrimitive))

    /** Decide an approval (contract decideApproval): checked, sent to the agent, recorded, announced. */
    suspend fun decide(user: HubUser, approvalId: String, decision: String): ApprovalOut {
        val a = approvals.get(approvalId)?.takeIf { it.hubId == user.hubId && (approvals.canDecide(user, it) || it.requestedBy == user.userId) }
            ?: throw HubException.notFound("approval")
        if (a.status != "pending") throw HubException(HttpStatusCode.Conflict, "approval_already_decided", "Already decided", "This request was already decided.")
        if (!approvals.canDecide(user, a)) throw HubException.forbidden("Someone with approval rights decides this one.")
        if (decision !in approvals.allowed(user, a)) throw HubException.forbidden(
            if (decision == "approve_always") "Only the owner can allow a command permanently." else "That decision isn't available for this request.")
        val r = runRow(a.runId) ?: throw HubException.notFound("run")
        val (runsApi, target) = agentFor(r)
        turn(a.runId).withLock {
            try {
                runsApi.decide(target, r.agentRunId!!, a.agentRequestId, a.generation, Approvals.choice(decision))
            } catch (e: AgentRunException) {
                approvals.record(a.id, "expired", null, null)
                throw HubException(HttpStatusCode.Conflict, "approval_already_decided", "No longer waiting", e.message)
            }
            if (approvals.record(a.id, if (decision == "deny") "denied" else "approved", decision, user.userId)) {
                emit(a.runId, "approval.decided", mapOf("approval_id" to a.id, "decision" to decision, "decided_by" to user.userId))
                userEvents.publish(approvals.deciders(a) + a.requestedBy, "approval.decided",
                    mapOf("approval_id" to a.id, "decision" to decision, "decided_by" to user.userId))
                accounts.activity(a.hubId, user.userId, a.agentId, "approval_decided", """{"decision":"$decision","approval_id":"${a.id}"}""")
                setStatus(a.runId, "running")
            }
        }
        return approvals.out(user, approvals.get(a.id)!!)
    }

    // ── controls ────────────────────────────────────────────────────────────

    fun getRun(user: HubUser, runId: String): RunOut { access(user, runId); return getRun(runId)!! }

    private fun getRun(runId: String): RunOut? = db.query("SELECT * FROM run WHERE id = ?", runId) { rs ->
        RunOut(rs.getString("id"), rs.getString("session_id"), rs.getString("agent_id"), rs.getString("status"), rs.str("model_label"),
            rs.getString("message_id"), rs.str("output"), rs.str("error"), rs.str("usage_json")?.let { hubJson.parseToJsonElement(it).jsonObject },
            null, rs.getString("created_at"), rs.str("finished_at"))
    }.firstOrNull()?.let { run ->
        run.copy(lastEventId = db.query("SELECT MAX(seq) FROM run_event WHERE run_id = ?", runId) { it.getLong(1).takeUnless { _ -> it.wasNull() } }
            .firstOrNull()?.toString())
    }

    suspend fun stop(user: HubUser, runId: String) {
        val r = access(user, runId)
        requireStarterOrOwner(user, r)
        if (r.finished) throw HubException(HttpStatusCode.Conflict, "run_finished", "Run finished", "This answer is already finished.")
        db.update("UPDATE run SET stopped_by = ? WHERE id = ?", user.userId, runId)
        if (r.status != "stopping") setStatus(runId, "stopping")
        val (runsApi, target) = agentFor(r)
        try {
            runsApi.stop(target, r.agentRunId!!)
        } catch (e: AgentRunException) {
            // The agent cannot be reached: end it here; the relay stops reading.
            relays.remove(runId)?.cancel()
            turn(runId).withLock { finish(runId, "cancelled") }
        }
    }

    suspend fun answer(user: HubUser, runId: String, clarificationId: String, input: AnswerIn) {
        val r = access(user, runId)
        requireStarterOrOwner(user, r)
        val (requestId, generation) = db.query(
            "SELECT agent_request_id, execution_generation FROM clarification WHERE id = ? AND run_id = ? AND status = 'pending'", clarificationId, runId,
        ) { it.getString(1) to it.getInt(2).takeUnless { _ -> it.wasNull() } }.firstOrNull()
            ?: throw HubException(HttpStatusCode.Conflict, "clarification_not_pending", "Question not open", "This question was already answered, expired or cancelled.")
        val answer = input.answer
        val ok = when (answer) {
            is JsonPrimitive -> answer.isString && answer.content.isNotBlank() && answer.content.length <= 4000
            is JsonArray -> answer.isNotEmpty() && answer.all { it is JsonPrimitive && it.isString }
            else -> false
        }
        if (!ok) throw HubException.invalid("answer: text (1–4000 characters) or a list of choices")
        val (runsApi, target) = agentFor(r)
        turn(runId).withLock {
            if (db.query("SELECT status FROM clarification WHERE id = ?", clarificationId) { it.getString(1) }.firstOrNull() != "pending")
                throw HubException(HttpStatusCode.Conflict, "clarification_not_pending", "Question not open", "This question was already answered, expired or cancelled.")
            try {
                runsApi.answer(target, r.agentRunId!!, requestId, generation, answer)
            } catch (e: AgentRunException) {
                db.update("UPDATE clarification SET status = 'cancelled' WHERE id = ?", clarificationId)
                emit(runId, "clarify.answered", mapOf("clarification_id" to clarificationId, "outcome" to "cancelled", "answer" to null, "answered_by" to null))
                throw HubException(HttpStatusCode.Conflict, "clarification_not_pending", "Question not open", e.message)
            }
            db.update("UPDATE clarification SET status = 'answered', answer_json = ?, answered_by = ? WHERE id = ?", answer.toString(), user.userId, clarificationId)
            emit(runId, "clarify.answered", mapOf("clarification_id" to clarificationId, "outcome" to "answered", "answer" to answer, "answered_by" to user.userId))
            if (db.query("SELECT COUNT(*) FROM clarification WHERE run_id = ? AND status = 'pending'", runId) { it.getInt(1) }.single() == 0)
                setStatus(runId, "running")
        }
    }

    suspend fun steer(user: HubUser, runId: String, input: SteerIn) {
        val r = access(user, runId)
        val text = input.text.trim()
        if (text.isEmpty() || text.length > 4000) throw HubException.invalid("text: 1–4000 characters")
        val agent = agents.record(r.agentId) ?: throw HubException.notFound("agent")
        if (!agent.capabilities.steer) throw notSupported()
        if (r.status != "running") throw notAccepting()
        val (runsApi, target) = agentFor(r)
        turn(runId).withLock {
            if (!runCatching { runsApi.steer(target, r.agentRunId!!, text) }.getOrDefault(false)) throw notAccepting()
            val id = newId("msg")
            db.update("INSERT INTO message (id, hub_id, session_id, role, kind, author_user_id, text, run_id, created_at) VALUES (?, ?, ?, 'user', 'steer', ?, ?, ?, ?)",
                id, r.hubId, r.sessionId, user.userId, text, runId, now())
            emit(runId, "steer.accepted", mapOf("text" to text, "by" to user.userId, "message_id" to id))
        }
    }

    /** After a restart: resume reading every open run (or end it if the agent cannot say). */
    fun recover() {
        val open = db.query("SELECT id FROM run WHERE status IN ('queued', 'running', 'waiting_for_approval', 'waiting_for_input', 'stopping')") { it.getString(1) }
        if (open.isNotEmpty()) log.info("resuming {} open run(s)", open.size)
        open.forEach(::relay)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private data class RunRow(val id: String, val hubId: String, val sessionId: String, val agentId: String, val userId: String,
                              val agentRunId: String?, val agentLastSeq: Long, val status: String, val messageId: String, val stoppedBy: String?,
                              val profileId: String?) {
        val finished get() = status in setOf("completed", "failed", "cancelled", "interrupted")
    }

    private fun runRow(id: String) = db.query("SELECT * FROM run WHERE id = ?", id) { rs ->
        RunRow(rs.getString("id"), rs.getString("hub_id"), rs.getString("session_id"), rs.getString("agent_id"), rs.getString("user_id"),
            rs.str("agent_run_id"), rs.getLong("agent_last_seq"), rs.getString("status"), rs.getString("message_id"), rs.str("stopped_by"),
            rs.str("profile_id"))
    }.firstOrNull()

    private fun isFinished(runId: String) = runRow(runId)?.finished != false

    private fun requireStarterOrOwner(user: HubUser, r: RunRow) {
        if (r.userId != user.userId && conversations.record(r.sessionId)?.ownerUserId != user.userId)
            throw HubException.forbidden("Only the person who sent this message can do that.")
    }

    private fun access(user: HubUser, runId: String): RunRow {
        val r = runRow(runId) ?: throw HubException.notFound("run")
        conversations.get(user, r.sessionId)   // 404 unless the run's conversation is the user's
        return r
    }

    private fun agentFor(r: RunRow): Pair<AgentRuns, ProfileTarget> {
        val agent = agents.record(r.agentId) ?: throw HubException.notFound("agent")
        val runsApi = agents.adapterFor(agent) as? AgentRuns ?: throw notSupported()
        val target = agents.targetFor(agent, r.profileId) ?: throw notSupported()
        if (r.agentRunId == null) throw notAccepting()
        return runsApi to target
    }

    private fun notSupported() = HubException(HttpStatusCode.UnprocessableEntity, "not_supported", "Not supported", "This agent does not support that.")
    private fun notAccepting() = HubException(HttpStatusCode.Conflict, "run_not_accepting_input", "Not accepting input",
        "The answer is not in a state that takes guidance right now.")

    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    companion object {
        val TERMINAL = setOf("run.completed", "run.failed", "run.cancelled")
        /** Runs at least this long get a "finished" push. */
        const val LONG_RUN_S = 20L
    }
}
