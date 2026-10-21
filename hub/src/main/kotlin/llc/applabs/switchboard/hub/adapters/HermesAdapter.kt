package llc.applabs.switchboard.hub.adapters

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.net.ConnectException
import java.net.UnknownHostException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import llc.applabs.switchboard.hub.Capabilities
import llc.applabs.switchboard.hub.Check
import llc.applabs.switchboard.hub.ConnectionTest
import llc.applabs.switchboard.hub.hubJson

/**
 * Hermes Agent's API server (checked against v0.14.0's source — api repo docs/hermes-0.14-findings.md —
 * and a live v0.22.1).
 *
 * - `GET /health` needs no key: liveness (and, from 0.22, `version`).
 * - `GET /health/detailed`: `gateway_state`, `active_agents` (0.22 also `api_server.active_runs`). No key
 *   in 0.14; **the key in 0.22**, so it is always sent, and a 401 there is told apart from "down" by `/health`.
 * - `GET /v1/capabilities` and `GET /v1/models` need `Authorization: Bearer <API_SERVER_KEY>`; a wrong
 *   key answers 401 `invalid_api_key`.
 * - `GET /api/jobs` (key) answers when the cron scheduler is available.
 */
class HermesAdapter(private val http: HttpClient) : AgentAdapter, AgentRuns by HermesRuns(http), AgentJobs by HermesJobs(http) {
    override val type = "hermes"

    override suspend fun health(target: ProfileTarget): Health = try {
        val r = http.get("${target.url}/health/detailed") { bearerAuth(target.apiKey) }
        when {
            r.status.isSuccess() -> {
                val body = parse(r)
                val active = body?.get("active_agents")?.jsonPrimitive?.intOrNull
                    ?: body?.get("api_server")?.let { runCatching { it.jsonObject["active_runs"]?.jsonPrimitive?.intOrNull }.getOrNull() }
                    ?: 0
                Health.Up(active, if (active > 0) "Working on $active run${if (active == 1) "" else "s"}" else null)
            }
            r.status == HttpStatusCode.NotFound -> plainHealth(target)   // older Hermes: /health only
            // Up but refusing the key, or down behind something answering 401? /health needs no key.
            r.status.value in AUTH_ERRORS -> if (plainHealth(target) is Health.Up) Health.KeyRejected
                else Health.Down("The agent answered HTTP ${r.status.value}.")
            else -> Health.Down("The agent answered HTTP ${r.status.value}.")
        }
    } catch (e: Exception) {
        Health.Down(reason(e, target.url))
    }

    private suspend fun plainHealth(target: ProfileTarget): Health {
        val r = http.get("${target.url}/health")
        return if (r.status.isSuccess()) Health.Up(0, null) else Health.Down("The agent answered HTTP ${r.status.value}.")
    }

