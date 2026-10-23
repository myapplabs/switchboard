package llc.applabs.switchboard.hub.adapters

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import java.net.ConnectException
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import llc.applabs.switchboard.hub.hubJson

/**
 * Hermes runs, checked against a live 0.22.1 and its recorded streams
 * (api/fixtures/recorded/hermes-0.22.1/):
 *
 * - `POST /v1/runs` `{input, session_id, conversation_history?, model?}` → 202 `{run_id}`; the
 *   per-user memory scope goes in `X-Hermes-Session-Key`, retries are safe with `Idempotency-Key`.
 * - `GET /v1/runs/{id}/events`: `id: <seq>` + `data: {json}` lines, the event named in the JSON's
 *   `event` field, ending after `run.completed` / `run.failed` / `run.cancelled`.
 * - clarify (0.22, from source): `clarify.request` → `POST /v1/runs/{id}/clarify`
 *   `{request_id, execution_generation, answer}`; steer: `POST /v1/runs/{id}/steer` `{input}`.
 * - `GET` / `DELETE /api/sessions/{id}` (0.22): the conversation's own session.
 */
class HermesRuns(private val http: HttpClient) : AgentRuns {

    override suspend fun startRun(target: ProfileTarget, request: RunRequest): String {
        val body = buildJsonObject {
            // With photos: one user message of text and image parts (Hermes passes images to the model, or has
            // them described for a model that can't see); /v1/runs reads the last message's content.
            if (request.images.isEmpty()) put("input", request.input)
            else put("input", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", request.input) })
                        request.images.forEach { url -> add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", url) }) }) }
                    })
                })
            })
            put("session_id", request.agentSessionId)
            // The hub's model ids are `provider::model` (from /api/model/options); /v1/runs takes them as two
            // fields — only the sessions API splits the prefixed form itself (checked in 0.21.1's source).
            request.model?.let { id ->
                val provider = id.substringBefore("::", "")
                if (provider.isNotEmpty()) put("provider", provider)
                put("model", id.substringAfter("::"))
            }
            request.history?.takeIf { it.isNotEmpty() }?.let { h ->
                put("conversation_history", buildJsonArray {
                    h.forEach { m -> add(buildJsonObject { put("role", m.role); put("content", m.content) }) }
                })
            }
        }
        val r = call { http.post("${target.url}/v1/runs") {
            bearerAuth(target.apiKey)
            header("X-Hermes-Session-Key", request.userScope)
            header("Idempotency-Key", request.idempotencyKey)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        } }
        val json = parse(r)
        return when {
            r.status.value in 200..299 -> json?.get("run_id")?.jsonPrimitive?.content
                ?: throw AgentRunException("hub_error", "The agent accepted the run but returned no run id.")
            r.status.value in setOf(401, 403) -> throw AgentRunException("agent_key_rejected", "The agent rejected the hub's API key.")
            r.status == HttpStatusCode.TooManyRequests -> throw AgentRunException("agent_busy",
                "The agent is busy with as many runs as it allows. Try again shortly.", r.headers["Retry-After"]?.toIntOrNull() ?: 10)
            else -> throw AgentRunException("agent_error", "The agent refused the run (HTTP ${r.status.value}): ${errorText(json) ?: "no detail"}")
        }
    }

    override fun events(target: ProfileTarget, agentRunId: String): Flow<AgentEvent> = flow {
        http.prepareGet("${target.url}/v1/runs/$agentRunId/events") {
            bearerAuth(target.apiKey)
            header("Accept", "text/event-stream")
            timeout { requestTimeoutMillis = Long.MAX_VALUE; socketTimeoutMillis = 120_000 }
        }.execute { r ->
            if (r.status == HttpStatusCode.NotFound) throw AgentRunException("run_interrupted", "The agent no longer knows this run.")
            if (r.status.value !in 200..299) throw AgentRunException("agent_error", "The agent's event stream answered HTTP ${r.status.value}.")
            val channel = r.bodyAsChannel()
            var lastId: Long? = null
            while (true) {
                val line = channel.readUTF8Line() ?: break
                when {
                    line.startsWith("id:") -> lastId = line.removePrefix("id:").trim().toLongOrNull()
                    line.startsWith("data:") -> {
                        val json = runCatching { hubJson.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject }.getOrNull() ?: continue
                        val event = toEvent(json, lastId)
                        emit(event)
                        if (event is AgentEvent.Completed || event is AgentEvent.Failed || event is AgentEvent.Cancelled) return@execute
                    }
                }
            }
        }
    }

    override suspend fun runStatus(target: ProfileTarget, agentRunId: String): AgentRunStatus? {
        val r = call { http.get("${target.url}/v1/runs/$agentRunId") { bearerAuth(target.apiKey) } }
        if (r.status.value !in 200..299) return null
        val json = parse(r) ?: return null
        return AgentRunStatus(json.str("status") ?: "unknown", json.str("output"), errorText(json), usage(json["usage"]))
    }

    override suspend fun stop(target: ProfileTarget, agentRunId: String) {
        val r = call { http.post("${target.url}/v1/runs/$agentRunId/stop") {
            bearerAuth(target.apiKey); contentType(ContentType.Application.Json); setBody("{}")
        } }
        if (r.status.value !in 200..299 && r.status != HttpStatusCode.Conflict)
            throw AgentRunException("agent_error", "The agent could not stop the run (HTTP ${r.status.value}).")
    }

    override suspend fun answer(target: ProfileTarget, agentRunId: String, requestId: String, generation: Int?, answer: JsonElement) {
        val body = buildJsonObject {
            put("request_id", requestId)
            generation?.let { put("execution_generation", it) }
            put("answer", if (answer is JsonArray) JsonPrimitive(answer.joinToString(", ") { it.jsonPrimitive.content }) else answer)
        }
        val r = call { http.post("${target.url}/v1/runs/$agentRunId/clarify") {
            bearerAuth(target.apiKey); contentType(ContentType.Application.Json); setBody(body.toString())
        } }
        if (r.status.value !in 200..299) throw AgentRunException("clarification_not_pending",
            "The agent no longer waits for this answer (HTTP ${r.status.value}).")
    }

    override suspend fun steer(target: ProfileTarget, agentRunId: String, text: String): Boolean {
        val r = call { http.post("${target.url}/v1/runs/$agentRunId/steer") {
            bearerAuth(target.apiKey); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("input", text) }.toString())
        } }
        return r.status.value in 200..299
    }

    override suspend fun decide(target: ProfileTarget, agentRunId: String, requestId: String?, generation: Int?, choice: String) {
        // 0.22 session runs take exactly {request_id, execution_generation, choice}; others {choice, request_id?}.
        val body = buildJsonObject {
            put("choice", choice)
            requestId?.let { put("request_id", it) }
            if (generation != null && requestId != null) put("execution_generation", generation)
        }
        val r = call { http.post("${target.url}/v1/runs/$agentRunId/approval") {
            bearerAuth(target.apiKey); contentType(ContentType.Application.Json); setBody(body.toString())
        } }
        if (r.status.value !in 200..299) throw AgentRunException("approval_already_decided",
            "The agent no longer waits for this approval (HTTP ${r.status.value}).")
    }

    override suspend fun session(target: ProfileTarget, agentSessionId: String, userScope: String): AgentSessionInfo {
        val r = try {
            http.get("${target.url}/api/sessions/$agentSessionId") { bearerAuth(target.apiKey); header("X-Hermes-Session-Key", userScope) }
        } catch (e: Exception) {
            return AgentSessionInfo(false)
        }
        if (r.status.value !in 200..299) return AgentSessionInfo(false)
        val s = parse(r)?.get("session")?.let { runCatching { it.jsonObject }.getOrNull() } ?: return AgentSessionInfo(false)
        return AgentSessionInfo(true, s.str("title"), s["estimated_cost_usd"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() })
    }

    override suspend fun models(target: ProfileTarget): List<AgentModel>? {
        val r = try { http.get("${target.url}/api/model/options") { bearerAuth(target.apiKey) } } catch (e: Exception) { return null }
        if (r.status.value !in 200..299) return null
        val json = parse(r) ?: return null
        val currentModel = json.str("model"); val currentProvider = json.str("provider")
        val out = mutableListOf<AgentModel>()
        for (p in (json["providers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }) {
            val authenticated = p["authenticated"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true
            if (!authenticated) continue
            val slug = p.str("slug") ?: continue
            val local = slug in LOCAL_PROVIDERS
            for (m in (p["models"] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }) {
                val isDefault = slug == currentProvider && m == currentModel
                out += AgentModel("$slug::$m", m.substringAfterLast('/'), isPaid = !local && !m.endsWith(":free"), isLocal = local, isDefault = isDefault)
            }
        }
        if (out.none { it.isDefault } && currentModel != null && currentProvider != null)
            out += AgentModel("$currentProvider::$currentModel", currentModel.substringAfterLast('/'),
                isPaid = currentProvider !in LOCAL_PROVIDERS && !currentModel.endsWith(":free"), isLocal = currentProvider in LOCAL_PROVIDERS, isDefault = true)
        return out.sortedByDescending { it.isDefault }
    }

    override suspend fun deleteSession(target: ProfileTarget, agentSessionId: String, userScope: String): Boolean = try {
        val r = http.delete("${target.url}/api/sessions/$agentSessionId") { bearerAuth(target.apiKey); header("X-Hermes-Session-Key", userScope) }
        r.status.value in 200..299 || r.status == HttpStatusCode.NotFound
    } catch (e: Exception) {
        false
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: AgentRunException) {
        throw e
    } catch (e: ConnectException) {
        throw AgentRunException("agent_offline", "The agent does not answer (connection refused).")
    } catch (e: Exception) {
        throw AgentRunException("agent_offline", "The agent does not answer (${e::class.simpleName}).")
    }

    private suspend fun parse(r: HttpResponse): JsonObject? =
        runCatching { hubJson.parseToJsonElement(r.bodyAsText()).jsonObject }.getOrNull()

    companion object {
        private val LOCAL_PROVIDERS = setOf("ollama", "lmstudio", "llamacpp", "vllm", "local", "custom")

        /** One Hermes event → the hub's view. Public for tests that replay recorded streams. */
        fun toEvent(json: JsonObject, sseId: Long? = null): AgentEvent {
            val name = json.str("event") ?: json.str("type") ?: ""
            val seq = json["seq"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() } ?: sseId ?: -1
            val at = json["timestamp"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
                ?.let { Instant.ofEpochMilli((it * 1000).toLong()).toString() }
            return when (name) {
                "message.delta" -> AgentEvent.Delta(seq, at, json.str("delta") ?: "")
                "tool.started" -> AgentEvent.ToolStarted(seq, at, json.str("tool_call_id"), json.str("tool") ?: "tool", json.str("preview"))
                "tool.completed" -> AgentEvent.ToolCompleted(seq, at, json.str("tool_call_id"), json.str("tool") ?: "tool",
                    json["error"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true,
                    json["duration"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() },
                    json.str("preview")?.takeIf { it.isNotBlank() }?.take(4096))
                "reasoning.available", "reasoning.delta" -> AgentEvent.Reasoning(seq, at, json.str("text") ?: json.str("delta") ?: "")
                "clarify.request" -> AgentEvent.Clarify(seq, at, json.str("prompt_id") ?: json.str("request_id") ?: "",
                    json["execution_generation"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() },
                    json.str("question") ?: "",
                    (json["choices"] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList(),
                    json["multi_select"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true,
                    json["timeout_s"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() })
                "approval.request" -> AgentEvent.ApprovalRequested(seq, at, json.str("request_id") ?: json.str("prompt_id"),
                    json["execution_generation"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() },
                    json.str("command") ?: "", json.str("description")?.takeIf { it.isNotBlank() },
                    (json["choices"] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: listOf("once", "deny"),
                    json["timeout_s"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() })
                "approval.responded" -> AgentEvent.ApprovalResolved(seq, at, json.str("request_id"), json.str("choice"))
                "run.completed" -> AgentEvent.Completed(seq, at, json.str("output"), usage(json["usage"]))
                "run.failed" -> AgentEvent.Failed(seq, at, errorText(json) ?: "The agent reported a failure.")
                "run.cancelled" -> AgentEvent.Cancelled(seq, at)
                else -> AgentEvent.Other(seq, at, name)
            }
        }

        private fun JsonObject.str(k: String): String? =
            this[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeUnless { this[k] is kotlinx.serialization.json.JsonNull }

        private fun usage(e: JsonElement?): Usage? = (e as? JsonObject)?.let { u ->
            Usage(u["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0, u["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0)
        }

        private fun errorText(json: JsonObject?): String? {
            val e = json?.get("error") ?: return null
            return when (e) {
                is JsonPrimitive -> e.content
                is JsonObject -> e["message"]?.jsonPrimitive?.content ?: e.toString()
                else -> e.toString()
            }
        }
    }
}
