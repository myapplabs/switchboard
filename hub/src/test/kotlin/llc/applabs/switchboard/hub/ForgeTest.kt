package llc.applabs.switchboard.hub

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** FORGE tickets on the board (F37): a fake bridge answers the way `forge switchboard connect` does. */
class ForgeTest {
    private val db = Db.inMemory()
    private val hub = Hub(HubConfig(), db, SecretBox.random(), emptyMap(), CoroutineScope(SupervisorJob()), healthIntervalMs = 600_000)
    private lateinit var owner: HubUser
    private lateinit var token: String
    private val seen = Collections.synchronizedList(mutableListOf<Pair<String, JsonObject>>())

    private fun app(block: suspend (HttpClient) -> Unit) = runBlocking {
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { hubModule(hub) }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false; defaultRequest { url("http://127.0.0.1:$port") }; engine { requestTimeout = 60_000 } }
        val hh = hub.accounts.ensureHub("Home")
        owner = hub.accounts.createUser(hh, "paulfranco", Role.owner)
        token = hub.accounts.issue(owner, "access", 900)
        try { block(client) } finally { client.close(); server.stop(50, 500) }
    }

    private suspend fun HttpResponse.json(): JsonObject = hubJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    /** What FORGE's tools answer (structuredContent; lists come as {"items": …}). */
    private fun answer(tool: String, args: JsonObject): String = when (tool) {
        "forge_projects" -> """{"items":[{"name":"Gameplay"}]}"""
        "forge_attention" -> """{"waiting_for_owner":[{"project":"Gameplay","run_id":"20261021-plan","phase":"PLAN_REVIEW","waiting_for":"plan approval"}]}"""
        "forge_backlog" -> """{"available":true,"tickets":[
            {"key":"T-001","title":"Score screen","status":"done","milestone":"M1","runs":["20261020-a"]},
            {"key":"T-002","title":"Leaderboard","status":"in_progress","milestone":"M1","runs":["20261021-plan"]},
            {"key":"T-003","title":"Sound","status":"in_progress","runs":["20261021-b"]},
            {"key":"T-004","title":"Settings","status":"in_review","runs":["20261021-c"]},
            {"key":"T-005","title":"Themes","status":"todo","grade":"READY","runs":[]}]}"""
        "forge_decide" -> """{"gate":"plan","phase":"EXECUTING"}"""
        "forge_start_ticket" -> if (args["confirmed"]?.jsonPrimitive?.content == "true") """{"started":true,"run_id":"20261021-new"}"""
            else """{"started":false,"ask_the_owner":true,"spec_verdict":"READY","spec_score":96,"estimate_usd":{"low":1.28,"typical":2.48,"high":4.1,"basis":"12 runs"}}"""
        else -> """{}"""
    }

    /** A bridge loop: poll, answer each call like FORGE, post the results. */
    private fun CoroutineScope.bridge(client: HttpClient, bridgeToken: String): Job = launch {
        while (isActive) {
            val r = client.post("/v1/forge/bridge/poll") { bearerAuth(bridgeToken) }
            if (r.status != HttpStatusCode.OK) { delay(50); continue }
            val calls = r.json()["calls"]!!.jsonArray.map { it.jsonObject }
            if (calls.isEmpty()) continue
            val results = calls.joinToString(",") { c ->
                val tool = c.str("tool"); val args = c["arguments"]!!.jsonObject
                seen += tool to args
                """{"id":"${c.str("id")}","ok":true,"result":${answer(tool, args)}}"""
            }
            client.post("/v1/forge/bridge/results") { bearerAuth(bridgeToken); contentType(ContentType.Application.Json); setBody("""{"results":[$results]}""") }
        }
    }

    /** A connection made in the app, then paired where FORGE runs with the one-time code. */
    private suspend fun HttpClient.newBridge(as_: String = token): Pair<HttpResponse, String?> {
        val r = post("/v1/forge/bridges") { bearerAuth(as_); contentType(ContentType.Application.Json); setBody("""{"name":"laptop"}""") }
        if (r.status != HttpStatusCode.Created) return r to null
        val created = r.json()
        assertTrue("token" !in created, "the app never sees the token")
        val code = created.str("code")
        val paired = post("/v1/forge/bridge/pair") { contentType(ContentType.Application.Json); setBody("""{"code":"${code.lowercase()}"}""") }
        assertEquals(HttpStatusCode.OK, paired.status, paired.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, post("/v1/forge/bridge/pair") { contentType(ContentType.Application.Json); setBody("""{"code":"$code"}""") }.status,
            "a code works once")
        return r to paired.json().str("token")
    }

    @Test fun `tickets become cards, a waiting run needs you, and decisions go to FORGE as the person's`() = app { client ->
        assertEquals(false, client.get("/v1/forge/cards") { bearerAuth(token) }.json().str("connected").toBoolean(), "nothing connected yet")
        val (_, bridgeToken) = client.newBridge()
        assertTrue(bridgeToken!!.startsWith("fbt_"))
        val loop = CoroutineScope(SupervisorJob()).bridge(client, bridgeToken)
        withTimeout(5_000) { while (client.get("/v1/forge/bridges") { bearerAuth(token) }.json()["items"]!!.jsonArray.none { it.jsonObject.str("connected") == "true" }) delay(20) }

        val board = client.get("/v1/forge/cards") { bearerAuth(token) }.json()
        assertEquals("true", board.str("connected"))
        val cards = board["cards"]!!.jsonArray.map { it.jsonObject }.associateBy { it.str("key") }
        assertEquals(mapOf("T-001" to "done", "T-002" to "needs_you", "T-003" to "working", "T-004" to "review", "T-005" to "backlog"),
            cards.mapValues { it.value.str("column") })
        assertEquals("plan approval" to "20261021-plan", cards["T-002"]!!.str("waiting_for") to cards["T-002"]!!.str("run_id"))
        assertEquals("forge:Gameplay:T-005", cards["T-005"]!!.str("id"))

        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/forge/runs/20261021-plan/decide") { bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"decision":"reject"}""") }.status, "a rejection needs a note")
        val decided = client.post("/v1/forge/runs/20261021-plan/decide") { bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"decision":"approve"}""") }
        assertEquals(HttpStatusCode.OK, decided.status, decided.bodyAsText())
        val args = seen.last { it.first == "forge_decide" }.second
        assertEquals("approve", args.str("decision"))
        assertEquals("paulfranco chose approve in the Switchboard app.", args.str("owner_said"), "FORGE records whose decision it was, and where")

        val preview = client.post("/v1/forge/tickets/start") { bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"project":"Gameplay","ticket":"T-005"}""") }.json()
        assertEquals("false" to "2.48", preview.str("started") to preview["estimate_usd"]!!.jsonObject.str("typical"), "a preview spends nothing; FORGE's estimate is a range")
        val started = client.post("/v1/forge/tickets/start") { bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"project":"Gameplay","ticket":"T-005","confirmed":true}""") }.json()
        assertEquals("true", started.str("started"))
        loop.cancel()
    }

    @Test fun `bridges are personal, tokens are checked, and only FORGE's board tools are relayed`() = app { client ->
        val hh = hub.accounts.ensureHub("Home")
        val sam = hub.accounts.createUser(hh, "Sam", Role.member); val samToken = hub.accounts.issue(sam, "access", 900)
        val kid = hub.accounts.createUser(hh, "kid", Role.restricted); val kidToken = hub.accounts.issue(kid, "access", 900)
        assertEquals(HttpStatusCode.Forbidden, client.newBridge(kidToken).first.status)
        val (_, mine) = client.newBridge()
        val loop = CoroutineScope(SupervisorJob()).bridge(client, mine!!)
        withTimeout(5_000) { while (client.get("/v1/forge/cards") { bearerAuth(token) }.json().str("connected") != "true") delay(20) }
        assertEquals("false", client.get("/v1/forge/cards") { bearerAuth(samToken) }.json().str("connected"), "Sam sees no one else's FORGE")
        assertTrue(client.get("/v1/forge/bridges") { bearerAuth(samToken) }.json()["items"]!!.jsonArray.isEmpty())

        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/forge/bridge/poll") { bearerAuth("fbt_not-a-real-token") }.status)
        val stale = client.post("/v1/forge/bridges") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"name":"old"}""") }.json()
        db.update("UPDATE forge_bridge SET pair_expires_at = '2020-01-01T00:00:00Z' WHERE id = ?", stale["bridge"]!!.jsonObject.str("id"))
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/forge/bridge/pair") { contentType(ContentType.Application.Json)
            setBody("""{"code":"${stale.str("code")}"}""") }.status, "codes expire")
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/forge/bridge/poll") { bearerAuth(token) }.status, "a person's token isn't a bridge token")
        val forbidden = runCatching { hub.forge.call(owner, "forge_abandon") }.exceptionOrNull() as HubException
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        val id = client.get("/v1/forge/bridges") { bearerAuth(token) }.json()["items"]!!.jsonArray.first { it.jsonObject.str("name") == "laptop" }.jsonObject.str("id")
        loop.cancel()
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/forge/bridges/$id") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/forge/bridge/poll") { bearerAuth(mine) }.status, "revoked")
        assertEquals("false", client.get("/v1/forge/cards") { bearerAuth(token) }.json().str("connected"))
    }
}
