package llc.applabs.switchboard.hub.adapters

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import llc.applabs.switchboard.hub.HubException
import llc.applabs.switchboard.hub.hubJson

/**
 * Hermes cron jobs (`/api/jobs…`, checked against a live 0.22.1): `schedule` is `{kind, expr|minutes,
 * display}`, `enabled` + `state` (scheduled / paused / running), `last_status` ok / error, `repeat`
 * `{times, completed}`. Times are ISO with an offset, except `last_run_at`, which is local without one
 * (the job's `next_run_at` offset is used for it).
 */
class HermesJobs(private val http: HttpClient) : AgentJobs {
    override suspend fun jobs(target: ProfileTarget): List<AgentJob> {
        val r = send { http.get("${target.url}/api/jobs?include_disabled=true") { bearerAuth(target.apiKey) } }
        return (parse(r)["jobs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::toJob) }
    }

    override suspend fun createJob(target: ProfileTarget, fields: JobFields): AgentJob = one(send {
        http.post("${target.url}/api/jobs") { bearerAuth(target.apiKey); json(body(fields)) }
    })

    override suspend fun updateJob(target: ProfileTarget, id: String, fields: JobFields): AgentJob = one(send {
        http.patch("${target.url}/api/jobs/$id") { bearerAuth(target.apiKey); json(body(fields)) }
    })

    override suspend fun deleteJob(target: ProfileTarget, id: String) {
        send { http.delete("${target.url}/api/jobs/$id") { bearerAuth(target.apiKey) } }
    }

    override suspend fun jobAction(target: ProfileTarget, id: String, action: String): AgentJob = one(send {
        http.post("${target.url}/api/jobs/$id/$action") { bearerAuth(target.apiKey); json(JsonObject(emptyMap())) }
    })

    private fun io.ktor.client.request.HttpRequestBuilder.json(o: JsonObject) { contentType(ContentType.Application.Json); setBody(o.toString()) }

    private fun body(f: JobFields) = buildJsonObject {
        f.name?.let { put("name", it) }; f.schedule?.let { put("schedule", it) }; f.prompt?.let { put("prompt", it) }; f.repeat?.let { put("repeat", it) }
    }

    private suspend fun send(block: suspend () -> HttpResponse): HttpResponse {
        val r = try { block() } catch (e: Exception) {
            throw HubException(io.ktor.http.HttpStatusCode.ServiceUnavailable, "agent_offline", "Agent offline", "The agent does not answer.")
        }
        if (r.status.value in 200..299) return r
        val msg = runCatching { hubJson.parseToJsonElement(r.bodyAsText()).jsonObject["error"]?.let { e ->
            (e as? JsonObject)?.get("message")?.jsonPrimitive?.content ?: e.jsonPrimitive.content } }.getOrNull()
        throw when (r.status.value) {
            404 -> HubException.notFound("job")
            400, 422 -> HubException.invalid(msg ?: "The agent refused these job settings.")
            else -> HubException(io.ktor.http.HttpStatusCode.BadGateway, "agent_error", "The agent refused", msg ?: "HTTP ${r.status.value}")
        }
    }

    private suspend fun parse(r: HttpResponse) = hubJson.parseToJsonElement(r.bodyAsText()).jsonObject
    private suspend fun one(r: HttpResponse) = toJob(parse(r)["job"]!!.jsonObject)

    companion object {
        private fun JsonObject.s(k: String) = this[k]?.takeUnless { it is JsonNull }?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

        fun toJob(j: JsonObject): AgentJob {
            val schedule = (j["schedule"] as? JsonObject)?.s("display") ?: j.s("schedule_display") ?: j.s("schedule") ?: ""
            val enabled = j["enabled"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } != false
            val state = when {
                j.s("state") == "running" -> "running"
                !enabled || j.s("state") == "paused" -> "paused"
                else -> "active"
            }
            val next = j.s("next_run_at")
            val offset = next?.let { runCatching { OffsetDateTime.parse(it).offset }.getOrNull() } ?: ZoneOffset.UTC
            return AgentJob(
                id = j.s("id") ?: "", name = j.s("name") ?: "", prompt = j.s("prompt"), schedule = schedule, state = state,
                repeat = (j["repeat"] as? JsonObject)?.get("times")?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() },
                lastRunAt = j.s("last_run_at")?.let { utc(it, offset) },
                lastResult = when (j.s("last_status")) { "ok", "success" -> "success"; "error", "failure", "failed" -> "failure"; else -> null },
                nextRunAt = next?.let { utc(it, offset) },
            )
        }

        /** ISO with or without an offset → UTC `…Z`; one without is read at [assumed]. */
        fun utc(s: String, assumed: ZoneOffset): String? = runCatching { OffsetDateTime.parse(s).toInstant().toString() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).atOffset(assumed).toInstant().toString() }.getOrNull()
    }
}
