package llc.applabs.switchboard.hub

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class PairingTest {
    private val androidApp = "android:apk-key-hash:x1Yw0Tb8sYl6m0Hk2Q4xqJ3PZ0yV3tE6nR8aWcF2bLk"
    private val config = HubConfig(hubName = "Main Street Bakery", publicUrl = "https://mini-1.tail1234.ts.net",
        origins = listOf(androidApp))
    private val hub = Hub(config, Db.inMemory(), SecretBox.random(), mapOf("hermes" to FakeHermes().adapter),
        CoroutineScope(SupervisorJob()), healthIntervalMs = 60_000)
    /** The Android app: passkeys for the shared domain, origin = the app's signing key. */
    private val phone = SoftAuthenticator(SWITCHBOARD_RP_ID, androidApp)
    private fun anotherPhone() = SoftAuthenticator(SWITCHBOARD_RP_ID, androidApp)
    private fun code() = hub.pairing.newCode(hub.accounts.ensureHub("Main Street Bakery"))

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { hubModule(hub) }
        block()
    }

    private suspend fun HttpResponse.json(): JsonObject = hubJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.post(path: String, body: String) =
        client.post(path) { contentType(ContentType.Application.Json); setBody(body) }

    /** The app's pairing: options → the phone creates a passkey → verify. */
    private suspend fun ApplicationTestBuilder.pair(code: String, name: String = "Paul", authenticator: SoftAuthenticator = phone): HttpResponse {
        val ceremony = post("/v1/auth/passkeys/register/options",
            """{"setup_code":"$code","display_name":"$name","device_name":"Pixel 9"}""").json()
        val credential = authenticator.create(ceremony["options"]!!.jsonObject)
        return post("/v1/auth/passkeys/register/verify", buildJsonObject {
            put("ceremony_id", ceremony.str("ceremony_id")); put("credential", credential)
        }.toString())
    }

    private suspend fun ApplicationTestBuilder.signIn(authenticator: SoftAuthenticator = phone): HttpResponse {
        val ceremony = post("/v1/auth/passkeys/login/options", "{}").json()
        return post("/v1/auth/passkeys/login/verify", buildJsonObject {
            put("ceremony_id", ceremony.str("ceremony_id")); put("credential", authenticator.get(ceremony["options"]!!.jsonObject))
        }.toString())
    }

    @Test fun `the owner pairs with the setup code and a passkey`() = app {
        val code = code()
        assertTrue(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}").matches(code))
        val options = post("/v1/auth/passkeys/register/options", """{"setup_code":"${code.lowercase()}","display_name":"Paul"}""").json()["options"]!!.jsonObject
        assertEquals("switchboard.applabs.llc", options["rp"]!!.jsonObject.str("id"))
        assertEquals("required", options["authenticatorSelection"]!!.jsonObject.str("residentKey"))
        assertEquals("required", options["authenticatorSelection"]!!.jsonObject.str("userVerification"))

        val r = pair(code)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val session = r.json()
        assertEquals("owner", session["me"]!!.jsonObject.str("role"))
        assertEquals("Paul", session["me"]!!.jsonObject.str("display_name"))
        assertEquals("900", session.str("expires_in"))
        assertEquals("false", client.get("/v1/hub").json().str("setup_required"))
        val me = client.get("/v1/me") { bearerAuth(session.str("access_token")) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(1, hub.db.query("SELECT COUNT(*) FROM credential") { it.getInt(1) }.single())
    }

    @Test fun `a setup code works once`() = app {
        val code = code()
        assertEquals(HttpStatusCode.OK, pair(code).status)
        val again = post("/v1/auth/passkeys/register/options", """{"setup_code":"$code","display_name":"Mallory"}""")
        assertEquals(HttpStatusCode.BadRequest, again.status)
        assertEquals("setup_code_invalid", again.json().str("code"))
    }

    @Test fun `wrong codes are refused and ten of them burn the real one`() = app {
        val code = code()
        val wrong = post("/v1/auth/passkeys/register/options", """{"setup_code":"AAAA-AAAA","display_name":"x"}""")
        assertEquals("setup_code_invalid", wrong.json().str("code"))
        repeat(Pairing.MAX_ATTEMPTS - 1) { post("/v1/auth/passkeys/register/options", """{"setup_code":"BBBB-BBBB","display_name":"x"}""") }
        assertEquals("setup_code_invalid", post("/v1/auth/passkeys/register/options",
            """{"setup_code":"$code","display_name":"Paul"}""").json().str("code"))
    }

    @Test fun `a later code adds a device for the owner instead of a second owner`() = app {
        pair(code(), "Paul")
        val newPhone = SoftAuthenticator(SWITCHBOARD_RP_ID, "https://switchboard.applabs.llc")   // an iPhone
        val r = pair(code(), "Someone else", newPhone)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("Paul", r.json()["me"]!!.jsonObject.str("display_name"))
        assertEquals(1, hub.db.query("SELECT COUNT(*) FROM user") { it.getInt(1) }.single())
        assertEquals(2, hub.db.query("SELECT COUNT(*) FROM credential") { it.getInt(1) }.single())
        assertEquals(HttpStatusCode.OK, signIn(newPhone).status)
        assertEquals(HttpStatusCode.OK, signIn(phone).status)
    }

    @Test fun `sign in with the passkey, refresh, sign out`() = app {
        pair(code())
        val session = signIn().json()
        assertEquals("owner", session["me"]!!.jsonObject.str("role"))
        val tokens = post("/v1/auth/refresh", """{"refresh_token":"${session.str("refresh_token")}"}""").json()
        assertNotEquals(session.str("access_token"), tokens.str("access_token"))
        assertEquals(HttpStatusCode.OK, client.get("/v1/me") { bearerAuth(tokens.str("access_token")) }.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/v1/auth/logout") { bearerAuth(tokens.str("access_token")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(tokens.str("access_token")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, post("/v1/auth/refresh", """{"refresh_token":"${tokens.str("refresh_token")}"}""").status)
    }

    @Test fun `a refresh token used twice signs the device out`() = app {
        val session = pair(code()).json()
        val first = post("/v1/auth/refresh", """{"refresh_token":"${session.str("refresh_token")}"}""").json()
        val replay = post("/v1/auth/refresh", """{"refresh_token":"${session.str("refresh_token")}"}""")
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(first.str("access_token")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, post("/v1/auth/refresh", """{"refresh_token":"${first.str("refresh_token")}"}""").status)
        assertTrue("device_revoked" in hub.db.query("SELECT action FROM activity") { it.getString(1) })
    }

    @Test fun `passkeys for another domain are refused`() = app {
        val ceremony = post("/v1/auth/passkeys/register/options", """{"setup_code":"${code()}","display_name":"Paul"}""").json()
        val wrongDomain = SoftAuthenticator("mini-1.tail1234.ts.net", androidApp).create(ceremony["options"]!!.jsonObject)
        val r = post("/v1/auth/passkeys/register/verify", buildJsonObject {
            put("ceremony_id", ceremony.str("ceremony_id")); put("credential", wrongDomain)
        }.toString())
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test fun `passkeys from elsewhere are refused`() = app {
        // registered for another site
        val code = code()
        val ceremony = post("/v1/auth/passkeys/register/options", """{"setup_code":"$code","display_name":"Paul"}""").json()
        val phishing = phone.create(ceremony["options"]!!.jsonObject, originOverride = "https://evil.test")
        val r = post("/v1/auth/passkeys/register/verify", buildJsonObject {
            put("ceremony_id", ceremony.str("ceremony_id")); put("credential", phishing)
        }.toString())
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals(0, hub.db.query("SELECT COUNT(*) FROM user") { it.getInt(1) }.single())
        // a passkey this hub never registered
        pair(code())
        assertEquals(HttpStatusCode.Unauthorized, signIn(anotherPhone().also { other ->
            other.create(buildJsonObject {
                put("challenge", SoftAuthenticator.b64(ByteArray(32)))
                put("user", buildJsonObject { put("id", SoftAuthenticator.b64(ByteArray(32))) })
            })
        }).status)
        // a ceremony is single use
        val login = post("/v1/auth/passkeys/login/options", "{}").json()
        val cred = phone.get(login["options"]!!.jsonObject)
        val body = buildJsonObject { put("ceremony_id", login.str("ceremony_id")); put("credential", cred) }.toString()
        assertEquals(HttpStatusCode.OK, post("/v1/auth/passkeys/login/verify", body).status)
        assertEquals(HttpStatusCode.BadRequest, post("/v1/auth/passkeys/login/verify", body).status)
    }

    @Test fun `an unknown invite is refused and exactly one code is required`() = app {
        assertEquals("not_found", post("/v1/auth/passkeys/register/options", """{"invite_code":"x","display_name":"Kid"}""").json().str("code"))
        assertEquals("validation_failed", post("/v1/auth/passkeys/register/options", """{"display_name":"Kid"}""").json().str("code"))
    }

    @Test fun `the pairing QR code is a link the phone's camera opens in the app, with the hub and code after the hash`() {
        val url = Pairing.pairingUrl(config, "ABCD-EFGH")
        assertEquals("https://switchboard.applabs.llc/pair#hub=https%3A%2F%2Fmini-1.tail1234.ts.net&code=ABCD-EFGH", url)
        val qr = Pairing.terminalQr(url)
        assertTrue(qr.lines().size > 10 && qr.contains('█'))
    }

    @Test fun `the installer adds an agent before anyone has paired, tested and with its key encrypted`() {
        val db = Db.inMemory()
        val secrets = SecretBox.random()
        val accounts = Accounts(db)
        val agents = Agents(db, secrets, accounts, UserEvents(), mapOf("hermes" to FakeHermes().adapter))
        val hubId = accounts.ensureHub("Main Street Bakery")
        assertTrue(!accounts.hasOwner())
        val id = kotlinx.coroutines.runBlocking { agents.addForHub(hubId, AgentInput("hermes", "office", "http://127.0.0.1:8642", listOf(ProfileInput("main", null, FakeHermes.KEY)))) }
        val r = agents.record(id)!!
        assertEquals("office" to "online", r.name to r.status)
        assertEquals(FakeHermes.KEY, secrets.open(r.profiles.single().apiKeySealed))
        assertTrue(db.query("SELECT api_key_encrypted FROM agent_profile") { it.getString(1) }.none { FakeHermes.KEY in it }, "stored encrypted")
        val wrong = runCatching { kotlinx.coroutines.runBlocking { agents.addForHub(hubId, AgentInput("hermes", "bad", "http://127.0.0.1:8642", listOf(ProfileInput("main", null, "wrong-key-0123456789")))) } }
        assertTrue(wrong.exceptionOrNull() is HubException, "a key the agent rejects isn't saved")
    }

    @Test fun `a fresh hub works with the official app and its push relay out of the box`() {
        val fresh = HubConfig()
        assertTrue(fresh.effectiveOrigins.any { it.startsWith("android:apk-key-hash:") }, "the official app's passkeys are accepted without config")
        assertEquals(OFFICIAL_PUSH_RELAY, fresh.pushRelayUrl)
    }
}
