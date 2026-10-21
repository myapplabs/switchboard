package llc.applabs.switchboard.data

import kotlinx.serialization.serializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import llc.applabs.switchboard.domain.HubException
import llc.applabs.switchboard.domain.SecureStore

/** The hub address and this device's tokens, kept in the platform's secure storage. */
class TokenStore(private val store: SecureStore) {
    var hubUrl: String?
        get() = store.get(HUB_URL)
        set(v) = if (v == null) store.remove(HUB_URL) else store.set(HUB_URL, v)
    var accessToken: String?
        get() = store.get(ACCESS)
        set(v) = if (v == null) store.remove(ACCESS) else store.set(ACCESS, v)
    var refreshToken: String?
        get() = store.get(REFRESH)
        set(v) = if (v == null) store.remove(REFRESH) else store.set(REFRESH, v)
    /** The last `/me`, so the app opens while the hub is unreachable. */
    var cachedMe: String?
        get() = store.get(ME)
        set(v) = if (v == null) store.remove(ME) else store.set(ME, v)

    fun clearSession() { accessToken = null; refreshToken = null; cachedMe = null }

    private companion object {
        const val HUB_URL = "hub_url"; const val ACCESS = "access_token"; const val REFRESH = "refresh_token"; const val ME = "me"
    }
}

/** One Server-Sent Event. */
data class SseEvent(val id: String?, val event: String?, val data: String)

@Serializable
private data class TokensDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

/**
 * The hub's HTTP API (contract `/v1`). Every call carries the access token; a 401 triggers one
 * refresh (refresh tokens rotate; a single refresh at a time) and a retry. Errors arrive as
 * [HubException] with the contract's code; a hub that can't be reached is [HubException.UNREACHABLE].
 */
