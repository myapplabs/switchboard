package llc.applabs.switchboard.hub

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Files and photos sent with a message: stored on the hub, photos inline for the agent, links for the rest. */
class AttachmentsTest {
    private val hermes = FakeHermes()
    private val dir = Files.createTempDirectory("files-test")
    private val hub = Hub(HubConfig(), Db.inMemory(), SecretBox.random(), mapOf("hermes" to hermes.adapter),
        CoroutineScope(SupervisorJob()), healthIntervalMs = 600_000, filesDir = dir)
    private lateinit var token: String
    private lateinit var other: String
    private lateinit var agentId: String

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { hubModule(hub) }
        val hh = hub.accounts.ensureHub("Home")
        val owner = hub.accounts.createUser(hh, "Owner", Role.owner)
        token = hub.accounts.signIn(owner, "Pixel", null, "android").accessToken
        other = hub.accounts.signIn(hub.accounts.createUser(hh, "Sam", Role.admin), "Phone", null, "android").accessToken
        agentId = hub.agents.add(owner, AgentInput("hermes", "agent", "http://127.0.0.1:8642", listOf(ProfileInput("main", null, FakeHermes.KEY)))).id
        block()
    }

    private suspend fun HttpResponse.json(): JsonObject = hubJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private suspend fun ApplicationTestBuilder.post(path: String, body: String, t: String = token) =
        client.post(path) { bearerAuth(t); contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun ApplicationTestBuilder.upload(session: String, name: String, type: String, bytes: ByteArray, t: String = token) =
        client.post("/v1/sessions/$session/attachments") {
            bearerAuth(t); header("X-Filename", java.net.URLEncoder.encode(name, Charsets.UTF_8)); contentType(ContentType.parse(type)); setBody(bytes)
        }

    private val photo = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)   // a JPEG's first bytes are enough
    private val pdf = "%PDF-1.4 notes".toByteArray()

    @Test fun `a photo and a file go with a message - the photo inline, both as links`() = app {
        val session = post("/v1/sessions", """{"agent_id":"$agentId"}""").json().str("id")
        val p = upload(session, "beach.jpg", "image/jpeg", photo)
        assertEquals(HttpStatusCode.Created, p.status, p.bodyAsText())
        val f = upload(session, "../../etc/notes.pdf", "application/pdf", pdf).json()
        assertEquals("notes.pdf", f.str("name"), "no folders in names")
        val r = post("/v1/sessions/$session/runs", """{"text":"What's in these?","attachments":["${p.json().str("id")}","${f.str("id")}"]}""")
        assertEquals(HttpStatusCode.Accepted, r.status, r.bodyAsText())

        withTimeout(5_000) { while (hermes.runBodies.isEmpty()) delay(20) }
        val content = hermes.runBodies.last()["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("image_url", content[1].str("type"))
        assertTrue(content[1]["image_url"]!!.jsonObject.str("url").startsWith("data:image/jpeg;base64,"))
        val text = content[0].str("text")
        assertTrue(text.startsWith("What's in these?") && "beach.jpg (image/jpeg, 7 bytes), shown to you above" in text, text)
        val link = Regex("""http://localhost/files/(att_\w+)/([\w-]+)/notes\.pdf""").find(text)
        assertTrue(link != null, text)

        // The agent downloads with its link only; a wrong token gets nothing.
        val got = client.get(link.value.removePrefix("http://localhost"))
        assertEquals(HttpStatusCode.OK, got.status)
        assertContentEquals(pdf, got.readRawBytes())
        assertEquals("nosniff", got.headers["X-Content-Type-Options"])
        assertEquals(HttpStatusCode.NotFound, client.get("/files/${link.groupValues[1]}/wrong-token-0000000000/notes.pdf").status)

        // The app sees them on the message, and can download them; someone without the conversation can't.
        val msgs = client.get("/v1/sessions/$session/messages") { bearerAuth(token) }.json()["items"]!!.jsonArray.map { it.jsonObject }
        val files = msgs.first { it.str("role") == "user" }["attachments"]!!.jsonArray.map { it.jsonObject.str("name") }
        assertEquals(listOf("beach.jpg", "notes.pdf"), files)
        assertContentEquals(photo, client.get("/v1/attachments/${p.json().str("id")}") { bearerAuth(token) }.readRawBytes())
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/attachments/${f.str("id")}") { bearerAuth(other) }.status)
    }

    @Test fun `files are checked, and go with their conversation`() = app {
        val session = post("/v1/sessions", """{"agent_id":"$agentId"}""").json().str("id")
        val elsewhere = post("/v1/sessions", """{"agent_id":"$agentId"}""").json().str("id")
        val a = upload(session, "a.txt", "text/plain", "hello".toByteArray()).json().str("id")
        assertEquals("validation_failed", post("/v1/sessions/$elsewhere/runs", """{"text":"x","attachments":["$a"]}""").json().str("code"),
            "only in its own conversation")
        assertEquals("validation_failed", post("/v1/sessions/$session/runs", """{"text":""}""").json().str("code"), "nothing to send")
        assertEquals(HttpStatusCode.BadRequest, upload(session, "empty.txt", "text/plain", ByteArray(0)).status)

        assertEquals(HttpStatusCode.Accepted, post("/v1/sessions/$session/runs", """{"attachments":["$a"]}""").status, "a file alone is a message")
        assertTrue(Files.exists(dir.resolve(a)))
        withTimeout(5_000) { while (hub.conversations.activeRunId(session) != null) delay(20) }
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/sessions/$session") { bearerAuth(token) }.status)
        assertTrue(!Files.exists(dir.resolve(a)), "deleted with the conversation")
    }

    @Test fun `names and types are cleaned`() {
        assertEquals("report.pdf", Attachments.cleanName("C:\\\\Users\\\\me\\\\report.pdf"))
        assertEquals("file", Attachments.cleanName("../.."))
        assertEquals("a b.txt", Attachments.cleanName("a%20b.txt"))
        assertEquals("application/octet-stream", Attachments.cleanType("text/html\r\nX-Evil: 1"))
        assertEquals("image/png", Attachments.cleanType("image/PNG; charset=x"))
    }
}
