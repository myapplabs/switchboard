package llc.applabs.switchboard.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import llc.applabs.switchboard.domain.HubException
import llc.applabs.switchboard.domain.SecureStore
import llc.applabs.switchboard.domain.model.Me
import llc.applabs.switchboard.domain.model.RunEvent

class MemoryStore : SecureStore {
    val values = mutableMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun set(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

private val json = headersOf(HttpHeaders.ContentType, "application/json")
private const val ME = """{"user_id":"usr_1","display_name":"paulfranco","role":"owner","hub":{"id":"hh_1","name":"Home"},"permissions":{"manage_members":true}}"""

class HubClientTest {
    private fun tokens(access: String? = "a1", refresh: String? = "r1") = TokenStore(MemoryStore()).apply {
        hubUrl = "http://hub:8780"; accessToken = access; refreshToken = refresh
    }

    @Test fun `hub addresses are normalised`() {
        assertEquals("http://10.0.0.158:8780", HubClient.normalizeHubUrl("10.0.0.158:8780"))
        assertEquals("https://mini-1.tail1234.ts.net", HubClient.normalizeHubUrl("mini-1.tail1234.ts.net/"))
        assertEquals("http://localhost:8780", HubClient.normalizeHubUrl("localhost:8780"))
        assertEquals("https://hub.example", HubClient.normalizeHubUrl(" https://hub.example/v1/ "))
    }

    @Test fun `a 401 refreshes once, rotates tokens and retries`() = runTest {
        val t = tokens()
        val seen = mutableListOf<String>()
        val engine = MockEngine { req ->
            seen += "${req.method.value} ${req.url.encodedPath} ${req.headers[HttpHeaders.Authorization]}"
            when (req.url.encodedPath) {
                "/v1/auth/refresh" -> {
                    assertEquals("""{"refresh_token":"r1"}""", (req.body as TextContent).text)
                    respond("""{"access_token":"a2","refresh_token":"r2","expires_in":900}""", HttpStatusCode.OK, json)
                }
                else -> if (req.headers[HttpHeaders.Authorization] == "Bearer a2") respond(ME, HttpStatusCode.OK, json)
                    else respond("""{"code":"unauthorized","title":"Sign in required","status":401}""", HttpStatusCode.Unauthorized, json)
            }
        }
        val client = HubClient(engine, t)
        val me: Me = client.call(HttpMethod.Get, "/me")
        assertEquals("paulfranco", me.displayName)
        assertEquals("a2" to "r2", t.accessToken to t.refreshToken)
        assertEquals(listOf("GET /v1/me Bearer a1", "POST /v1/auth/refresh null", "GET /v1/me Bearer a2"), seen)
    }

    @Test fun `a refused refresh signs this device out`() = runTest {
        val t = tokens()
        var signedOut = false
        val engine = MockEngine { respond("""{"code":"unauthorized","title":"Sign in required","status":401}""", HttpStatusCode.Unauthorized, json) }
        val client = HubClient(engine, t).apply { onSignedOut = { signedOut = true } }
        val e = assertFailsWith<HubException> { client.call<Me>(HttpMethod.Get, "/me") }
        assertEquals("unauthorized", e.code)
        assertTrue(signedOut)
        assertNull(t.accessToken)
        assertEquals("http://hub:8780", t.hubUrl, "the hub address is kept for signing in again")
    }

    @Test fun `problems keep their code, detail and retry time`() = runTest {
        val engine = MockEngine {
            respond("""{"code":"agent_busy","title":"Agent busy","status":429,"detail":"Try again shortly.","retry_after_s":7}""",
                HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType, "application/problem+json"))
        }
        val e = assertFailsWith<HubException> { HubClient(engine, tokens()).call<Me>(HttpMethod.Post, "/sessions/s/runs", "{}") }
        assertEquals(listOf("agent_busy", "Try again shortly.", "7"), listOf(e.code, e.friendly, e.retryAfterS.toString()))
    }

    @Test fun `typed request bodies are sent with their own serializer`() = runTest {
        var sent: String? = null
        val engine = MockEngine { req -> sent = (req.body as TextContent).text; respond(ME, HttpStatusCode.OK, json) }
        val body = llc.applabs.switchboard.domain.model.TaskInput("Plan meals", steps = listOf(llc.applabs.switchboard.domain.model.TaskStepInput(agentId = "agt_1")))
        HubClient(engine, tokens()).call<Me>(HttpMethod.Post, "/tasks", body)
        assertEquals("""{"title":"Plan meals","instructions":"","steps":[{"agent_id":"agt_1"}],"review_each_step":false,"column":"backlog","coordinated":false}""", sent)
    }

    @Test fun `an unreachable hub says so plainly`() = runTest {
        val engine = MockEngine { throw io.ktor.utils.io.errors.IOException("Connection refused") }
        val e = assertFailsWith<HubException> { HubClient(engine, tokens()).call<Me>(HttpMethod.Get, "/me") }
        assertEquals(HubException.UNREACHABLE, e.code)
        assertTrue("http://hub:8780" in e.friendly)
    }

    @Test fun `run events resume after a drop without repeats`() = runTest {
        val lastIds = mutableListOf<String?>()
        var calls = 0
        fun ev(seq: Int, body: String) = "id: $seq\nevent: x\ndata: {\"run_id\":\"run_1\",\"seq\":$seq,\"at\":\"2026-10-21T00:00:00Z\",$body}\n\n"
        val engine = MockEngine { req ->
            lastIds += req.headers["Last-Event-ID"]
            val text = if (calls++ == 0) ev(1, "\"type\":\"run.started\",\"session_id\":\"s\",\"agent_id\":\"a\",\"message_id\":\"m\"") +
                ev(2, "\"type\":\"message.delta\",\"delta\":\"Hel\"")                         // then the connection drops
            else ev(2, "\"type\":\"message.delta\",\"delta\":\"Hel\"") + ev(3, "\"type\":\"message.delta\",\"delta\":\"lo\"") +
                ev(4, "\"type\":\"run.completed\",\"output\":\"Hello\"")
            respond(": ping\n\n$text", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val repo = ChatRepositoryImpl(HubClient(engine, tokens()), retryDelaysMs = listOf(1, 1))
        val events = repo.events("run_1").toList()
        assertEquals(listOf(1L, 2L, 3L, 4L), events.map { it.seq })
        assertEquals("Hello", events.filterIsInstance<RunEvent.Delta>().joinToString("") { it.text })
        assertTrue(events.last() is RunEvent.Completed)
        assertEquals(listOf(null, "2"), lastIds, "resumed after the last event seen")
    }
}