    override suspend fun keyAccepted(target: ProfileTarget): Boolean? = try {
        val r = http.get("${target.url}/v1/capabilities") { bearerAuth(target.apiKey) }
        when {
            r.status.isSuccess() -> true
            r.status.value in AUTH_ERRORS -> false
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    override suspend fun capabilities(target: ProfileTarget): Capabilities? = try {
        val r = http.get("${target.url}/v1/capabilities") { bearerAuth(target.apiKey) }
        if (r.status.isSuccess()) parse(r)?.let { mapCapabilities(it, jobs = jobsAvailable(target)) } else null
    } catch (e: Exception) {
        null
    }

    private suspend fun jobsAvailable(target: ProfileTarget): Boolean = try {
        http.get("${target.url}/api/jobs") { bearerAuth(target.apiKey) }.status.isSuccess()
    } catch (e: Exception) {
        false
    }

    override suspend fun test(targets: List<ProfileTarget>): ConnectionTest {
        val checks = mutableListOf<Check>()
        var caps: Capabilities? = null
        for (t in targets) {
            val p = t.name
            // 1. reachable (no key needed for the answer to count)
            val health = health(t)
            if (health is Health.Down) {
                checks += Check("reachable", false, p, health.reason)
                continue
            }
            checks += Check("reachable", true, p)
            // 2. key + 3. capabilities
            val r = try {
                http.get("${t.url}/v1/capabilities") { bearerAuth(t.apiKey) }
            } catch (e: Exception) {
                checks += Check("auth", false, p, reason(e, t.url)); continue
            }
            if (r.status.value in AUTH_ERRORS) {
                checks += Check("auth", false, p, "The agent rejected the API key (HTTP ${r.status.value}). " +
                    "Use this profile's API_SERVER_KEY.")
                continue
            }
            if (r.status == HttpStatusCode.NotFound) {
                checks += Check("auth", true, p)
                checks += Check("capabilities", false, p, "This Hermes version has no /v1/capabilities. " +
                    "Update Hermes Agent (0.14 or later).")
                continue
            }
            if (!r.status.isSuccess()) {
                checks += Check("capabilities", false, p, "The agent answered HTTP ${r.status.value}.")
                continue
            }
            checks += Check("auth", true, p)
            val body = parse(r)
            if (body == null) {
                checks += Check("capabilities", false, p, "The agent's capabilities could not be read."); continue
            }
            val c = mapCapabilities(body, jobs = jobsAvailable(t))
            checks += Check("capabilities", true, p)
            // 4. runs: Switchboard chats through the Runs API
            checks += if (c.chat && c.streaming) Check("runs", true, p)
                else Check("runs", false, p, "The API server does not offer runs with streaming events. " +
                    "Update Hermes Agent and enable its API server.")
            if (caps == null) caps = c
        }
        return ConnectionTest(ok = checks.isNotEmpty() && checks.all { it.ok }, checks = checks, capabilities = caps)
    }

    private suspend fun parse(r: HttpResponse): JsonObject? =
        runCatching { hubJson.parseToJsonElement(r.bodyAsText()).jsonObject }.getOrNull()

    companion object {
        private val AUTH_ERRORS = setOf(401, 403)

        /** Hermes `/v1/capabilities` → the hub's agent-neutral capabilities. */
        fun mapCapabilities(body: JsonObject, jobs: Boolean): Capabilities {
            val f = body["features"]?.jsonObject ?: JsonObject(emptyMap())
            fun flag(name: String) = f[name]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true
            fun present(name: String) = f[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.isNotBlank() == true
            val authRequired = body["auth"]?.jsonObject?.get("required")?.jsonPrimitive?.booleanOrNull == true
            return Capabilities(
                chat = flag("run_submission"),
                streaming = flag("run_events_sse"),
                stop = flag("run_stop"),
                toolEvents = flag("tool_progress_events"),
                approvals = flag("run_approval_response") && flag("approval_events"),
                jobs = jobs,
                // 0.22 honours a run's model and provider; 0.14 needs a profile per model (hub-side, M3).
                modelSwitching = flag("model_options") || flag("session_model_lock"),
                perUserMemory = present("session_key_header") && authRequired,
                images = false,                         // unverified on the Runs API (findings doc)
                // 0.22 advertises no clarify flag; /v1/runs/{id}/clarify arrived with steer (both by 0.22).
                clarify = flag("run_steer"),
                steer = flag("run_steer"),
                reasoning = flag("reasoning_streaming"),
            )
        }

        fun reason(e: Throwable, url: String): String = when (e) {
            is ConnectException -> "Connection refused at $url — is the agent's API server running and bound to an address the hub can reach?"
            is UnknownHostException -> "Unknown host in $url — check the address (Tailscale MagicDNS name or LAN IP)."
            is io.ktor.client.plugins.HttpRequestTimeoutException,
            is io.ktor.client.network.sockets.ConnectTimeoutException,
            is io.ktor.client.network.sockets.SocketTimeoutException ->
                "No answer from $url within the timeout — the machine may be asleep or a firewall blocks the port."
            else -> "Could not reach $url (${e::class.simpleName}: ${e.message?.take(120)})."
        }

        fun client(): HttpClient = HttpClient(io.ktor.client.engine.cio.CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 10_000
            }
        }
    }
}

private fun HttpStatusCode.isSuccess() = value in 200..299
