package llc.applabs.switchboard.hub

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import java.net.ConnectException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import llc.applabs.switchboard.hub.adapters.HermesAdapter

/**
 * A Hermes Agent 0.14 API server, faked: the responses are the shapes in its source
 * (gateway/platforms/api_server.py; api repo docs/hermes-0.14-findings.md). Behaviour switches let a
 * test take the agent down, change its key, or make it an older Hermes.
 */
class FakeHermes(var key: String = KEY) {
    var down = false
    var activeAgents = 0
    var hasCapabilities = true
    var runs = true
    var jobs = true
    /** Hermes 0.22 behaviour: `/health/detailed` needs the key (0.14: open). */
    var detailedNeedsKey = true
    val calls = java.util.Collections.synchronizedList(mutableListOf<String>())

    // ── runs ────────────────────────────────────────────────────────────────
    /** A scripted event stream: text is written as is; a [Step.Wait] holds the stream open until completed. */
    sealed interface Step { data class Text(val text: String) : Step; data class Wait(val signal: CompletableDeferred<Unit>) : Step }
    /** Streams for the next `/events` requests, in order; when empty, the recorded tool run. */
    val streams = ConcurrentLinkedQueue<List<Step>>()
    /** `GET /v1/runs/{id}` answers. */
    var runStatus: String? = """{"status":"running"}"""
    var busy = false
    /** Hermes sessions that exist (GET /api/sessions/{id}), with their titles. */
    val sessions = java.util.concurrent.ConcurrentHashMap<String, String>()
    val runBodies = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
    val runHeaders = java.util.Collections.synchronizedList(mutableListOf<Map<String, String?>>())
    val clarifyBodies = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
    val steerBodies = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
    val deleted = java.util.Collections.synchronizedList(mutableListOf<String>())
    /** Completed when the hub calls stop / clarify (for scripts that wait on them). */
    var stopped = CompletableDeferred<Unit>()
    var answered = CompletableDeferred<Unit>()
    var approved = CompletableDeferred<Unit>()
    val approvalBodies = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
    /** Jobs as Hermes 0.22 reports them (shapes from cio-mini). */
    val jobList = java.util.concurrent.CopyOnWriteArrayList(listOf(
        """{"id":"j1","name":"Daily digest","prompt":"Summarise the news","schedule":{"kind":"cron","expr":"0 6 * * *","display":"0 6 * * *"},"enabled":true,"state":"scheduled","last_status":"ok","last_run_at":"2026-10-20T06:12:17.46735","next_run_at":"2026-10-21T06:00:00-07:00","repeat":{"times":null,"completed":21}}""",
        """{"id":"j2","name":"Backups","prompt":"Back up","schedule":{"kind":"interval","minutes":360,"display":"every 360m"},"enabled":false,"state":"paused","last_status":"error","last_run_at":"2026-10-18T00:00:40.4","next_run_at":"2026-10-19T00:00:00-07:00","repeat":{"times":5,"completed":3}}""",
    ))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runCounter = 0

    val client = HttpClient(MockEngine { req -> handle(req) }) { expectSuccess = false }
    val adapter = HermesAdapter(client)

    /**
     * The same agent without HTTP: for tests on virtual time (the HTTP client works on real threads,
     * which virtual time does not wait for). Same switches, same call log.
     */
    val inMemory = object : llc.applabs.switchboard.hub.adapters.AgentAdapter {
        override val type = "hermes"
        override suspend fun health(target: llc.applabs.switchboard.hub.adapters.ProfileTarget): llc.applabs.switchboard.hub.adapters.Health {
            calls += "GET /health/detailed"
            return if (down) llc.applabs.switchboard.hub.adapters.Health.Down("Connection refused")
            else llc.applabs.switchboard.hub.adapters.Health.Up(activeAgents, if (activeAgents > 0) "Working on $activeAgents run${if (activeAgents == 1) "" else "s"}" else null)
        }
        override suspend fun keyAccepted(target: llc.applabs.switchboard.hub.adapters.ProfileTarget): Boolean? {
            calls += "GET /v1/capabilities"
            return if (down) null else target.apiKey == key
        }
        override suspend fun test(targets: List<llc.applabs.switchboard.hub.adapters.ProfileTarget>) =
            llc.applabs.switchboard.hub.ConnectionTest(ok = !down && targets.all { it.apiKey == key },
                checks = listOf(llc.applabs.switchboard.hub.Check("auth", targets.all { it.apiKey == key })),
                capabilities = llc.applabs.switchboard.hub.Capabilities(chat = true, streaming = true))
        override suspend fun capabilities(target: llc.applabs.switchboard.hub.adapters.ProfileTarget) =
            llc.applabs.switchboard.hub.Capabilities(chat = true, streaming = true)
    }

    private fun authorized(req: HttpRequestData) = req.headers[HttpHeaders.Authorization] == "Bearer $key"

