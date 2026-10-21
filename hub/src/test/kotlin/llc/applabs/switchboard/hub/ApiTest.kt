package llc.applabs.switchboard.hub

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ApiTest {
    private val hermes = FakeHermes()
    private val scope = CoroutineScope(SupervisorJob())
    private val hub = Hub(HubConfig(hubName = "The Francos"), Db.inMemory(), SecretBox.random(),
        mapOf("hermes" to hermes.adapter), scope, healthIntervalMs = 60_000)

    private fun ownerToken(): String {
        val hh = hub.accounts.ensureHub("The Francos")
        return hub.accounts.issue(hub.accounts.owner(hh) ?: hub.accounts.createUser(hh, "Paul", Role.owner), "access", 900)
    }

    private fun tokenFor(role: Role): String {
        val hh = hub.accounts.ensureHub("The Francos")
        return hub.accounts.issue(hub.accounts.createUser(hh, role.name, role), "access", 900)
    }

    private fun addBody(key: String = FakeHermes.KEY) = """
        {"type":"hermes","name":"mini-ops","base_url":"http://mini-ops:8642/",
         "profiles":[{"name":"full","api_key":"$key","toolset_summary":"All tools"}]}""".trimIndent()

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { hubModule(hub) }
        block()
    }

    private suspend fun HttpResponse.json(): JsonObject = hubJson.parseToJsonElement(bodyAsText()).jsonObject

    @Test fun `hub info says whether the owner still has to pair`() = app {
        val before = client.get("/v1/hub").json()
        assertEquals("The Francos", before["name"]!!.jsonPrimitive.content)
        assertEquals("true", before["setup_required"]!!.jsonPrimitive.content)
        ownerToken()
        assertEquals("false", client.get("/v1/hub").json()["setup_required"]!!.jsonPrimitive.content)
    }

    @Test fun `no token gives a problem, not a page`() = app {
        val r = client.get("/v1/agents")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals("application/problem+json", r.headers["Content-Type"]!!.substringBefore(";"))
        assertEquals("unauthorized", r.json()["code"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth("made-up") }.status)
    }

    @Test fun `the owner adds an agent - the key is stored encrypted and never returned`() = app {
        val token = ownerToken()
        val r = client.post("/v1/agents") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(addBody()) }
        assertEquals(HttpStatusCode.Created, r.status)
        val body = r.bodyAsText()
        assertFalse(FakeHermes.KEY in body, "the key must never be returned")
        val agent = r.json()
        assertEquals("online", agent["status"]!!.jsonPrimitive.content)
        assertEquals("http://mini-ops:8642", agent["base_url"]!!.jsonPrimitive.content)
        assertEquals("mini-ops", agent["host"]!!.jsonPrimitive.content)
        assertEquals("true", agent["capabilities"]!!.jsonObject["approvals"]!!.jsonPrimitive.content)
        val profile = agent["profiles"]!!.jsonArray.single().jsonObject
        assertEquals("full", profile["key_last4"]!!.jsonPrimitive.content)
        val stored = hub.db.query("SELECT api_key_encrypted FROM agent_profile") { it.getString(1) }.single()
        assertTrue(stored.startsWith("v1:") && FakeHermes.KEY !in stored)
        assertEquals(FakeHermes.KEY, hub.secrets.open(stored))
        val list = client.get("/v1/agents") { bearerAuth(token) }.json()["items"]!!.jsonArray
        assertEquals(1, list.size)
        assertEquals(listOf("agent_added"), hub.db.query("SELECT action FROM activity") { it.getString(1) })
    }

    @Test fun `a failed connection test is a 422 with the reason`() = app {
        val token = ownerToken()
        val r = client.post("/v1/agents") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(addBody("a-wrong-key-0123456789")) }
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        val p = r.json()
        assertEquals("agent_key_rejected", p["code"]!!.jsonPrimitive.content)
        assertTrue("rejected the API key" in p["detail"]!!.jsonPrimitive.content)
        assertEquals("false", p["test"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        hermes.down = true
        val down = client.post("/v1/agents") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(addBody()) }.json()
        assertEquals("agent_unreachable", down["code"]!!.jsonPrimitive.content)
        assertEquals(0, hub.db.query("SELECT COUNT(*) FROM agent") { it.getInt(1) }.single())
    }

    @Test fun `testing without saving`() = app {
        val r = client.post("/v1/agents/test") { bearerAuth(ownerToken()); contentType(ContentType.Application.Json); setBody(addBody()) }
        assertEquals("true", r.json()["ok"]!!.jsonPrimitive.content)
        assertEquals(0, hub.db.query("SELECT COUNT(*) FROM agent") { it.getInt(1) }.single())
    }

    @Test fun `validation and roles`() = app {
        val owner = ownerToken()
        val short = client.post("/v1/agents") { bearerAuth(owner); contentType(ContentType.Application.Json)
            setBody(addBody("short")) }
        assertEquals("validation_failed", short.json()["code"]!!.jsonPrimitive.content)
        val other = client.post("/v1/agents") { bearerAuth(owner); contentType(ContentType.Application.Json)
            setBody(addBody().replace("\"hermes\"", "\"openclaw\"")) }
        assertEquals("not_supported", other.json()["code"]!!.jsonPrimitive.content)
        val admin = tokenFor(Role.admin)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/agents") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody(addBody()) }.status)
        client.post("/v1/agents") { bearerAuth(owner); contentType(ContentType.Application.Json); setBody(addBody()) }
        val seenByAdmin = client.get("/v1/agents") { bearerAuth(admin) }.json()["items"]!!.jsonArray.single().jsonObject
        assertFalse("base_url" in seenByAdmin.keys && seenByAdmin["base_url"]!!.toString() != "null", "only the owner sees the address")
        val member = tokenFor(Role.member)
        assertEquals(0, client.get("/v1/agents") { bearerAuth(member) }.json()["items"]!!.jsonArray.size)
        assertEquals("member", client.get("/v1/me") { bearerAuth(member) }.json()["role"]!!.jsonPrimitive.content)
    }

    @Test fun `rename, change the key, remove`() = app {
        val token = ownerToken()
        val id = client.post("/v1/agents") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(addBody()) }
            .json()["id"]!!.jsonPrimitive.content
        val renamed = client.patch("/v1/agents/$id") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"name":"mini-1"}""") }
        assertEquals("mini-1", renamed.json()["name"]!!.jsonPrimitive.content)
        val profileId = renamed.json()["profiles"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content
        hermes.key = "rotated-key-0123456789abcd"
        val rekeyed = client.patch("/v1/agents/$id") { bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"profiles":[{"id":"$profileId","name":"full","api_key":"rotated-key-0123456789abcd"}]}""") }
        assertEquals(HttpStatusCode.OK, rekeyed.status)
        assertEquals("abcd", rekeyed.json()["profiles"]!!.jsonArray.single().jsonObject["key_last4"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/agents/$id") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/agents/$id") { bearerAuth(token) }.status)
        // the owner's own actions (the health poller may also log the moment the old key stopped working)
        assertEquals(listOf("agent_added", "agent_changed", "agent_changed", "agent_removed"),
            hub.db.query("SELECT action FROM activity WHERE user_id IS NOT NULL ORDER BY at, rowid") { it.getString(1) })
    }

    @Test fun `logout revokes the token`() = app {
        val token = ownerToken()
        assertEquals(HttpStatusCode.NoContent, client.post("/v1/auth/logout") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(token) }.status)
    }
}
