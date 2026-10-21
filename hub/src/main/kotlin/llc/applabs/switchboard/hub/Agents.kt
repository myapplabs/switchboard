package llc.applabs.switchboard.hub

import io.ktor.http.HttpStatusCode
import java.net.URI
import llc.applabs.switchboard.hub.adapters.AgentAdapter
import llc.applabs.switchboard.hub.adapters.ProfileTarget

/** A stored agent with its decrypted profile targets (never leaves the hub). */
data class AgentRecord(
    val id: String, val hubId: String, val type: String, val name: String, val baseUrl: String,
    val status: String, val activity: String?, val activeRuns: Int, val capabilities: Capabilities,
    val lastSeenAt: String?, val profiles: List<ProfileRecord>,
    /** Specialty (spec F33): what the agent is good at, set by the owner or an admin. */
    val about: String = "", val tags: List<String> = emptyList(), val costLevel: String? = null,
    /** The hub's coordinator (spec F36): plans cards into sub-cards for other agents. */
    val coordinator: Boolean = false,
)

data class ProfileRecord(
    val id: String, val name: String, val port: Int?, val apiKeySealed: String, val keyLast4: String, val toolsetSummary: String?,
)

/**
 * The agent registry (contract: /v1/agents). Owner-only changes; keys are validated against the
 * agent, encrypted with [SecretBox] and never returned. M0 visibility: the owner and admins see every
 * agent; per-role grants for members arrive in v1 (deny by default until then).
 */