    private suspend fun io.ktor.client.engine.mock.MockRequestHandleScope.handle(req: HttpRequestData) = run {
        calls += "${req.method.value} ${req.url.encodedPath}"
        if (down) throw ConnectException("Connection refused")
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val path = req.url.encodedPath
        if (path.startsWith("/api/jobs")) {
            if (!authorized(req)) return@run respond(INVALID_KEY, HttpStatusCode.Unauthorized, json)
            return@run jobsApi(req, path, json)
        }
        if (path.startsWith("/v1/runs") || path.startsWith("/api/sessions/")) {
            if (!authorized(req)) return@run respond(INVALID_KEY, HttpStatusCode.Unauthorized, json)
            return@run runs(req, path, json)
        }
        when (path) {
            "/health" -> respond("""{"status":"ok","platform":"hermes-agent","version":"0.22.1"}""", HttpStatusCode.OK, json)
            "/health/detailed" -> if (detailedNeedsKey && !authorized(req))
                respond("""{"error":{"message":"Invalid gateway API key (API_SERVER_KEY)","type":"gateway_auth_error","code":"gateway_auth_failed"}}""",
                    HttpStatusCode.Unauthorized, json)
            else respond(
                """{"status":"ok","platform":"hermes-agent","gateway_state":"running","platforms":{},"active_agents":$activeAgents,"pid":4242}""",
                HttpStatusCode.OK, json)
            "/v1/capabilities" -> when {
                !hasCapabilities -> respond("not found", HttpStatusCode.NotFound)
                !authorized(req) -> respond(INVALID_KEY, HttpStatusCode.Unauthorized, json)
                else -> respond(capabilities(runs), HttpStatusCode.OK, json)
            }
            "/v1/models" -> if (!authorized(req)) respond(INVALID_KEY, HttpStatusCode.Unauthorized, json)
                else respond("""{"object":"list","data":[{"id":"hermes-agent","object":"model","owned_by":"hermes"}]}""", HttpStatusCode.OK, json)
            "/api/model/options" -> if (!authorized(req)) respond(INVALID_KEY, HttpStatusCode.Unauthorized, json)
                else respond("""{"model":"nvidia/nemotron-3-ultra-550b-a55b:free","provider":"openrouter","providers":[
                    {"slug":"openrouter","is_current":true,"authenticated":true,"models":["nvidia/nemotron-3-ultra-550b-a55b:free","anthropic/claude-sonnet-5.5","openai/gpt-5.6"]},
                    {"slug":"ollama","is_current":false,"authenticated":true,"models":["qwen4:8b"]},
                    {"slug":"fireworks","is_current":false,"authenticated":false,"models":[]}]}""", HttpStatusCode.OK, json)
            "/api/jobs" -> when {
                !authorized(req) -> respond(INVALID_KEY, HttpStatusCode.Unauthorized, json)
                !jobs -> respond("""{"error":"Cron jobs are not available"}""", HttpStatusCode.ServiceUnavailable, json)
                else -> respond("""{"jobs":[]}""", HttpStatusCode.OK, json)
            }
            else -> respond("not found", HttpStatusCode.NotFound)
        }
    }

    private suspend fun io.ktor.client.engine.mock.MockRequestHandleScope.runs(req: HttpRequestData, path: String, json: io.ktor.http.Headers) = when {
        path == "/v1/runs" && req.method == HttpMethod.Post -> {
            if (busy) respond("""{"error":{"message":"Too many concurrent runs","code":"rate_limited"}}""", HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("7")))
            else {
                runBodies += body(req)
                runHeaders += mapOf("session-key" to req.headers["X-Hermes-Session-Key"], "idempotency" to req.headers["Idempotency-Key"],
                    "port" to req.url.port.toString())
                respond("""{"run_id":"run_fake_${++runCounter}","status":"started","replayed":false}""", HttpStatusCode.Accepted, json)
            }
        }
        path.endsWith("/events") -> {
            val steps = streams.poll() ?: listOf(Step.Text(recorded("run-tool-date")))
            val channel = ByteChannel(autoFlush = true)
            scope.launch {
                for (step in steps) when (step) {
                    is Step.Text -> channel.writeStringUtf8(step.text)
                    is Step.Wait -> step.signal.await()
                }
                channel.flushAndClose()
            }
            respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        path.endsWith("/stop") -> { stopped.complete(Unit); respond("""{"status":"stopping"}""", HttpStatusCode.OK, json) }
        path.endsWith("/approval") -> { approvalBodies += body(req); approved.complete(Unit)
            respond("""{"object":"hermes.run.approval_response","choice":"once","resolved":1}""", HttpStatusCode.OK, json) }
        path.endsWith("/clarify") -> { clarifyBodies += body(req); answered.complete(Unit); respond("""{"status":"answered"}""", HttpStatusCode.OK, json) }
        path.endsWith("/steer") -> { steerBodies += body(req); respond("""{"status":"accepted"}""", HttpStatusCode.OK, json) }
        path.startsWith("/v1/runs/") -> runStatus?.let { respond(it, HttpStatusCode.OK, json) } ?: respond("""{"error":"not found"}""", HttpStatusCode.NotFound, json)
        req.method == HttpMethod.Delete -> { val id = path.substringAfterLast('/'); deleted += id; sessions.remove(id); respond("""{"deleted":true}""", HttpStatusCode.OK, json) }
        else -> {
            val id = path.substringAfterLast('/')
            sessions[id]?.let { title ->
                respond("""{"object":"hermes.session","session":{"id":"$id","title":"$title","estimated_cost_usd":0.0012}}""", HttpStatusCode.OK, json)
            } ?: respond("""{"error":{"message":"Session not found"}}""", HttpStatusCode.NotFound, json)
        }
    }

