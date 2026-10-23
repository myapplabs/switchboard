package llc.applabs.switchboard.hub

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deleting your own account from the app (both app stores require it). */
class AccountDeletionTest {
    private val hermes = FakeHermes()
    private val db = Db.inMemory()
    private val hub = Hub(HubConfig(hubName = "Home"), db, SecretBox.random(), mapOf("hermes" to hermes.adapter),
        CoroutineScope(SupervisorJob()), healthIntervalMs = 600_000)

    private fun app(block: suspend ApplicationTestBuilder.(owner: String, member: String, agentId: String) -> Unit) = testApplication {
        application { hubModule(hub) }
        val hh = hub.accounts.ensureHub("Home")
        val owner = hub.accounts.createUser(hh, "Owner", Role.owner)
        val member = hub.accounts.createUser(hh, "Sam", Role.member)
        val agentId = hub.agents.add(owner, AgentInput("hermes", "agent", "http://127.0.0.1:8642", listOf(ProfileInput("main", null, FakeHermes.KEY)))).id
        db.update("INSERT INTO agent_grant (agent_id, hub_id, subject_kind, subject, can_chat) VALUES (?, ?, 'user', ?, 1)", agentId, hh, member.userId)
        block(hub.accounts.signIn(owner, "Pixel", null, "android").accessToken, hub.accounts.signIn(member, "Phone", null, "android").accessToken, agentId)
    }

    private fun count(sql: String, vararg args: Any?) = db.query(sql, *args) { it.getInt(1) }.single()
    private suspend fun ApplicationTestBuilder.post(path: String, body: String, t: String) =
        client.post(path) { bearerAuth(t); contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun ApplicationTestBuilder.deleteMe(t: String, body: String = "{}") =
        client.delete("/v1/me") { bearerAuth(t); contentType(ContentType.Application.Json); setBody(body) }

    @Test fun `a member deletes their account - their data goes, the hub stays`() = app { owner, member, agentId ->
        val mine = post("/v1/sessions", """{"agent_id":"$agentId"}""", member)
        assertEquals(HttpStatusCode.Created, mine.status, mine.bodyAsText())
        val sessionId = hubJson.parseToJsonElement(mine.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val memberId = db.query("SELECT id FROM user WHERE display_name = 'Sam'") { it.getString(1) }.single()

        assertEquals(HttpStatusCode.NoContent, deleteMe(member).status)
        assertEquals(0, count("SELECT COUNT(*) FROM chat_session WHERE id = ?", sessionId), "their conversation")
        assertEquals(0, count("SELECT COUNT(*) FROM agent_grant WHERE subject = ?", memberId), "their access")
        assertEquals(0, count("SELECT COUNT(*) FROM device WHERE user_id = ? AND (revoked_at IS NULL OR name IS NOT NULL)", memberId), "their devices")
        assertEquals(Members.DELETED_NAME, db.query("SELECT display_name FROM user WHERE id = ?", memberId) { it.getString(1) }.single())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(member) }.status, "signed out")
        assertEquals(HttpStatusCode.OK, client.get("/v1/me") { bearerAuth(owner) }.status, "everyone else is fine")
        assertEquals(1, count("SELECT COUNT(*) FROM agent"), "the hub keeps its agents")
    }

    @Test fun `the owner deletes the whole hub, only after typing its name`() = app { owner, _, agentId ->
        post("/v1/sessions", """{"agent_id":"$agentId"}""", owner)
        val wrong = deleteMe(owner, """{"confirm":"Not it"}""")
        assertEquals(HttpStatusCode.BadRequest, wrong.status, wrong.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, deleteMe(owner).status, "no confirmation")
        assertEquals(1, count("SELECT COUNT(*) FROM agent"), "nothing deleted yet")

        assertEquals(HttpStatusCode.NoContent, deleteMe(owner, """{"confirm":" home "}""").status)
        for (table in listOf("user", "membership", "agent", "agent_profile", "chat_session", "device", "token", "credential", "activity"))
            assertEquals(0, count("SELECT COUNT(*) FROM $table"), table)
        assertTrue(!hub.accounts.hasOwner(), "waiting for a new owner")
        assertEquals(1, count("SELECT COUNT(*) FROM hub"), "the hub itself is ready to set up again")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(owner) }.status)
    }
}