class Agents(
    private val db: Db,
    private val secrets: SecretBox,
    private val accounts: Accounts,
    private val events: UserEvents,
    private val adapters: Map<String, AgentAdapter>,
) {
    /** Called when agents are added, changed or removed (the health poller watches the registry). */
    var onChanged: (String) -> Unit = {}
    /** Set by the hub (the permission layer needs the registry too). */
    lateinit var permissions: Permissions

    /** Agents [user] may see: all for owner and admins, granted ones for members (deny by default). */
    fun list(user: HubUser): List<AgentOut> =
        records(user.hubId).filter { visible(user, it) }.map { out(it, user) }

    fun get(user: HubUser, id: String): AgentOut {
        val r = record(id)?.takeIf { it.hubId == user.hubId && visible(user, it) } ?: throw HubException.notFound("agent")
        return out(r, user)
    }

    private fun visible(user: HubUser, r: AgentRecord) = user.isAdmin || permissions.access(user, r).canChat

    /** Who gets this agent's live events. */
    fun viewers(r: AgentRecord): List<String> = permissions.viewers(r)

    suspend fun test(user: HubUser, input: AgentInput): ConnectionTest {
        requireOwner(user)
        val adapter = validate(input)
        return adapter.test(input.profiles.map { ProfileTarget(it.name, profileUrl(input.baseUrl, it.port), it.apiKey) })
    }

    suspend fun add(user: HubUser, input: AgentInput): AgentOut {
        requireOwner(user)
        val adapter = validate(input)
        val test = adapter.test(input.profiles.map { ProfileTarget(it.name, profileUrl(input.baseUrl, it.port), it.apiKey) })
        if (!test.ok) throw failedTest(test)
        val id = newId("agt")
        val t = now()
        db.tx { c ->
            c.prepareStatement("""INSERT INTO agent (id, hub_id, type, name, base_url, status, capabilities_json, last_seen_at, created_at, updated_at)
                                  VALUES (?, ?, ?, ?, ?, 'online', ?, ?, ?, ?)""").use {
                Db.bind(it, arrayOf<Any?>(id, user.hubId, input.type, input.name.trim(), input.baseUrl.trimEnd('/'),
                    hubJson.encodeToString(Capabilities.serializer(), test.capabilities ?: Capabilities()), t, t, t)); it.executeUpdate()
            }
            input.profiles.forEachIndexed { i, p ->
                c.prepareStatement("""INSERT INTO agent_profile (id, hub_id, agent_id, name, port, api_key_encrypted, key_last4, toolset_summary, position)
                                      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""").use {
                    Db.bind(it, arrayOf<Any?>(newId("prf"), user.hubId, id, p.name.trim(), p.port, secrets.seal(p.apiKey),
                        p.apiKey.takeLast(4), p.toolsetSummary, i)); it.executeUpdate()
                }
            }
        }
        accounts.activity(user.hubId, user.userId, id, "agent_added", """{"type":"${input.type}"}""")
        record(id)?.let { events.publish(viewers(it), "agent.changed", mapOf("agent_id" to id)) }
        onChanged(id)
        return get(user, id)
    }

    suspend fun update(user: HubUser, id: String, patch: AgentPatch): AgentOut {
        val r = record(id)?.takeIf { it.hubId == user.hubId } ?: throw HubException.notFound("agent")
        patch.coordinator?.let { on ->
            requireOwner(user)
            setCoordinator(user, r, on)
            if (patch.name == null && patch.baseUrl == null && patch.profiles == null && patch.about == null && patch.tags == null && patch.costLevel == null) {
                agentsOf(user.hubId).forEach { events.publish(viewers(it), "agent.changed", mapOf("agent_id" to it.id)) }
                return get(user, id)
            }
        }
        val specialty = patch.about != null || patch.tags != null || patch.costLevel != null
        if (specialty) {
            if (!user.isAdmin) throw HubException.forbidden("Only the owner and admins describe agents.")
            setSpecialty(user, r, patch)
            if (patch.name == null && patch.baseUrl == null && patch.profiles == null) {
                events.publish(viewers(record(id)!!), "agent.changed", mapOf("agent_id" to id))
                return get(user, id)
            }
        }
        requireOwner(user)
        val adapter = adapters[r.type] ?: throw notSupported(r.type)
        val baseUrl = patch.baseUrl?.trim()?.trimEnd('/') ?: r.baseUrl
        patch.name?.let { if (it.isBlank() || it.length > 40) throw HubException.invalid("name: 1–40 characters") }
        if (patch.baseUrl != null) checkUrl(baseUrl)
        // The profiles after the patch: kept, changed, added; removed ones dropped.
        val byId = r.profiles.associateBy { it.id }
        val next = mutableListOf<Pair<ProfileRecord?, ProfilePatch?>>()
        r.profiles.forEach { p -> if (patch.profiles?.none { it.id == p.id } != false) next += p to null }
        patch.profiles?.forEach { pp ->
            val old = pp.id?.let { byId[it] ?: throw HubException.invalid("unknown profile ${pp.id}") }
            if (!pp.remove) {
                if (old == null && pp.apiKey == null) throw HubException.invalid("a new profile needs an api_key")
                pp.apiKey?.let { checkKey(it) }
                next += old to pp
            }
        }
        if (next.isEmpty()) throw HubException.invalid("an agent needs at least one profile")
        val targets = next.map { (old, pp) ->
            ProfileTarget(pp?.name ?: old!!.name, profileUrl(baseUrl, pp?.port ?: old?.port),
                pp?.apiKey ?: secrets.open(old!!.apiKeySealed))
        }
        val connectionChanged = baseUrl != r.baseUrl || patch.profiles != null
        val caps = if (connectionChanged) {
            val test = adapter.test(targets)
            if (!test.ok) throw failedTest(test)
            test.capabilities
        } else null
        val t = now()
        db.tx { c ->
            c.prepareStatement("UPDATE agent SET name = ?, base_url = ?, updated_at = ?${if (caps != null) ", capabilities_json = ?, status = 'online'" else ""} WHERE id = ?").use {
                val args = mutableListOf<Any?>(patch.name?.trim() ?: r.name, baseUrl, t)
                if (caps != null) args += hubJson.encodeToString(Capabilities.serializer(), caps)
                args += id
                Db.bind(it, args.toTypedArray()); it.executeUpdate()
            }
            if (patch.profiles != null) {
                c.prepareStatement("DELETE FROM agent_profile WHERE agent_id = ?").use { Db.bind(it, arrayOf<Any?>(id)); it.executeUpdate() }
                next.forEachIndexed { i, (old, pp) ->
                    val key = pp?.apiKey
                    c.prepareStatement("""INSERT INTO agent_profile (id, hub_id, agent_id, name, port, api_key_encrypted, key_last4, toolset_summary, position)
                                          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""").use {
                        Db.bind(it, arrayOf<Any?>(old?.id ?: newId("prf"), user.hubId, id, (pp?.name ?: old!!.name).trim(), pp?.port ?: old?.port,
                            key?.let(secrets::seal) ?: old!!.apiKeySealed, key?.takeLast(4) ?: old!!.keyLast4,
                            pp?.toolsetSummary ?: old?.toolsetSummary, i)); it.executeUpdate()
                    }
                }
            }
        }
        accounts.activity(user.hubId, user.userId, id, "agent_changed")
        record(id)?.let { events.publish(viewers(it), "agent.changed", mapOf("agent_id" to id)) }
        onChanged(id)
        return get(user, id)
    }

    /** One coordinator per hub: setting one clears the others. */
    private fun setCoordinator(user: HubUser, r: AgentRecord, on: Boolean) {
        db.tx { c ->
            fun exec(sql: String, vararg a: Any?) = c.prepareStatement(sql).use { Db.bind(it, a); it.executeUpdate() }
            if (on) exec("UPDATE agent_specialty SET coordinator = 0 WHERE hub_id = ?", r.hubId)
            exec("""INSERT INTO agent_specialty (agent_id, hub_id, coordinator, updated_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (agent_id) DO UPDATE SET coordinator = excluded.coordinator, updated_at = excluded.updated_at""", r.id, r.hubId, on, now())
        }
        accounts.activity(user.hubId, user.userId, r.id, "agent_described", """{"coordinator":$on}""")
    }

    private fun agentsOf(hubId: String) = records(hubId)

    fun coordinatorOf(hubId: String): AgentRecord? = records(hubId).firstOrNull { it.coordinator }

    private fun setSpecialty(user: HubUser, r: AgentRecord, patch: AgentPatch) {
        val about = (patch.about ?: r.about).trim()
        if (about.length > 300) throw HubException.invalid("about: at most 300 characters")
        val tags = (patch.tags ?: r.tags).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (tags.size > 8 || tags.any { !TAG.matches(it) }) throw HubException.invalid("tags: up to 8, each 1–20 of a–z, 0–9 and -")
        val cost = if (patch.costLevel != null) patch.costLevel.ifBlank { null } else r.costLevel
        if (cost != null && cost !in COST_LEVELS) throw HubException.invalid("cost_level: low, medium or high")
        db.update("""INSERT INTO agent_specialty (agent_id, hub_id, about, tags_json, cost_level, updated_at) VALUES (?, ?, ?, ?, ?, ?)
                     ON CONFLICT (agent_id) DO UPDATE SET about = excluded.about, tags_json = excluded.tags_json,
                     cost_level = excluded.cost_level, updated_at = excluded.updated_at""",
            r.id, r.hubId, about, kotlinx.serialization.json.JsonArray(tags.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString(), cost, now())
        accounts.activity(user.hubId, user.userId, r.id, "agent_described")
    }

    fun remove(user: HubUser, id: String) {
        requireOwner(user)
        val r = record(id)?.takeIf { it.hubId == user.hubId } ?: throw HubException.notFound("agent")
        val viewers = viewers(r)
        db.update("DELETE FROM agent WHERE id = ?", r.id)
        accounts.activity(user.hubId, user.userId, id, "agent_removed")
        events.publish(viewers, "agent.removed", mapOf("agent_id" to id))
        onChanged(id)
    }

    // ── registry access for the poller ───────────────────────────────────────

    fun record(id: String): AgentRecord? = rows("WHERE a.id = ?", id).firstOrNull()

    fun adapterFor(r: AgentRecord): AgentAdapter? = adapters[r.type]

    fun allIds(): List<String> = db.query("SELECT id FROM agent") { it.getString(1) }

    fun records(hubId: String): List<AgentRecord> = rows("WHERE a.hub_id = ? ORDER BY a.created_at", hubId)

    /** The first profile, decrypted — what health checks use. */
    fun primaryTarget(r: AgentRecord): ProfileTarget? = targetFor(r, null)

    /** The profile [profileId] (a tool level) — what a person's runs are routed to; the first profile when null. */
    fun targetFor(r: AgentRecord, profileId: String?): ProfileTarget? =
        (r.profiles.firstOrNull { it.id == profileId } ?: r.profiles.firstOrNull())?.let {
            ProfileTarget(it.name, profileUrl(r.baseUrl, it.port), secrets.open(it.apiKeySealed))
        }

    /** Store freshly discovered capabilities (e.g. after the agent was upgraded); true when they changed. */
    fun setCapabilities(id: String, caps: Capabilities): Boolean {
        val before = record(id) ?: return false
        if (before.capabilities == caps) return false
        db.update("UPDATE agent SET capabilities_json = ?, updated_at = ? WHERE id = ?", hubJson.encodeToString(Capabilities.serializer(), caps), now(), id)
        events.publish(viewers(before), "agent.changed", mapOf("agent_id" to id))
        return true
    }

    /** Store a health result; returns true when what the app shows changed. */
    fun setHealth(id: String, status: String, activity: String?, activeRuns: Int, seen: Boolean): Boolean {
        val before = record(id) ?: return false
        db.update("UPDATE agent SET status = ?, activity = ?, active_runs = ?${if (seen) ", last_seen_at = ?" else ""} WHERE id = ?",
            *(listOf<Any?>(status, activity, activeRuns) + (if (seen) listOf(now()) else emptyList()) + listOf(id)).toTypedArray())
        return before.status != status || before.activity != activity || before.activeRuns != activeRuns
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun rows(where: String, vararg args: Any?): List<AgentRecord> {
        val agents = db.query("SELECT a.*, s.about, s.tags_json, s.cost_level, s.coordinator FROM agent a LEFT JOIN agent_specialty s ON s.agent_id = a.id $where", *args) { rs ->
            AgentRecord(rs.getString("id"), rs.getString("hub_id"), rs.getString("type"), rs.getString("name"),
                rs.getString("base_url"), rs.getString("status"), rs.str("activity"), rs.getInt("active_runs"),
                runCatching { hubJson.decodeFromString(Capabilities.serializer(), rs.getString("capabilities_json")) }.getOrDefault(Capabilities()),
                rs.str("last_seen_at"), emptyList(), rs.str("about") ?: "",
                rs.str("tags_json")?.let { t -> runCatching { hubJson.decodeFromString<List<String>>(t) }.getOrNull() } ?: emptyList(), rs.str("cost_level"), rs.int("coordinator") == 1)
        }
        return agents.map { a ->
            a.copy(profiles = db.query("SELECT * FROM agent_profile WHERE agent_id = ? ORDER BY position", a.id) { rs ->
                ProfileRecord(rs.getString("id"), rs.getString("name"), rs.int("port"), rs.getString("api_key_encrypted"),
                    rs.getString("key_last4"), rs.str("toolset_summary"))
            })
        }
    }

    private fun out(r: AgentRecord, user: HubUser) = AgentOut(
        id = r.id, type = r.type, name = r.name, status = r.status, lastSeenAt = r.lastSeenAt, activity = r.activity,
        host = runCatching { URI(r.baseUrl).host }.getOrNull(), currentModel = null, activeRuns = r.activeRuns,
        jobCount = null, capabilities = r.capabilities,
        myPermissions = permissions.access(user, r).let { a ->
            AgentPermissions(chat = a.canChat, manageJobs = a.canManageJobs && r.capabilities.jobs, manageAgent = user.isOwner)
        },
        about = r.about, tags = r.tags, costLevel = r.costLevel, coordinator = r.coordinator,
        baseUrl = r.baseUrl.takeIf { user.isOwner },
        profiles = r.profiles.map { ProfileOut(it.id, it.name, it.port, keySet = true, keyLast4 = it.keyLast4, toolsetSummary = it.toolsetSummary) }
            .takeIf { user.isOwner },
    )

    private fun requireOwner(user: HubUser) {
        if (!user.isOwner) throw HubException.forbidden("Only the owner manages agents.")
    }

    private fun validate(input: AgentInput): AgentAdapter {
        val adapter = adapters[input.type] ?: throw notSupported(input.type)
        if (input.name.isBlank() || input.name.length > 40) throw HubException.invalid("name: 1–40 characters")
        checkUrl(input.baseUrl)
        if (input.profiles.isEmpty()) throw HubException.invalid("an agent needs at least one profile")
        if (input.profiles.map { it.name.trim() }.toSet().size != input.profiles.size) throw HubException.invalid("profile names must differ")
        input.profiles.forEach { checkKey(it.apiKey); it.port?.let { p -> if (p !in 1..65535) throw HubException.invalid("port out of range") } }
        return adapter
    }

    private fun checkUrl(url: String) {
        val u = runCatching { URI(url.trim()) }.getOrNull()
        if (u == null || u.scheme !in setOf("http", "https") || u.host.isNullOrBlank())
            throw HubException.invalid("base_url must be an http(s) address, e.g. http://mini-1:8642")
    }

    private fun checkKey(key: String) {
        if (key.length < 16) throw HubException.invalid("api_key must be at least 16 characters (Hermes rejects shorter keys)")
    }

    private fun notSupported(type: String) = HubException(HttpStatusCode.UnprocessableEntity, "not_supported",
        "Agent type not supported yet", "This hub supports: ${adapters.keys.sorted().joinToString()}. ($type arrives in a later release.)")

    private fun failedTest(test: ConnectionTest): HubException {
        val first = test.checks.firstOrNull { !it.ok }
        val code = when (first?.name) {
            "reachable" -> "agent_unreachable"
            "auth" -> "agent_key_rejected"
            else -> "not_supported"
        }
        return HubException(HttpStatusCode.UnprocessableEntity, code, "The agent connection test failed",
            first?.detail ?: "The agent could not be verified.", test)
    }

    companion object {
        /** The profile's address: [baseUrl] with [port] instead of its own port, when given. */
        private val TAG = Regex("[a-z0-9-]{1,20}")
        val COST_LEVELS = setOf("low", "medium", "high")

        fun profileUrl(baseUrl: String, port: Int?): String {
            val u = URI(baseUrl.trim().trimEnd('/'))
            return if (port == null) u.toString() else URI(u.scheme, u.userInfo, u.host, port, u.path, null, null).toString().trimEnd('/')
        }
    }
}