    private suspend fun io.ktor.client.engine.mock.MockRequestHandleScope.jobsApi(req: HttpRequestData, path: String, json: io.ktor.http.Headers) = when {
        !jobs -> respond("""{"error":"Cron jobs are not available"}""", HttpStatusCode.ServiceUnavailable, json)
        path == "/api/jobs" && req.method == HttpMethod.Get -> respond("""{"jobs":[${jobList.joinToString(",")}]}""", HttpStatusCode.OK, json)
        path == "/api/jobs" && req.method == HttpMethod.Post -> {
            val b = body(req)
            if (b["schedule"]?.toString()?.contains("nonsense") == true) respond("""{"error":"Invalid schedule"}""", HttpStatusCode.BadRequest, json)
            else {
                val job = """{"id":"j${jobList.size + 1}","name":${b["name"]},"prompt":${b["prompt"]},"schedule":{"kind":"cron","display":${b["schedule"]}},"enabled":true,"state":"scheduled","next_run_at":"2026-10-22T09:00:00-07:00","repeat":{"times":null,"completed":0}}"""
                jobList += job; respond("""{"job":$job}""", HttpStatusCode.OK, json)
            }
        }
        req.method == HttpMethod.Patch && path.startsWith("/api/jobs/") -> {
            val id = path.split('/')[3]; val i = jobList.indexOfFirst { it.contains("\"id\":\"$id\"") }
            if (i < 0) respond("""{"error":"Job not found"}""", HttpStatusCode.NotFound, json)
            else {
                val merged = hubJson.parseToJsonElement(jobList[i]).jsonObject.toMutableMap().apply { putAll(body(req)) }
                jobList[i] = JsonObject(merged).toString(); respond("""{"job":${jobList[i]}}""", HttpStatusCode.OK, json)
            }
        }
        path.endsWith("/pause") -> {
            val id = path.split('/')[3]; val i = jobList.indexOfFirst { it.contains("\"id\":\"$id\"") }
            if (i < 0) respond("""{"error":"Job not found"}""", HttpStatusCode.NotFound, json)
            else { jobList[i] = jobList[i].replace("\"enabled\":true", "\"enabled\":false").replace("\"state\":\"scheduled\"", "\"state\":\"paused\""); respond("""{"job":${jobList[i]}}""", HttpStatusCode.OK, json) }
        }
        req.method == HttpMethod.Delete -> { val id = path.split('/')[3]; jobList.removeIf { it.contains("\"id\":\"$id\"") }; respond("""{"success":true}""", HttpStatusCode.OK, json) }
        else -> respond("""{"error":"Job not found"}""", HttpStatusCode.NotFound, json)
    }

    private fun body(req: HttpRequestData): JsonObject {
        val text = when (val b = req.body) { is TextContent -> b.text; is OutgoingContent.ByteArrayContent -> String(b.bytes()); else -> "{}" }
        return hubJson.parseToJsonElement(text).jsonObject
    }

    companion object {
        /** A recorded Hermes 0.22.1 stream (api/fixtures/recorded/hermes-0.22.1/<name>.sse). */
        fun recorded(name: String): String = Files.readString(Path.of("../api/fixtures/recorded/hermes-0.22.1/$name.sse"))

        /** One Hermes event as it appears on the wire. */
        fun hermesEvent(seq: Int, json: String) = "id: $seq\ndata: ${json.dropLast(1)}, \"seq\": $seq}\n\n"

        const val KEY = "k3y-0123456789abcdef-full"
        const val INVALID_KEY = """{"error":{"message":"Invalid gateway API key (API_SERVER_KEY)","type":"gateway_auth_error","code":"gateway_auth_failed"}}"""

        /** `GET /v1/capabilities` of Hermes 0.14, verbatim in structure. */
        fun capabilities(runs: Boolean = true) = """
            {"object":"hermes.api_server.capabilities","platform":"hermes-agent","model":"hermes-agent",
             "auth":{"type":"bearer","required":true},
             "runtime":{"mode":"server_agent","tool_execution":"server","split_runtime":false,"description":"…"},
             "features":{"chat_completions":true,"chat_completions_streaming":true,"responses_api":true,
               "responses_streaming":true,"run_submission":$runs,"run_status":$runs,"run_events_sse":$runs,
               "run_stop":$runs,"run_steer":$runs,"run_approval_response":true,"tool_progress_events":true,"approval_events":true,
               "model_options":true,"session_model_lock":true,"reasoning_streaming":true,
               "session_continuity_header":"X-Hermes-Session-Id","session_key_header":"X-Hermes-Session-Key","cors":false},
             "endpoints":{}}""".trimIndent()
    }
}