class HubClient(engine: HttpClientEngine?, val tokens: TokenStore) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val http = (if (engine != null) HttpClient(engine) { configure() } else HttpClient { configure() })
    private val refreshLock = Mutex()
    /** Called when the hub no longer accepts this device (refresh failed): the app signs out. */
    var onSignedOut: () -> Unit = {}

    private fun io.ktor.client.HttpClientConfig<*>.configure() {
        expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 8_000; requestTimeoutMillis = 30_000 }
    }

    fun base(hubUrl: String) = normalizeHubUrl(hubUrl) + "/v1"

    /** A JSON call; [T] decoded from the body (Unit for none). */
    suspend inline fun <reified T> call(method: HttpMethod, path: String, body: Any? = null, auth: Boolean = true, hubUrl: String? = null): T {
        val text = send(method, path, body?.let { encode(it) }, auth, hubUrl)
        @Suppress("UNCHECKED_CAST")
        return if (T::class == Unit::class) Unit as T else json.decodeFromString(text)
    }

    /**
     * Body encoding: already-encoded JSON strings and JSON elements as they are, everything else by the
     * serializer of its runtime class ([call] takes `Any?`, so the static type would only say `Any`).
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun encode(body: Any): String = when (body) {
        is String -> body
        is kotlinx.serialization.json.JsonElement -> body.toString()
        else -> {
            @Suppress("UNCHECKED_CAST")
            val serializer = json.serializersModule.serializer(body::class, emptyList(), false) as kotlinx.serialization.KSerializer<Any>
            json.encodeToString(serializer, body)
        }
    }

    suspend fun send(method: HttpMethod, path: String, body: String?, auth: Boolean, hubUrl: String? = null): String {
        val url = (hubUrl?.let(::base) ?: tokens.hubUrl?.let(::base) ?: throw HubException("not_signed_in", 0, "Not connected", "Connect to a hub first.")) + path
        val sent = if (auth) tokens.accessToken else null
        var response = exchange(url, method, body, sent)
        if (auth && response.status.value == 401 && refresh(sentWith = sent?.let { "Bearer $it" })) {
            response = exchange(url, method, body, tokens.accessToken)
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) throw problem(response.status.value, text)
        return text
    }

    private suspend fun exchange(url: String, method: HttpMethod, body: String?, token: String?): HttpResponse = try {
        http.request(url) {
            this.method = method
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw unreachable(url)
    }

    /** Rotate tokens once; false when this device must sign in again. */
    private suspend fun refresh(sentWith: String?): Boolean = refreshLock.withLock {
        val current = tokens.accessToken
        if (current != null && sentWith != "Bearer $current") return@withLock true   // another call already refreshed
        val refreshToken = tokens.refreshToken ?: run { signedOut(); return@withLock false }
        val url = base(tokens.hubUrl ?: return@withLock false) + "/auth/refresh"
        val r = exchange(url, HttpMethod.Post, """{"refresh_token":"$refreshToken"}""", null)
        if (r.status.value !in 200..299) { signedOut(); return@withLock false }
        val t = json.decodeFromString(TokensDto.serializer(), r.bodyAsText())
        tokens.accessToken = t.accessToken
        tokens.refreshToken = t.refreshToken
        true
    }

    /** A plain JSON POST to another service (the push relay); no hub token. */
    suspend fun sendTo(url: String, body: String): String {
        val r = exchange(url, HttpMethod.Post, body, null)
        val text = r.bodyAsText()
        if (r.status.value !in 200..299) throw problem(r.status.value, text)
        return text
    }

    private fun signedOut() { tokens.clearSession(); onSignedOut() }

    /**
     * An SSE stream ([path] under `/v1`), resumable with [lastEventId]. Ends when the hub closes it.
     * A 401 refreshes the token and opens it again.
     */
    fun sse(path: String, lastEventId: String?): Flow<SseEvent> = flow {
        val url = base(tokens.hubUrl ?: throw HubException("not_signed_in", 0, "Not connected", null)) + path
        var attempt = 0
        while (true) {
            val token = tokens.accessToken
            var unauthorized = false
            try {
                http.prepareRequest(url) {
                    method = HttpMethod.Get
                    sseHeaders(token, lastEventId)
                    timeout { requestTimeoutMillis = Long.MAX_VALUE; socketTimeoutMillis = 60_000 }
                }.execute { r ->
                    if (r.status.value == 401) { unauthorized = true; return@execute }
                    if (r.status.value !in 200..299) throw problem(r.status.value, r.bodyAsText())
                    emit(SseEvent(null, OPENED, ""))   // connected (before any event arrives)
                    val ch = r.bodyAsChannel()
                    var id: String? = null; var event: String? = null; val data = StringBuilder()
                    while (true) {
                        val line = ch.readUTF8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                if (data.isNotEmpty()) emit(SseEvent(id, event, data.toString()))
                                id = null; event = null; data.clear()
                            }
                            line.startsWith(":") -> Unit   // comment / ping
                            line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.removePrefix("data:").trimStart()) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HubException) {
                throw e
            } catch (e: Exception) {
                throw unreachable(url)
            }
            if (unauthorized && attempt++ == 0 && refresh(sentWith = token?.let { "Bearer $it" })) continue
            if (unauthorized) throw HubException("unauthorized", 401, "Signed out", "Sign in again.")
            return@flow
        }
    }

    private fun HttpRequestBuilder.sseHeaders(token: String?, lastEventId: String?) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        header(HttpHeaders.Accept, "text/event-stream")
        lastEventId?.let { header("Last-Event-ID", it) }
    }

    fun problem(status: Int, text: String): HubException {
        val p = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        fun s(k: String) = p?.get(k)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        return HubException(s("code") ?: "hub_error", status, s("title") ?: "Hub error (HTTP $status)", s("detail"),
            p?.get("retry_after_s")?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() })
    }

    private fun unreachable(url: String) = HubException(HubException.UNREACHABLE, 0, "Hub unreachable",
        "Can't reach the hub at ${url.substringBefore("/v1")}. Check that you're on the home network or Tailscale, and that the hub is running.")

    companion object {
        /** Sent once when a stream opens; carries no data. */
        const val OPENED = "__opened"

        /** `192.168.1.20:8780` → `http://192.168.1.20:8780`; `mini.tailnet.ts.net` → `https://…`; no trailing `/` or `/v1`. */
        fun normalizeHubUrl(input: String): String {
            var u = input.trim().removeSuffix("/").removeSuffix("/v1").removeSuffix("/")
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                val host = u.substringBefore('/').substringBefore(':')
                val ip = host.all { it.isDigit() || it == '.' } || host == "localhost"
                u = (if (ip || u.contains(':')) "http://" else "https://") + u
            }
            return u
        }
    }
}
