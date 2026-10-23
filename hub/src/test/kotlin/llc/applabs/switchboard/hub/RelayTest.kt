package llc.applabs.switchboard.hub

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Scheduled-job results through Hermes's relay: setup, the gateway's connection, delivery into a conversation. */
class RelayTest {
    private val hermes = FakeHermes()
    private val sent = CopyOnWriteArrayList<JsonObject>()
    private val push = HttpClient(MockEngine { req ->
        sent += hubJson.parseToJsonElement((req.body as TextContent).text).jsonObject
        respond("{}", HttpStatusCode.Accepted)
    })
    private val db = Db.inMemory()
    private val hub = Hub(HubConfig(pushRelayUrl = "https://relay.test"), db, SecretBox.random(), mapOf("hermes" to hermes.adapter),
        CoroutineScope(SupervisorJob()), healthIntervalMs = 600_000, relayHttp = push)
    private lateinit var owner: HubUser
    private lateinit var token: String
    private lateinit var agentId: String

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { hubModule(hub) }
        val hh = hub.accounts.ensureHub("Home")
        owner = hub.accounts.createUser(hh, "paulfranco", Role.owner)
        token = hub.accounts.signIn(owner, "Pixel 9", null, "android").accessToken
        agentId = hub.agents.add(owner, AgentInput("hermes", "cio-mini", "http://127.0.0.1:8642", listOf(ProfileInput("main", null, FakeHermes.KEY)))).id
        block()
    }

    private suspend fun HttpResponse.json(): JsonObject = hubJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private suspend fun ApplicationTestBuilder.call(method: String, path: String, body: String = "{}") = when (method) {
        "post" -> client.post(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }
        "patch" -> client.patch(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }
        "delete" -> client.delete(path) { bearerAuth(token) }
        else -> client.get(path) { bearerAuth(token) }
    }

    /** What Hermes's `make_upgrade_token` sends. */
    private fun hermesToken(gatewayId: String, secret: String, exp: Long = Instant.now().epochSecond + 300): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$gatewayId:$exp:${Relay.hmacHex(secret, "$gatewayId:$exp")}".toByteArray())

    private suspend fun ApplicationTestBuilder.setUp(): Pair<String, String> {
        val setup = call("post", "/v1/agents/$agentId/relay/setup")
        assertEquals(HttpStatusCode.Created, setup.status, setup.bodyAsText())
        val code = setup.json().str("code")
        assertEquals("waiting", call("get", "/v1/agents/$agentId/relay").json().str("status"))
        val script = client.get("/relay/setup/$code") { header("Host", "cio.example.ts.net") }
        assertEquals(HttpStatusCode.OK, script.status)
        val text = script.bodyAsText()
        assertTrue("set_var GATEWAY_RELAY_URL 'https://cio.example.ts.net'" in text, text)
        assertEquals(HttpStatusCode.NotFound, client.get("/relay/setup/$code") { header("Host", "cio.example.ts.net") }.status, "one use")
        fun v(k: String) = Regex("set_var $k '([^']+)'").find(text)!!.groupValues[1]
        return v("GATEWAY_RELAY_ID") to v("GATEWAY_RELAY_SECRET")
    }

    @Test fun `a gateway connects with its secret and posts a job's result into a conversation`() = app {
        post("/v1/devices", """{"platform":"android","push_token":"fcm-${"t".repeat(150)}","relay_grant":"g"}""")
        val (gatewayId, secret) = setUp()
        hermes.jobList[0] = hermes.jobList[0].replace("\"enabled\":true", "\"enabled\":true,\"deliver\":\"telegram\"")
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/relay", request = { header("Authorization", "Bearer ${hermesToken(gatewayId, "wrong")}") }) {
            assertEquals(Relay.UNAUTHORIZED, closeReason.await()?.code, "a wrong secret is refused")
        }

        val on = call("patch", "/v1/agents/$agentId/jobs/j1", """{"results_here":true}""").json()
        assertEquals("true", on.str("results_here"))
        val deliver = hubJson.parseToJsonElement(hermes.jobList[0]).jsonObject.str("deliver")
        val deliveryId = deliver.substringAfter("telegram,relay:")
        assertTrue(deliver.startsWith("telegram,relay:jd_"), "keeps Telegram: $deliver")

        ws.webSocket("/relay", request = { header("Authorization", "Bearer ${hermesToken(gatewayId, secret)}") }) {
            send(Frame.Text("""{"type":"hello","platform":"relay","botId":""}""" + "\n"))
            val d = hubJson.parseToJsonElement((incoming.receive() as Frame.Text).readText().trim()).jsonObject
            assertEquals("descriptor", d.str("type"))
            val desc = d["descriptor"]!!.jsonObject
            assertEquals(listOf("send", "typing"), desc["supported_ops"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("1", desc.str("contract_version"))
            assertEquals("connected", call("get", "/v1/agents/$agentId/relay").json().str("status"))

            suspend fun outbound(action: String): JsonObject {
                send(Frame.Text("""{"type":"outbound","requestId":"r1","action":$action}""" + "\n"))
                val r = hubJson.parseToJsonElement((incoming.receive() as Frame.Text).readText().trim()).jsonObject
                assertEquals("outbound_result" to "r1", r.str("type") to r.str("requestId"))
                return r["result"]!!.jsonObject
            }
            val ok = outbound("""{"op":"send","chat_id":"$deliveryId","content":"**Today:** 3 stories"}""")
            assertEquals("true", ok.str("success"))
            assertEquals("false", outbound("""{"op":"send","chat_id":"jd_someone_else","content":"x"}""").str("success"), "only its own jobs")
            assertEquals("false", outbound("""{"op":"send_media","chat_id":"$deliveryId","media_kind":"image","source_url":"https://x"}""").str("success"))
            assertEquals("true", outbound("""{"op":"typing","chat_id":"$deliveryId"}""").str("success"))
        }

        val job = call("get", "/v1/agents/$agentId/jobs/j1").json()
        val sessionId = job.str("results_session_id")
        val session = call("get", "/v1/sessions/$sessionId").json()
        assertEquals("Daily digest", session.str("title"))
        val messages = call("get", "/v1/sessions/$sessionId/messages").json()["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("job_result" to "**Today:** 3 stories"), messages.map { it.str("kind") to it.str("text") })
        withTimeout(10_000) { while (sent.none { it.str("kind") == "job_finished" }) delay(20) }
        assertEquals(setOf("token", "grant", "kind", "hub", "session_id", "agent_id", "job_id"), sent.first { it.str("kind") == "job_finished" }.keys)

        // A reply there gives the agent the job's result, which its own session never saw.
        call("post", "/v1/sessions/$sessionId/runs", """{"text":"tell me more about the second"}""")
        withTimeout(10_000) { while (hermes.runBodies.isEmpty()) delay(20) }
        val input = hermes.runBodies.last().str("input")
        assertTrue(input.startsWith("[Your scheduled job posted this here earlier:]\n**Today:** 3 stories") && input.endsWith("tell me more about the second"), input)

        val off = call("patch", "/v1/agents/$agentId/jobs/j1", """{"results_here":false}""").json()
        assertEquals("false", off.str("results_here"))
        assertEquals("telegram", hubJson.parseToJsonElement(hermes.jobList[0]).jsonObject.str("deliver"))
        assertNull(hub.relay.deliver(agentId, deliveryId, "late"), "turned off: refused")
    }

    @Test fun `tokens - expired, never-expiring and replaced secrets are refused`() = app {
        val (gatewayId, secret) = setUp()
        assertEquals(agentId, hub.relay.authenticate("Bearer ${hermesToken(gatewayId, secret)}"))
        assertNull(hub.relay.authenticate("Bearer ${hermesToken(gatewayId, secret, exp = Instant.now().epochSecond - 1)}"))
        assertNull(hub.relay.authenticate("Bearer ${hermesToken(gatewayId, secret, exp = 0)}"))
        assertNull(hub.relay.authenticate("Bearer garbage"))
        assertNull(hub.relay.authenticate(null))
        val again = setUp()
        assertNull(hub.relay.authenticate("Bearer ${hermesToken(gatewayId, secret)}"), "a new setup replaces the old secret")
        assertNotNull(hub.relay.authenticate("Bearer ${hermesToken(again.first, again.second)}"))
        assertEquals(HttpStatusCode.NoContent, call("delete", "/v1/agents/$agentId/relay").status)
        assertNull(hub.relay.authenticate("Bearer ${hermesToken(again.first, again.second)}"), "removed")
        val refused = call("patch", "/v1/agents/$agentId/jobs/j1", """{"results_here":true}""")
        assertEquals(HttpStatusCode.Conflict to "relay_not_set_up", refused.status to refused.json().str("code"))
        assertEquals("off", call("get", "/v1/agents/$agentId/relay").json().str("status"))
    }

    @Test fun `the setup script stays plain - only a host's characters reach it`() {
        assertNull(Relay.safeHubUrl("https", "evil.com'; rm -rf ~; '"))
        assertEquals("http://127.0.0.1:8780", Relay.safeHubUrl("http", "127.0.0.1:8780"))
        val s = Relay.script("https://hub.example", "sb-abc", "s3cr3t")
        assertTrue("chmod 600" in s && "umask 077" in s)
    }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String) = call("post", path, body)
}
