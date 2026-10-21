package llc.applabs.switchboard.hub

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** M2: invites, members, grants and routing, models and limits, shared conversations, activity, devices. */
class MembersTest {
    private val hermes = FakeHermes()
    private val db = Db.inMemory()
    private val hub = Hub(HubConfig(publicUrl = "https://mini-1.tail1234.ts.net"), db, SecretBox.random(), mapOf("hermes" to hermes.adapter),
        CoroutineScope(SupervisorJob()), healthIntervalMs = 600_000, runRetryDelaysMs = listOf(50))
    private lateinit var owner: HubUser
    private lateinit var ownerToken: String
    private lateinit var agentId: String
    private lateinit var fullProfile: String
    private lateinit var safeProfile: String

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { hubModule(hub) }
        val hh = hub.accounts.ensureHub("Home")
        owner = hub.accounts.createUser(hh, "paulfranco", Role.owner)
        ownerToken = hub.accounts.issue(owner, "access", 900)
        val agent = hub.agents.add(owner, AgentInput("hermes", "cio-mini", "http://127.0.0.1:8642",
            listOf(ProfileInput("full", 8642, FakeHermes.KEY), ProfileInput("safe", 8643, FakeHermes.KEY))))
        agentId = agent.id
        fullProfile = agent.profiles!![0].id; safeProfile = agent.profiles!![1].id
        block()
    }

    private suspend fun HttpResponse.json(): JsonObject = hubJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private suspend fun ApplicationTestBuilder.post(path: String, body: String, token: String = ownerToken) =
        client.post(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun ApplicationTestBuilder.get(path: String, token: String = ownerToken) = client.get(path) { bearerAuth(token) }
    private suspend fun ApplicationTestBuilder.grants(body: String) =
        client.put("/v1/agents/$agentId/grants") { bearerAuth(ownerToken); contentType(ContentType.Application.Json); setBody(body) }

    private fun person(name: String, role: Role): Pair<HubUser, String> {
        val u = hub.accounts.createUser(hub.accounts.ensureHub("Home"), name, role)
        return u to hub.accounts.issue(u, "access", 900)
    }

    private suspend fun runFinished(sessionId: String) = withTimeout(10_000) { while (hub.conversations.activeRunId(sessionId) != null) delay(20) }

    // ── invites ────────────────────────────────────────────────────────────

    @Test fun `a member joins with an invite and a passkey`() = app {
        val invite = post("/v1/invites", """{"role":"member","note":"for Sam"}""").json()
        val code = invite.str("code")
        assertTrue(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}").matches(code))
        assertTrue(invite.str("url").startsWith("https://switchboard.applabs.llc/join#hub=https%3A%2F%2Fmini-1.tail1234.ts.net&code="), "code only in the fragment")
        val preview = client.get("/v1/invites/$code").json()
        assertEquals(listOf("Home", "paulfranco", "member"), listOf(preview.str("hub_name"), preview.str("invited_by"), preview.str("role")))

        val phone = SoftAuthenticator(SWITCHBOARD_RP_ID, "https://switchboard.applabs.llc")
        val ceremony = post("/v1/auth/passkeys/register/options", """{"invite_code":"${code.lowercase()}","display_name":"Sam"}""", "").json()
        val session = post("/v1/auth/passkeys/register/verify", buildJsonObject {
            put("ceremony_id", ceremony.str("ceremony_id")); put("credential", phone.create(ceremony["options"]!!.jsonObject)); put("platform", "ios")
        }.toString(), "").json()
        assertEquals("member" to "Sam", session["me"]!!.jsonObject.let { it.str("role") to it.str("display_name") })
        val samToken = session.str("access_token")
        assertEquals("ios", get("/v1/devices", samToken).json()["items"]!!.jsonArray.single().jsonObject.str("platform"))
        assertEquals("true", get("/v1/devices", samToken).json()["items"]!!.jsonArray.single().jsonObject.str("this_device"))

        val again = client.get("/v1/invites/$code")
        assertEquals(HttpStatusCode.Gone to "invite_used", again.status to again.json().str("code"))
        assertTrue(get("/v1/members").json()["items"]!!.jsonArray.any { it.jsonObject.str("display_name") == "Sam" })
        assertTrue("member_joined" in db.query("SELECT action FROM activity") { it.getString(1) })
    }

    @Test fun `invites expire, can be revoked, and only the owner invites admins`() = app {
        val inv = post("/v1/invites", """{"role":"restricted"}""").json()
        db.update("UPDATE invite SET expires_at = ? WHERE id = ?", "2000-01-01T00:00:00Z", inv.str("id"))
        assertEquals("invite_expired", client.get("/v1/invites/${inv.str("code")}").json().str("code"))
        val inv2 = post("/v1/invites", """{"role":"member"}""").json()
        assertEquals(1, get("/v1/invites").json()["items"]!!.jsonArray.size, "the expired one is not listed")
        assertFalse("code" in get("/v1/invites").json()["items"]!!.jsonArray.single().jsonObject.keys.filter { k ->
            get("/v1/invites").json()["items"]!!.jsonArray.single().jsonObject[k].toString() != "null" }, "codes are shown once")
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/invites/${inv2.str("id")}") { bearerAuth(ownerToken) }.status)
        assertEquals("invite_expired", client.get("/v1/invites/${inv2.str("code")}").json().str("code"))
        val (_, admin) = person("partner", Role.admin)
        assertEquals(HttpStatusCode.Forbidden, post("/v1/invites", """{"role":"admin"}""", admin).status)
        assertEquals(HttpStatusCode.Created, post("/v1/invites", """{"role":"member"}""", admin).status)
        assertEquals(HttpStatusCode.BadRequest, post("/v1/invites", """{"role":"owner"}""").status)
    }

    // ── members ────────────────────────────────────────────────────────────

    @Test fun `role changes follow the rules`() = app {
        val (admin, adminToken) = person("partner", Role.admin)
        val (kid, kidToken) = person("kid", Role.member)
        assertEquals("restricted", client.patch("/v1/members/${kid.userId}") { bearerAuth(adminToken); contentType(ContentType.Application.Json)
            setBody("""{"role":"restricted"}""") }.json().str("role"))
        assertEquals(HttpStatusCode.Forbidden, client.patch("/v1/members/${kid.userId}") { bearerAuth(adminToken); contentType(ContentType.Application.Json)
            setBody("""{"role":"admin"}""") }.status, "only the owner makes admins")
        assertEquals(HttpStatusCode.Forbidden, client.patch("/v1/members/${owner.userId}") { bearerAuth(adminToken); contentType(ContentType.Application.Json)
            setBody("""{"role":"member"}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/members", kidToken).status)
        assertEquals("member", client.patch("/v1/members/${admin.userId}") { bearerAuth(ownerToken); contentType(ContentType.Application.Json)
            setBody("""{"role":"member"}""") }.json().str("role"))
    }

    @Test fun `removing a member signs them out and deletes their conversations everywhere`() = app {
        val (sam, samToken) = person("Sam", Role.member)
        grants("""{"grants":[{"user_id":"${sam.userId}","can_chat":true,"profile_id":"$safeProfile"}]}""")
        val session = post("/v1/sessions", """{"agent_id":"$agentId"}""", samToken).json().str("id")
        post("/v1/sessions/$session/runs", """{"text":"hi"}""", samToken)
        runFinished(session)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/members/${sam.userId}") { bearerAuth(ownerToken) }.status)
        assertEquals(HttpStatusCode.Unauthorized, get("/v1/me", samToken).status)
        assertEquals(0, db.query("SELECT COUNT(*) FROM chat_session") { it.getInt(1) }.single())
        assertTrue("sb_$session" in hermes.deleted, "the agent's copy too")
        assertFalse(get("/v1/members").json()["items"]!!.jsonArray.any { it.jsonObject.str("user_id") == sam.userId })
    }

    // ── grants, profiles, models, limits ───────────────────────────────────

    @Test fun `members see and use only granted agents, on their granted profile`() = app {
        val (sam, samToken) = person("Sam", Role.member)
        assertEquals(0, get("/v1/agents", samToken).json()["items"]!!.jsonArray.size, "deny by default")
        assertEquals(HttpStatusCode.Forbidden, post("/v1/sessions", """{"agent_id":"$agentId"}""", samToken).status)
        assertEquals(HttpStatusCode.OK, grants("""{"grants":[{"role":"member","can_chat":true,"profile_id":"$safeProfile","daily_run_limit":1}]}""").status)
        val seen = get("/v1/agents", samToken).json()["items"]!!.jsonArray.single().jsonObject
        assertEquals("true", seen["my_permissions"]!!.jsonObject.str("chat"))
        assertFalse(seen.containsKey("profiles") && seen["profiles"].toString() != "null", "profiles and keys are the owner's")
        val session = post("/v1/sessions", """{"agent_id":"$agentId"}""", samToken).json().str("id")
        assertEquals(HttpStatusCode.Accepted, post("/v1/sessions/$session/runs", """{"text":"hi"}""", samToken).status)
        assertEquals("8643", hermes.runHeaders.last()["port"], "routed to the safe profile")
        runFinished(session)
        val limited = post("/v1/sessions/$session/runs", """{"text":"again"}""", samToken)
        assertEquals(HttpStatusCode.TooManyRequests to "limit_reached", limited.status to limited.json().str("code"))
        // A person's own grant replaces the role's
        grants("""{"grants":[{"role":"member","can_chat":true,"profile_id":"$safeProfile","daily_run_limit":1},
                             {"user_id":"${sam.userId}","can_chat":true,"profile_id":"$fullProfile"}]}""")
        assertEquals(HttpStatusCode.Accepted, post("/v1/sessions/$session/runs", """{"text":"again"}""", samToken).status)
        assertEquals("8642", hermes.runHeaders.last()["port"])
        runFinished(session)
        // The owner's runs use the first (most capable) profile
        val mine = post("/v1/sessions", """{"agent_id":"$agentId"}""").json().str("id")
        post("/v1/sessions/$mine/runs", """{"text":"hi"}""")
        assertEquals("8642", hermes.runHeaders.last()["port"])
        runFinished(mine)
    }

    @Test fun `grant validation`() = app {
        val (admin, adminToken) = person("partner", Role.admin)
        assertEquals(HttpStatusCode.Forbidden, client.put("/v1/agents/$agentId/grants") { bearerAuth(adminToken); contentType(ContentType.Application.Json)
            setBody("""{"grants":[]}""") }.status, "grants are the owner's")
        assertEquals(HttpStatusCode.BadRequest, grants("""{"grants":[{"role":"restricted","can_chat":true}]}""").status, "restricted must name a profile")
        assertEquals(HttpStatusCode.BadRequest, grants("""{"grants":[{"role":"member","user_id":"${admin.userId}","can_chat":true}]}""").status)
        assertEquals(HttpStatusCode.BadRequest, grants("""{"grants":[{"user_id":"${owner.userId}","can_chat":false}]}""").status)
        assertEquals(HttpStatusCode.BadRequest, grants("""{"grants":[{"role":"member","can_chat":true,"profile_id":"prf_nope"}]}""").status)
        assertEquals(HttpStatusCode.OK, grants("""{"grants":[{"role":"restricted","can_chat":true,"profile_id":"$safeProfile"}]}""").status)
        assertEquals(1, get("/v1/agents/$agentId/grants").json()["grants"]!!.jsonArray.size)
    }

    @Test fun `models - the default plus granted ones, with limits`() = app {
        val all = get("/v1/agents/$agentId/models").json()["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals("openrouter::nvidia/nemotron-3-ultra-550b-a55b:free", all.first().str("id"))
        assertEquals("true" to "false", all.first().str("is_default") to all.first().str("is_paid"))
        assertEquals(4, all.size, "owner: every model from authenticated providers (3 OpenRouter + 1 Ollama)")
        assertEquals("true", all.first { it.str("id") == "ollama::qwen4:8b" }.str("is_local"))

        val (_, samToken) = person("Sam", Role.member)
        grants("""{"grants":[{"role":"member","can_chat":true,"profile_id":"$safeProfile","models":[{"model_id":"ollama::qwen4:8b","daily_limit":1}]}]}""")
        val mine = get("/v1/agents/$agentId/models", samToken).json()["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("openrouter::nvidia/nemotron-3-ultra-550b-a55b:free", "ollama::qwen4:8b"), mine.map { it.str("id") })
        assertEquals("1" to "0", mine[1].str("daily_limit") to mine[1].str("used_today"))
        val session = post("/v1/sessions", """{"agent_id":"$agentId"}""", samToken).json().str("id")
        assertEquals(HttpStatusCode.Forbidden, post("/v1/sessions/$session/runs", """{"text":"hi","model_id":"openrouter::anthropic/claude-sonnet-5.5"}""", samToken).status)
        assertEquals(HttpStatusCode.Accepted, post("/v1/sessions/$session/runs", """{"text":"hi","model_id":"ollama::qwen4:8b"}""", samToken).status)
        runFinished(session)
        assertEquals("limit_reached", post("/v1/sessions/$session/runs", """{"text":"hi","model_id":"ollama::qwen4:8b"}""", samToken).json().str("code"))
        assertEquals(HttpStatusCode.Accepted, post("/v1/sessions/$session/runs", """{"text":"hi"}""", samToken).status, "the default model still works")
        runFinished(session)
    }

    // ── shared conversations ───────────────────────────────────────────────

    @Test fun `a shared conversation - live for participants, least capable profile, shared memory`() = app {
        val (sam, samToken) = person("Sam", Role.member)
        val (_, outsiderToken) = person("Alex", Role.member)
        grants("""{"grants":[{"user_id":"${sam.userId}","can_chat":true,"profile_id":"$safeProfile"}]}""")
        val session = post("/v1/sessions", """{"agent_id":"$agentId","title":"Trip"}""").json().str("id")
        val alex = hub.accounts.memberIds(owner.hubId).first { hub.accounts.user(it)!!.displayName == "Alex" }
        val refused = client.patch("/v1/sessions/$session") { bearerAuth(ownerToken); contentType(ContentType.Application.Json)
            setBody("""{"shared_with":{"user_ids":["$alex"]}}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status, "can't share with someone who can't use the agent")
        assertTrue("Alex" in refused.json().str("detail"))
        val shared = client.patch("/v1/sessions/$session") { bearerAuth(ownerToken); contentType(ContentType.Application.Json)
            setBody("""{"shared_with":{"user_ids":["${sam.userId}"]}}""") }.json()
        assertEquals(sam.userId, shared["shared_with"]!!.jsonObject["user_ids"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(1, get("/v1/sessions", samToken).json()["items"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.NotFound, get("/v1/sessions/$session", outsiderToken).status)

        assertEquals(HttpStatusCode.Accepted, post("/v1/sessions/$session/runs", """{"text":"where to?"}""", samToken).status)
        val body = hermes.runBodies.last()
        assertEquals("[Sam]: where to?", body.str("input"), "the speaker's name for the agent")
        assertEquals("switchboard:hub:${owner.hubId}", hermes.runHeaders.last()["session-key"], "the hub's shared memory, not Sam's")
        assertEquals("8643", hermes.runHeaders.last()["port"], "the owner's turn would be on full; shared uses the least capable")
        runFinished(session)
        assertEquals(HttpStatusCode.Forbidden, client.patch("/v1/sessions/$session") { bearerAuth(samToken); contentType(ContentType.Application.Json)
            setBody("""{"title":"Mine now"}""") }.status, "only the owner renames")
        assertEquals(HttpStatusCode.Forbidden, client.delete("/v1/sessions/$session") { bearerAuth(samToken) }.status)
        val msgs = get("/v1/sessions/$session/messages", samToken).json()["items"]!!.jsonArray
        assertEquals(sam.userId, msgs[0].jsonObject.str("author_user_id"))
    }

    // ── activity, devices ──────────────────────────────────────────────────

    @Test fun `activity shows who used what, never the content`() = app {
        val session = post("/v1/sessions", """{"agent_id":"$agentId"}""").json().str("id")
        post("/v1/sessions/$session/runs", """{"text":"my secret plans"}""")
        runFinished(session)
        val acts = get("/v1/activity").json()
        assertFalse("secret" in acts.toString(), "metadata only")
        val run = acts["items"]!!.jsonArray.map { it.jsonObject }.first { it.str("action") == "run_started" }
        assertEquals(owner.userId to agentId, run.str("user_id") to run.str("agent_id"))
        assertEquals(1, get("/v1/activity?agent_id=$agentId&limit=1").json()["items"]!!.jsonArray.size)
        val (_, memberToken) = person("Sam", Role.member)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/activity", memberToken).status)
    }

    @Test fun `devices - mine only, and removing one signs it out`() = app {
        val s = hub.accounts.signIn(owner, "Pixel 9", null, "android")
        val devices = get("/v1/devices", s.accessToken).json()["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Pixel 9" to "android", devices.single().str("name") to devices.single().str("platform"))
        val (_, other) = person("Sam", Role.member)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/devices/${devices.single().str("id")}") { bearerAuth(other) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/devices/${devices.single().str("id")}") { bearerAuth(ownerToken) }.status)
        assertEquals(HttpStatusCode.Unauthorized, get("/v1/me", s.accessToken).status)
    }

    // ── the permission matrix (spec: hub testing) ──────────────────────────

    @Test fun `permission matrix - every role and action`() = app {
        val people = mapOf(Role.owner to ownerToken) + listOf(Role.admin, Role.member, Role.restricted).associateWith { person(it.name, it).second }
        val expect = mapOf(
            // action                     owner  admin  member restricted (without grants)
            "list members" to listOf(200, 200, 403, 403),
            "create invite" to listOf(201, 201, 403, 403),
            "view activity" to listOf(200, 200, 403, 403),
            "set grants" to listOf(200, 403, 403, 403),
            "add agent" to listOf(400, 403, 403, 403),        // owner reaches validation (bad URL); others are refused
            "start conversation" to listOf(201, 201, 403, 403),
            "list models" to listOf(200, 200, 404, 404),
        )
        suspend fun act(action: String, token: String): Int = when (action) {
            "list members" -> get("/v1/members", token).status.value
            "create invite" -> post("/v1/invites", """{"role":"member"}""", token).status.value
            "view activity" -> get("/v1/activity", token).status.value
            "set grants" -> client.put("/v1/agents/$agentId/grants") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"grants":[]}""") }.status.value
            "add agent" -> post("/v1/agents", """{"type":"hermes","name":"x","base_url":"nope","profiles":[{"name":"a","api_key":"0123456789abcdef0"}]}""", token).status.value
            "start conversation" -> post("/v1/sessions", """{"agent_id":"$agentId"}""", token).status.value
            "list models" -> get("/v1/agents/$agentId/models", token).status.value
            else -> error(action)
        }
        val roles = listOf(Role.owner, Role.admin, Role.member, Role.restricted)
        val wrong = mutableListOf<String>()
        for ((action, codes) in expect) roles.forEachIndexed { i, role ->
            val got = act(action, people[role]!!)
            if (got != codes[i]) wrong += "$action as $role: expected ${codes[i]}, got $got"
        }
        assertEquals(emptyList(), wrong)
        // With a grant, members and restricted people can chat (restricted on the named profile only).
        grants("""{"grants":[{"role":"member","can_chat":true},{"role":"restricted","can_chat":true,"profile_id":"$safeProfile"}]}""")
        assertEquals(201, act("start conversation", people[Role.member]!!))
        assertEquals(201, act("start conversation", people[Role.restricted]!!))
        assertEquals(403, act("set grants", people[Role.member]!!))
    }

    // ── jobs and quick commands (M3) ───────────────────────────────────────

    @Test fun `jobs - Hermes's jobs in the hub's terms, changed only with job permission`() = app {
        val jobs = get("/v1/agents/$agentId/jobs").json()["items"]!!.jsonArray.map { it.jsonObject }
        val digest = jobs.first { it.str("id") == "j1" }
        assertEquals(listOf("Daily digest", "0 6 * * *", "active", "success"), listOf(digest.str("name"), digest.str("schedule"), digest.str("state"), digest.str("last_result")))
        assertEquals("2026-10-21T13:00:00Z", digest.str("next_run_at"), "offset times in UTC")
        assertEquals("2026-10-20T13:12:17.467350Z", digest.str("last_run_at"), "local times read at the job's offset")
        val backups = jobs.first { it.str("id") == "j2" }
        assertEquals(listOf("paused", "failure", "5", "every 360m"), listOf(backups.str("state"), backups.str("last_result"), backups.str("repeat"), backups.str("schedule")))

        val created = post("/v1/agents/$agentId/jobs", """{"name":"Weekly report","schedule":"0 9 * * 1","prompt":"Write the weekly report"}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertEquals("0 9 * * 1", created.json().str("schedule"))
        assertEquals("validation_failed", post("/v1/agents/$agentId/jobs", """{"name":"x","schedule":"nonsense","prompt":"y"}""").json().str("code"))
        assertEquals("paused", post("/v1/agents/$agentId/jobs/j1/pause", "{}").json().str("state"))
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/agents/$agentId/jobs/j3") { bearerAuth(ownerToken) }.status)
        assertTrue(db.query("SELECT action FROM activity") { it.getString(1) }.count { it == "job_changed" } >= 3)

        val (_, samToken) = person("Sam", Role.member)
        grants("""{"grants":[{"role":"member","can_chat":true,"profile_id":"$safeProfile"}]}""")
        assertEquals(HttpStatusCode.OK, get("/v1/agents/$agentId/jobs", samToken).status, "members with chat see jobs")
        assertEquals(HttpStatusCode.Forbidden, post("/v1/agents/$agentId/jobs/j1/resume", "{}", samToken).status)
        grants("""{"grants":[{"role":"member","can_chat":true,"can_manage_jobs":true,"profile_id":"$safeProfile"}]}""")
        assertEquals(HttpStatusCode.NotFound, post("/v1/agents/$agentId/jobs/nope/pause", "{}", samToken).status, "allowed now; that job doesn't exist")
        val (_, kidToken) = person("kid", Role.restricted)
        grants("""{"grants":[{"role":"restricted","can_chat":true,"can_manage_jobs":true,"profile_id":"$safeProfile"}]}""")
        assertEquals(HttpStatusCode.Forbidden, post("/v1/agents/$agentId/jobs/j1/pause", "{}", kidToken).status, "restricted never manage jobs")
    }

    @Test fun `quick commands - personal and for everyone`() = app {
        val (_, samToken) = person("Sam", Role.member)
        assertEquals(HttpStatusCode.Created, post("/v1/quick-commands", """{"label":"Status","prompt":"How are you doing?","scope":"everyone","agent_id":"$agentId"}""").status)
        assertEquals(HttpStatusCode.Forbidden, post("/v1/quick-commands", """{"label":"Mine","prompt":"x","scope":"everyone"}""", samToken).status)
        val mine = post("/v1/quick-commands", """{"label":"Weather","prompt":"Weather today?","scope":"personal"}""", samToken).json()
        assertEquals(listOf("Status", "Weather"), get("/v1/quick-commands", samToken).json()["items"]!!.jsonArray.map { it.jsonObject.str("label") })
        assertEquals(listOf("Status"), get("/v1/quick-commands").json()["items"]!!.jsonArray.map { it.jsonObject.str("label") }, "Sam's personal one is Sam's")
        val forEveryone = get("/v1/quick-commands", samToken).json()["items"]!!.jsonArray.first().jsonObject.str("id")
        assertEquals(HttpStatusCode.Forbidden, client.delete("/v1/quick-commands/$forEveryone") { bearerAuth(samToken) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/quick-commands/${mine.str("id")}") { bearerAuth(samToken) }.status)
    }
}
