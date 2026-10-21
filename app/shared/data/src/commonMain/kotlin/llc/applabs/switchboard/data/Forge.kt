package llc.applabs.switchboard.data

import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import llc.applabs.switchboard.domain.ForgeRepository
import llc.applabs.switchboard.domain.model.ForgeBoard
import llc.applabs.switchboard.domain.model.ForgeBridge
import llc.applabs.switchboard.domain.model.ForgeBridgeCreated
import llc.applabs.switchboard.domain.model.ForgeRun
import llc.applabs.switchboard.domain.model.ForgeStart
import llc.applabs.switchboard.domain.model.Items

class ForgeRepositoryImpl(private val client: HubClient) : ForgeRepository {
    private val _board = MutableStateFlow(ForgeBoard(connected = false))
    override val board: StateFlow<ForgeBoard> = _board.asStateFlow()

    override suspend fun refresh(fresh: Boolean) { _board.value = client.call(HttpMethod.Get, "/forge/cards" + if (fresh) "?fresh=true" else "") }
    override suspend fun bridges(): List<ForgeBridge> = client.call<Items<ForgeBridge>>(HttpMethod.Get, "/forge/bridges").items
    override suspend fun connect(name: String): ForgeBridgeCreated = client.call(HttpMethod.Post, "/forge/bridges", buildJsonObject { put("name", name) })
    override suspend fun revoke(id: String) = client.call<Unit>(HttpMethod.Delete, "/forge/bridges/$id")
    override suspend fun start(project: String, ticket: String, confirmed: Boolean): ForgeStart =
        client.call<ForgeStart>(HttpMethod.Post, "/forge/tickets/start", buildJsonObject {
            put("project", project); put("ticket", ticket); put("confirmed", confirmed)
        }).also { if (confirmed) runCatching { refresh(fresh = true) } }
    override suspend fun run(runId: String): ForgeRun = client.call(HttpMethod.Get, "/forge/runs/$runId")
    override suspend fun decide(runId: String, decision: String, note: String?) {
        client.call<kotlinx.serialization.json.JsonObject>(HttpMethod.Post, "/forge/runs/$runId/decide", buildJsonObject {
            put("decision", decision); note?.let { put("note", it) }
        })
        runCatching { refresh(fresh = true) }
    }
}
