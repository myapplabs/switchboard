package llc.applabs.switchboard.hub

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.utils.io.readUTF8Line
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import llc.applabs.switchboard.hub.FakeHermes.Step

/**
 * M1 end to end over a real HTTP server: Ktor's in-process test engine buffers streamed responses
 * until they end, so it cannot show a run's events while the run is still going.
 */
class RunsTest {
    class Ctx(val client: HttpClient)

    private val hermes = FakeHermes()
    private val db = Db.inMemory()
    private val hub = Hub(HubConfig(), db, SecretBox.random(), mapOf("hermes" to hermes.adapter), CoroutineScope(SupervisorJob()),
        healthIntervalMs = 600_000, runRetryDelaysMs = listOf(50, 50, 50))
    private lateinit var owner: HubUser
    private lateinit var token: String
    private lateinit var agentId: String

    private fun app(block: suspend Ctx.() -> Unit) = kotlinx.coroutines.runBlocking {
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { hubModule(hub) }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false; defaultRequest { url("http://127.0.0.1:$port") } }
        val hh = hub.accounts.ensureHub("Home")
        owner = hub.accounts.createUser(hh, "paulfranco", Role.owner)
        token = hub.accounts.issue(owner, "access", 900)
        agentId = hub.agents.add(owner, AgentInput("hermes", "cio-mini", "http://127.0.0.1:8642", listOf(ProfileInput("main", null, FakeHermes.KEY)))).id
        try { Ctx(client).block() } finally { client.close(); server.stop(50, 500) }
    }

    private suspend fun HttpResponse.json(): JsonObject = hubJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private suspend fun Ctx.post(path: String, body: String, as_: String = token) =
        client.post(path) { bearerAuth(as_); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun Ctx.newSession(): String = post("/v1/sessions", """{"agent_id":"$agentId"}""").json().str("id")

    private suspend fun Ctx.send(session: String, text: String): JsonObject {
        val r = post("/v1/sessions/$session/runs", """{"text":"$text"}""")
        assertEquals(HttpStatusCode.Accepted, r.status, r.bodyAsText())
        return r.json()
    }

    /** The run's SSE stream as the app reads it, each event validated against the contract. */
    private suspend fun Ctx.stream(runId: String, lastEventId: Long? = null, until: (JsonObject) -> Boolean = { false }): List<JsonObject> {
        val events = mutableListOf<JsonObject>()
        withTimeout(10_000) {
            client.prepareGet("/v1/runs/$runId/events") { bearerAuth(token); lastEventId?.let { header("Last-Event-ID", it.toString()) } }.execute { r ->
                assertEquals(HttpStatusCode.OK, r.status)
                val ch = r.bodyAsChannel()
                var name: String? = null
                while (true) {
                    val line = ch.readUTF8Line() ?: break
                    when {
                        line.startsWith("event:") -> name = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            val data = line.removePrefix("data:").trim()
                            assertEquals(emptyList(), ContractSchema.eventErrors(data), data)
                            val e = hubJson.parseToJsonElement(data).jsonObject
                            assertEquals(name, e.str("type"), "SSE event name = payload type")
                            events += e
                            if (until(e)) return@execute
                        }
                    }
                }
            }
        }
        return events
    }

    private suspend fun Ctx.messages(session: String) =
        client.get("/v1/sessions/$session/messages") { bearerAuth(token) }.json()["items"]!!.jsonArray.map { it.jsonObject }

    private suspend fun eventually(what: String, check: () -> Boolean) {
        withTimeout(10_000) { while (!check()) delay(20) }
        assertTrue(check(), what)
    }

    @Test fun `a recorded Hermes tool run streams through the hub and is stored`() = app {
        val session = newSession()
        val run = send(session, "what time is it?")
        val events = stream(run.str("id"))
        val types = events.map { it.str("type") }
        assertEquals(listOf("run.started", "tool.started", "tool.completed"), types.take(3))
        assertEquals("run.completed", types.last())
        assertEquals((1..events.size).map { it.toLong() }, events.map { it.str("seq").toLong() })
        val tool = events[1]
        assertEquals("call-eefa84b8-f427-4a36-901e-75cc768bede7", tool.str("tool_call_id"), "Hermes's own tool call id")
        assertEquals("date", tool.str("preview"))
        val text = events.filter { it.str("type") == "message.delta" }.joinToString("") { it.str("delta") }
        assertEquals("It's 8:32 PM PDT on Tuesday, October 20, 2026.", text, "leading blank lines trimmed")
        assertEquals(text, events.last().str("output"))

        val msgs = messages(session)
        assertEquals(listOf("user", "assistant"), msgs.map { it.str("role") })
        assertEquals("complete", msgs[1].str("status"))
        assertEquals(text, msgs[1].str("text"))
        assertEquals("done", msgs[1]["tool_calls"]!!.jsonArray.single().jsonObject.str("state"))

        val body = hermes.runBodies.single()
        assertEquals("sb_$session", body.str("session_id"), "the conversation's own Hermes session")
        assertFalse("conversation_history" in body, "a new conversation sends no history")
        assertEquals("switchboard:${owner.userId}", hermes.runHeaders.single()["session-key"], "per-user memory scope")
        assertEquals(run.str("id"), hermes.runHeaders.single()["idempotency"])
        eventually("usage and title stored") {
            hub.conversations.record(session)!!.let { it.inputTokens == 30489L && !it.titleIsDefault }
        }
        assertEquals("what time is it?", hub.conversations.record(session)!!.title, "no agent title: the first message")
    }

    @Test fun `hybrid - continue the agent session, or send history when it is gone`() = app {
        val session = newSession()
        stream(send(session, "what time is it?").str("id"))
        eventually("first run finished") { hub.conversations.activeRunId(session) == null }

        hermes.sessions["sb_$session"] = "Run date command and show time"
        stream(send(session, "and tomorrow?").str("id"))
        assertFalse("conversation_history" in hermes.runBodies[1], "Hermes has the session: no history")

        eventually("second run finished") { hub.conversations.activeRunId(session) == null }
        hermes.sessions.clear()
        stream(send(session, "and the day after?").str("id"))
        val history = hermes.runBodies[2]["conversation_history"]!!.jsonArray.map { it.jsonObject.str("role") to it.jsonObject.str("content") }
        assertEquals(listOf("user" to "what time is it?", "assistant" to "It's 8:32 PM PDT on Tuesday, October 20, 2026.", "user" to "and tomorrow?"),
            history.take(3), "the hub's copy, oldest first, without the new message")
        assertEquals(4, history.size)
    }

    @Test fun `the agent's session title becomes the conversation title`() = app {
        val session = newSession()
        hermes.sessions["sb_$session"] = "Run date command and show time"
        stream(send(session, "what time is it?").str("id"))
        eventually("title from Hermes") { hub.conversations.record(session)!!.title == "Run date command and show time" }
        assertEquals(0.0012, hub.conversations.record(session)!!.estimatedCostUsd)
    }

    @Test fun `a dropped agent stream reconnects without repeating events`() = app {
        val full = FakeHermes.recorded("run-tool-date")
        val cut = full.substring(0, full.indexOf("id: 4"))          // tool events and two deltas, then the line drops
        hermes.streams += listOf(Step.Text(cut))
        hermes.streams += listOf(Step.Text(full))                    // Hermes replays from the start
        val session = newSession()
        val events = stream(send(session, "what time is it?").str("id"))
        assertEquals(1, events.count { it.str("type") == "tool.started" })
        assertEquals("It's 8:32 PM PDT on Tuesday, October 20, 2026.", messages(session)[1].str("text"))
    }

    @Test fun `a run that finished while the stream was down is completed from its status`() = app {
        val full = FakeHermes.recorded("run-tool-date")
        hermes.streams += listOf(Step.Text(full.substring(0, full.indexOf("id: 2"))))
        hermes.streams += listOf(Step.Text(": open\n\n"))
        hermes.runStatus = """{"status":"completed","output":"It's 8:32 PM.","usage":{"input_tokens":10,"output_tokens":2}}"""
        val session = newSession()
        val events = stream(send(session, "time?").str("id"))
        assertEquals("run.completed", events.last().str("type"))
        assertEquals("It's 8:32 PM.", events.last().str("output"))
    }

    @Test fun `losing the agent for good ends the run as interrupted`() = app {
        repeat(5) { hermes.streams += listOf(Step.Text(": open\n\n")) }
        hermes.runStatus = null
        val session = newSession()
        val last = stream(send(session, "time?").str("id")).last()
        assertEquals("run.failed" to "run_interrupted", last.str("type") to last.str("code"))
        assertEquals("failed", messages(session)[1].str("status"))
    }

    @Test fun `stop ends the run with run_cancelled by the user`() = app {
        hermes.stopped = CompletableDeferred()
        val recorded = FakeHermes.recorded("run-stopped")
        val cancelAt = recorded.indexOf("id: 78")
        hermes.streams += listOf(Step.Text(recorded.substring(0, recorded.indexOf("id: 10"))), Step.Wait(hermes.stopped),
            Step.Text(recorded.substring(cancelAt)))
        val session = newSession()
        val runId = send(session, "a long story").str("id")
        stream(runId) { it.str("type") == "message.delta" && it.str("seq") == "5" }
        assertEquals(HttpStatusCode.Accepted, post("/v1/runs/$runId/stop", "{}").status)
        val rest = stream(runId, lastEventId = 5)
        assertEquals(listOf("run.status", "run.cancelled"), rest.map { it.str("type") }.filter { it != "message.delta" })
        assertEquals(owner.userId, rest.last().str("by"))
        assertEquals("cancelled", messages(session)[1].str("status"))
        assertEquals(HttpStatusCode.Conflict, post("/v1/runs/$runId/stop", "{}").status, "already finished")
    }

    @Test fun `the agent asks a question and the answer goes back`() = app {
        hermes.answered = CompletableDeferred()
        hermes.streams += listOf(
            Step.Text(": open\n\n" + FakeHermes.hermesEvent(0, """{"event": "clarify.request", "prompt_id": "clr-req-1", "execution_generation": 1, "question": "Which project?", "choices": ["forge", "switchboard"], "multi_select": false, "timeout_s": 120}""")),
            Step.Wait(hermes.answered),
            Step.Text(FakeHermes.hermesEvent(1, """{"event": "message.delta", "delta": "Backing up switchboard."}""") +
                FakeHermes.hermesEvent(2, """{"event": "run.completed", "output": "Backing up switchboard.", "usage": {"input_tokens": 5, "output_tokens": 4}}""")))
        val session = newSession()
        val runId = send(session, "back up a project").str("id")
        val asked = stream(runId) { it.str("type") == "clarify.requested" }.last()
        assertEquals("Which project?", asked.str("question"))
        assertEquals("waiting_for_input", client.get("/v1/runs/$runId") { bearerAuth(token) }.json().str("status"))
        val cid = asked.str("clarification_id")
        assertEquals(HttpStatusCode.OK, post("/v1/runs/$runId/clarifications/$cid", """{"answer":"switchboard"}""").status)
        val body = hermes.clarifyBodies.single()
        assertEquals(Triple("clr-req-1", "1", "switchboard"), Triple(body.str("request_id"), body.str("execution_generation"), body.str("answer")))
        assertEquals(HttpStatusCode.Conflict, post("/v1/runs/$runId/clarifications/$cid", """{"answer":"forge"}""").status, "answered once")
        val rest = stream(runId, lastEventId = asked.str("seq").toLong())
        assertEquals(listOf("clarify.answered", "run.status", "message.delta", "run.completed"), rest.map { it.str("type") })
        val clar = messages(session)[1]["clarifications"]!!.jsonArray.single().jsonObject
        assertEquals("answered" to "switchboard", clar.str("status") to clar.str("answer"))
    }

    @Test fun `steer adds guidance to a running run`() = app {
        val hold = CompletableDeferred<Unit>()
        hermes.streams += listOf(Step.Text(": open\n\n" + FakeHermes.hermesEvent(0, """{"event": "message.delta", "delta": "Working"}""")),
            Step.Wait(hold), Step.Text(FakeHermes.hermesEvent(1, """{"event": "run.completed", "output": "Working"}""")))
        val session = newSession()
        val runId = send(session, "do it").str("id")
        stream(runId) { it.str("type") == "message.delta" }
        assertEquals(HttpStatusCode.Accepted, post("/v1/runs/$runId/steer", """{"text":"use staging"}""").status)
        assertEquals("use staging", hermes.steerBodies.single().str("input"))
        hold.complete(Unit)
        val events = stream(runId)
        assertTrue(events.any { it.str("type") == "steer.accepted" && it.str("text") == "use staging" })
        assertEquals(listOf("message" to "user", "message" to "assistant", "steer" to "user"), messages(session).map { it.str("kind") to it.str("role") })
    }

    @Test fun `one run at a time, busy and offline agents are refused clearly`() = app {
        val hold = CompletableDeferred<Unit>()
        hermes.streams += listOf(Step.Text(": open\n\n"), Step.Wait(hold), Step.Text(FakeHermes.hermesEvent(0, """{"event": "run.completed", "output": "ok"}""")))
        val session = newSession()
        send(session, "first")
        val second = post("/v1/sessions/$session/runs", """{"text":"second"}""")
        assertEquals(HttpStatusCode.Conflict to "run_active", second.status to second.json().str("code"))
        hold.complete(Unit)
        eventually("first run done") { hub.conversations.activeRunId(session) == null }

        hermes.busy = true
        val busy = post("/v1/sessions/$session/runs", """{"text":"third"}""")
        assertEquals(HttpStatusCode.TooManyRequests to "agent_busy", busy.status to busy.json().str("code"))
        assertEquals("7", busy.json().str("retry_after_s"))
        hermes.busy = false

        hub.agents.setHealth(agentId, "offline", null, 0, seen = false)
        val offline = post("/v1/sessions/$session/runs", """{"text":"fourth"}""")
        assertEquals(HttpStatusCode.ServiceUnavailable to "agent_offline", offline.status to offline.json().str("code"))
        assertEquals(2, messages(session).size, "refused messages are not stored")
    }

    @Test fun `conversations are private, and members wait for grants`() = app {
        val session = newSession()
        val hh = hub.accounts.ensureHub("Home")
        val admin = hub.accounts.issue(hub.accounts.createUser(hh, "partner", Role.admin), "access", 900)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/sessions/$session") { bearerAuth(admin) }.status)
        assertEquals(0, client.get("/v1/sessions") { bearerAuth(admin) }.json()["items"]!!.jsonArray.size)
        val member = hub.accounts.issue(hub.accounts.createUser(hh, "kid", Role.member), "access", 900)
        assertEquals(HttpStatusCode.Forbidden, post("/v1/sessions", """{"agent_id":"$agentId"}""", member).status)
    }

    @Test fun `rename, switch model, list and search`() = app {
        val session = newSession()
        stream(send(session, "what time is it?").str("id"))
        val patched = client.patch("/v1/sessions/$session") { bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"title":"Clock","model_id":"openrouter::anthropic/claude-sonnet-5.5"}""") }.json()
        assertEquals("Clock" to "claude-sonnet-5.5", patched.str("title") to patched.str("model_label"))
        stream(send(session, "again").str("id"))
        val body = hermes.runBodies.last()
        assertEquals("openrouter" to "anthropic/claude-sonnet-5.5", body.str("provider") to body.str("model"),
            "Hermes's runs API takes provider and model as separate fields")
        assertEquals(1, client.get("/v1/sessions?q=8:32") { bearerAuth(token) }.json()["items"]!!.jsonArray.size, "search finds message text")
        assertEquals(0, client.get("/v1/sessions?q=nothing-like-this") { bearerAuth(token) }.json()["items"]!!.jsonArray.size)
    }

    @Test fun `deleting removes the conversation everywhere, later if the agent is offline`() = app {
        val s1 = newSession()
        stream(send(s1, "hello").str("id"))
        eventually("done") { hub.conversations.activeRunId(s1) == null }
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/sessions/$s1") { bearerAuth(token) }.status)
        assertEquals(listOf("sb_$s1"), hermes.deleted.toList())
        assertEquals(0, db.query("SELECT COUNT(*) FROM message") { it.getInt(1) }.single())
        assertEquals(0, db.query("SELECT COUNT(*) FROM run_event") { it.getInt(1) }.single())

        val s2 = newSession()
        stream(send(s2, "hello").str("id"))
        eventually("done") { hub.conversations.activeRunId(s2) == null }
        hermes.down = true
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/sessions/$s2") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/sessions/$s2") { bearerAuth(token) }.status, "gone for the app at once")
        assertEquals(1, db.query("SELECT COUNT(*) FROM agent_cleanup") { it.getInt(1) }.single())
        hermes.down = false
        db.update("UPDATE agent_cleanup SET next_at = ?", "2000-01-01T00:00:00Z")
        hub.conversations.cleanupOnce()
        assertEquals(0, db.query("SELECT COUNT(*) FROM agent_cleanup") { it.getInt(1) }.single())
        assertTrue("sb_$s2" in hermes.deleted)
    }

    @Test fun `after a hub restart an open run is resumed`() = app {
        val hold = CompletableDeferred<Unit>()
        val full = FakeHermes.recorded("run-tool-date")
        hermes.streams += listOf(Step.Text(full.substring(0, full.indexOf("id: 4"))), Step.Wait(hold))
        val session = newSession()
        val runId = send(session, "what time is it?").str("id")
        stream(runId) { it.str("seq") == "4" }
        // A second hub on the same database (the first one "crashed" mid-run).
        val restarted = Hub(HubConfig(), db, hub.secrets, mapOf("hermes" to hermes.adapter), CoroutineScope(SupervisorJob()),
            healthIntervalMs = 600_000, runRetryDelaysMs = listOf(50))
        hermes.streams += listOf(Step.Text(full))
        restarted.runs.recover()
        hold.complete(Unit)
        eventually("resumed and completed") { db.query("SELECT status FROM run WHERE id = ?", runId) { it.getString(1) }.single() == "completed" }
        val text = db.query("SELECT text FROM message WHERE run_id = ?", runId) { it.getString(1) }.single()
        assertEquals("It's 8:32 PM PDT on Tuesday, October 20, 2026.", text, "no duplicated text across the restart")
        assertNull(hub.conversations.activeRunId(session))
    }

    // ── approvals (M3) ─────────────────────────────────────────────────────

    private fun approvalStream(choices: String = "[\"once\", \"session\", \"always\", \"deny\"]") = listOf(
        Step.Text(": open\n\n" + FakeHermes.hermesEvent(0, """{"event": "approval.request", "request_id": "req-9", "execution_generation": 2, "command": "rm -rf ~/build-cache", "description": "recursive delete", "choices": $choices, "timeout_s": 60}""")),
        Step.Wait(hermes.approved),
        Step.Text(FakeHermes.hermesEvent(1, """{"event": "approval.responded", "request_id": "req-9", "choice": "once"}""") +
            FakeHermes.hermesEvent(2, """{"event": "message.delta", "delta": "Cleared."}""") +
            FakeHermes.hermesEvent(3, """{"event": "run.completed", "output": "Cleared."}""")))

    @Test fun `an approval goes to the owner and the decision back to the agent`() = app {
        hermes.approved = CompletableDeferred()
        hermes.streams += approvalStream()
        val session = newSession()
        val runId = send(session, "clear the build cache").str("id")
        val asked = stream(runId) { it.str("type") == "approval.requested" }.last()
        assertEquals("rm -rf ~/build-cache", asked.str("command"))
        assertEquals("true", asked.str("can_decide"))
        assertEquals(listOf("approve_once", "approve_session", "approve_always", "deny"), asked["allowed_decisions"]!!.jsonArray.map { it.jsonPrimitive.content })
        val pending = client.get("/v1/approvals") { bearerAuth(token) }.json()["items"]!!.jsonArray.single().jsonObject
        assertEquals(asked.str("approval_id"), pending.str("id"))
        assertEquals("waiting_for_approval", client.get("/v1/runs/$runId") { bearerAuth(token) }.json().str("status"))
        val decided = post("/v1/approvals/${pending.str("id")}/decision", """{"decision":"approve_once"}""")
        assertEquals(HttpStatusCode.OK, decided.status, decided.bodyAsText())
        assertEquals("approved", decided.json().str("status"))
        val body = hermes.approvalBodies.single()
        assertEquals(Triple("once", "req-9", "2"), Triple(body.str("choice"), body.str("request_id"), body.str("execution_generation")))
        val rest = stream(runId, lastEventId = asked.str("seq").toLong())
        assertEquals(listOf("approval.decided", "run.status", "message.delta", "run.completed"), rest.map { it.str("type") },
            "Hermes's own approval.responded for the same request is not announced twice")
        assertEquals(owner.userId, rest.first().str("decided_by"))
        assertEquals(HttpStatusCode.Conflict, post("/v1/approvals/${pending.str("id")}/decision", """{"decision":"deny"}""").status)
        assertTrue("approval_decided" in db.query("SELECT action FROM activity") { it.getString(1) })
    }

    @Test fun `who may decide - members their own, restricted never, always only the owner`() = app {
        val hh = hub.accounts.ensureHub("Home")
        val kid = hub.accounts.createUser(hh, "kid", Role.restricted); val kidToken = hub.accounts.issue(kid, "access", 900)
        val sam = hub.accounts.createUser(hh, "Sam", Role.member); val samToken = hub.accounts.issue(sam, "access", 900)
        val admin = hub.accounts.createUser(hh, "partner", Role.admin); val adminToken = hub.accounts.issue(admin, "access", 900)
        val profile = hub.agents.record(agentId)!!.profiles.first().id
        hub.permissions.setGrants(owner, agentId, AgentGrants(listOf(
            Grant(role = "member", canChat = true), Grant(role = "restricted", canChat = true, profileId = profile))))

        // The kid's run asks: the kid can't decide; the admin can, but not "always".
        hermes.approved = CompletableDeferred(); hermes.streams += approvalStream()
        val kidSession = post("/v1/sessions", """{"agent_id":"$agentId"}""", kidToken).json().str("id")
        val kidRun = post("/v1/sessions/$kidSession/runs", """{"text":"delete stuff"}""", kidToken).json().str("id")
        val seenByKid = withTimeout(10_000) { var a: JsonObject? = null
            while (a == null) { a = client.get("/v1/approvals") { bearerAuth(kidToken) }.json()["items"]!!.jsonArray.firstOrNull()?.jsonObject; delay(20) }; a }
        assertEquals(0, seenByKid["allowed_decisions"]!!.jsonArray.size, "the kid sees their request but can't decide")
        assertEquals(HttpStatusCode.Forbidden, post("/v1/approvals/${seenByKid.str("id")}/decision", """{"decision":"approve_once"}""", kidToken).status)
        val forAdmin = client.get("/v1/approvals") { bearerAuth(adminToken) }.json()["items"]!!.jsonArray.single().jsonObject
        assertFalse("approve_always" in forAdmin["allowed_decisions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(HttpStatusCode.Forbidden, post("/v1/approvals/${forAdmin.str("id")}/decision", """{"decision":"approve_always"}""", adminToken).status)
        assertEquals(0, client.get("/v1/approvals") { bearerAuth(samToken) }.json()["items"]!!.jsonArray.size, "not Sam's request")
        assertEquals(HttpStatusCode.OK, post("/v1/approvals/${forAdmin.str("id")}/decision", """{"decision":"deny"}""", adminToken).status)
        assertEquals("deny", hermes.approvalBodies.last().str("choice"))
        withTimeout(10_000) { while (hub.conversations.activeRunId(kidSession) != null) delay(20) }

        // Sam's own run: Sam decides it (no "always").
        hermes.approved = CompletableDeferred(); hermes.streams += approvalStream()
        val samSession = post("/v1/sessions", """{"agent_id":"$agentId"}""", samToken).json().str("id")
        post("/v1/sessions/$samSession/runs", """{"text":"clear cache"}""", samToken)
        val mine = withTimeout(10_000) { var a: JsonObject? = null
            while (a == null) { a = client.get("/v1/approvals") { bearerAuth(samToken) }.json()["items"]!!.jsonArray.firstOrNull()?.jsonObject; delay(20) }; a }
        assertEquals(listOf("approve_once", "approve_session", "deny"), mine["allowed_decisions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(HttpStatusCode.OK, post("/v1/approvals/${mine.str("id")}/decision", """{"decision":"approve_session"}""", samToken).status)
        assertEquals("session", hermes.approvalBodies.last().str("choice"))
        assertTrue(kidRun.isNotEmpty())
    }

    @Test fun `an approval decided elsewhere or never decided is closed`() = app {
        hermes.streams += listOf(Step.Text(": open\n\n" +
            FakeHermes.hermesEvent(0, """{"event": "approval.request", "request_id": "r1", "command": "reboot", "choices": ["once", "deny"]}""") +
            FakeHermes.hermesEvent(1, """{"event": "approval.responded", "request_id": "r1", "choice": "deny"}""") +
            FakeHermes.hermesEvent(2, """{"event": "approval.request", "request_id": "r2", "command": "shutdown", "choices": ["once", "deny"]}""") +
            FakeHermes.hermesEvent(3, """{"event": "run.completed", "output": "Done."}""")))
        val session = newSession()
        val events = stream(send(session, "restart").str("id"))
        val decided = events.first { it.str("type") == "approval.decided" }
        assertEquals("deny" to "agent", decided.str("decision") to decided.str("decided_by"), "decided in Telegram or by a timeout")
        assertEquals(listOf("denied", "expired"), db.query("SELECT status FROM approval ORDER BY created_at, rowid") { it.getString(1) })
    }
}
