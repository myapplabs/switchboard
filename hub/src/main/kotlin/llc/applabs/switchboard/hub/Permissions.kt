package llc.applabs.switchboard.hub

import io.ktor.http.HttpStatusCode
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelGrant(@SerialName("model_id") val modelId: String, @SerialName("daily_limit") val dailyLimit: Int? = null)

/** One grant (contract AgentGrants.grants[]): for a role, or for one person (`user_id`, wins over the role). */
@Serializable
data class Grant(
    val role: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("can_chat") val canChat: Boolean,
    @SerialName("can_manage_jobs") val canManageJobs: Boolean = false,
    @SerialName("daily_run_limit") val dailyRunLimit: Int? = null,
    val models: List<ModelGrant> = emptyList(),
)

@Serializable
data class AgentGrants(val grants: List<Grant>)

/**
 * What one person may do with one agent, after roles and grants (spec: Permission model).
 *
 * - [models] `null` = every model (owner); otherwise the granted model ids. The agent's default model
 *   (no `model_id`) is always allowed when [canChat].
 * - [profileId] the agent profile (tool level) this person's runs go to.
 */
data class Access(
    val canChat: Boolean,
    val canManageJobs: Boolean,
    val profileId: String?,
    val models: Map<String, Int?>?,
    val dailyRunLimit: Int?,
)

/**
 * The permission layer: deny by default, checked on every request.
 *
 * | Role | Chat | Models | Profile |
 * |---|---|---|---|
 * | owner | every agent | every model | the agent's first profile (most capable) |
 * | admin | every agent | as granted (else the default model) | as granted, else the first profile |
 * | member, restricted | only with a grant (`can_chat`) | as granted | as granted; restricted grants must name one |
 *
 * A person's own grant replaces their role's grant on that agent. Profiles are listed from most to least
 * capable (the owner orders them); a shared conversation uses the least capable profile among its
 * participants and only models every participant may use, so sharing never escalates.
 */
class Permissions(private val db: Db, private val agents: Agents, private val accounts: Accounts) {

    fun access(user: HubUser, agent: AgentRecord): Access {
        if (agent.hubId != user.hubId) return NONE
        if (user.isOwner) return Access(true, true, agent.profiles.firstOrNull()?.id, null, null)
        val g = grant(agent.id, "user", user.userId) ?: grant(agent.id, "role", user.role.name)
        if (user.role == Role.admin && g == null) return Access(true, true, agent.profiles.firstOrNull()?.id, emptyMap(), null)
        if (g == null || !g.canChat) return NONE.copy(canManageJobs = g?.canManageJobs == true && user.role != Role.restricted)
        val profile = g.profileId?.takeIf { p -> agent.profiles.any { it.id == p } } ?: agent.profiles.firstOrNull()?.id
        return Access(true, g.canManageJobs && user.role != Role.restricted, profile, g.models.associate { it.modelId to it.dailyLimit }, g.dailyRunLimit)
    }

    /** Check a run before it starts; returns the profile to use. Throws forbidden / limit_reached. */
    fun checkRun(user: HubUser, agent: AgentRecord, modelId: String?): Access {
        val a = access(user, agent)
        if (!a.canChat) throw HubException.forbidden("You don't have access to ${agent.name}. Ask the owner to grant it.")
        if (modelId != null && a.models != null && modelId !in a.models)
            throw HubException.forbidden("That model isn't granted to you on ${agent.name}.")
        a.dailyRunLimit?.let { limit ->
            if (runsToday(user.userId, agent.id, null) >= limit) throw limitReached("your daily limit of $limit messages to ${agent.name}")
        }
        if (modelId != null) a.models?.get(modelId)?.let { limit ->
            if (runsToday(user.userId, agent.id, modelId) >= limit) throw limitReached("your daily limit of $limit messages with this model")
        }
        return a
    }

    /** Everyone who may see [agent] (its card, status events): owner, admins, people with chat access. */
    fun viewers(agent: AgentRecord): List<String> {
        val members = db.query("SELECT u.id, m.role FROM user u JOIN membership m ON m.user_id = u.id WHERE m.hub_id = ? AND u.disabled_at IS NULL",
            agent.hubId) { it.getString(1) to it.getString(2) }
        return members.filter { (id, role) -> access(HubUser(id, agent.hubId, Role.valueOf(role), ""), agent).canChat }.map { it.first }
    }

    /** Everyone who may manage [agent]'s jobs: owner, admins, members granted `can_manage_jobs`. */
    fun jobManagers(agent: AgentRecord): List<String> {
        val members = db.query("SELECT u.id, m.role FROM user u JOIN membership m ON m.user_id = u.id WHERE m.hub_id = ? AND u.disabled_at IS NULL",
            agent.hubId) { it.getString(1) to it.getString(2) }
        return members.filter { (id, role) -> access(HubUser(id, agent.hubId, Role.valueOf(role), ""), agent).canManageJobs }.map { it.first }
    }

    fun runsToday(userId: String, agentId: String, modelId: String?): Int {
        val since = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC).toString()
        return if (modelId == null)
            db.query("SELECT COUNT(*) FROM run WHERE user_id = ? AND agent_id = ? AND created_at >= ?", userId, agentId, since) { it.getInt(1) }.single()
        else db.query("SELECT COUNT(*) FROM run WHERE user_id = ? AND agent_id = ? AND created_at >= ? AND model_id = ?",
            userId, agentId, since, modelId) { it.getInt(1) }.single()
    }

    // ── grants API (owner) ──────────────────────────────────────────────────

    fun getGrants(user: HubUser, agentId: String): AgentGrants {
        requireOwner(user)
        val agent = agents.record(agentId)?.takeIf { it.hubId == user.hubId } ?: throw HubException.notFound("agent")
        return AgentGrants(db.query("SELECT * FROM agent_grant WHERE agent_id = ? ORDER BY subject_kind, subject", agent.id) { row(it) })
    }

    fun setGrants(user: HubUser, agentId: String, input: AgentGrants): AgentGrants {
        requireOwner(user)
        val agent = agents.record(agentId)?.takeIf { it.hubId == user.hubId } ?: throw HubException.notFound("agent")
        val seen = mutableSetOf<String>()
        for (g in input.grants) {
            if ((g.role == null) == (g.userId == null)) throw HubException.invalid("each grant names exactly one of role or user_id")
            g.role?.let { r ->
                if (r !in setOf("admin", "member", "restricted")) throw HubException.invalid("role: admin, member or restricted (the owner always has access)")
                if (r == "restricted" && g.canChat && g.profileId == null && agent.profiles.size > 1)
                    throw HubException.invalid("a restricted grant must name its profile (restricted roles never get the most capable one by default)")
            }
            g.userId?.let { u ->
                val target = accounts.user(u)?.takeIf { it.hubId == user.hubId } ?: throw HubException.invalid("unknown user $u")
                if (target.isOwner) throw HubException.invalid("the owner always has access")
            }
            g.profileId?.let { p -> if (agent.profiles.none { it.id == p }) throw HubException.invalid("unknown profile $p") }
            g.dailyRunLimit?.let { if (it < 0) throw HubException.invalid("daily_run_limit must be ≥ 0") }
            g.models.forEach { if (it.modelId.isBlank() || (it.dailyLimit ?: 0) < 0) throw HubException.invalid("models: model_id required, daily_limit ≥ 0") }
            if (!seen.add(g.role ?: "user:${g.userId}")) throw HubException.invalid("one grant per role or person")
        }
        db.tx { c ->
            c.prepareStatement("DELETE FROM agent_grant WHERE agent_id = ?").use { Db.bind(it, arrayOf<Any?>(agent.id)); it.executeUpdate() }
            for (g in input.grants) c.prepareStatement("""INSERT INTO agent_grant (agent_id, hub_id, subject_kind, subject, profile_id, can_chat,
                    can_manage_jobs, daily_run_limit, models_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""").use {
                Db.bind(it, arrayOf<Any?>(agent.id, agent.hubId, if (g.role != null) "role" else "user", g.role ?: g.userId, g.profileId,
                    g.canChat, g.canManageJobs, g.dailyRunLimit, hubJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(ModelGrant.serializer()), g.models)))
                it.executeUpdate()
            }
        }
        accounts.activity(user.hubId, user.userId, agent.id, "grants_changed", """{"grants":${input.grants.size}}""")
        return getGrants(user, agentId)
    }

    // ── shared conversations ────────────────────────────────────────────────

    /** The least capable profile and the common models among [participants] (each must be able to chat). */
    fun sharedAccess(participants: List<HubUser>, agent: AgentRecord): Access? {
        val all = participants.map { access(it, agent) }
        if (all.any { !it.canChat }) return null
        val position = { p: String? -> agent.profiles.indexOfFirst { it.id == p }.let { if (it < 0) 0 else it } }
        val profile = all.maxByOrNull { position(it.profileId) }?.profileId
        val models = all.mapNotNull { it.models }.takeIf { it.isNotEmpty() }
            ?.reduce { acc, m -> acc.filterKeys { it in m }.mapValues { (k, v) -> listOfNotNull(v, m[k]).minOrNull() } }
        return Access(true, false, profile, models, all.mapNotNull { it.dailyRunLimit }.minOrNull())
    }

    private fun grant(agentId: String, kind: String, subject: String): Grant? =
        db.query("SELECT * FROM agent_grant WHERE agent_id = ? AND subject_kind = ? AND subject = ?", agentId, kind, subject) { row(it) }.firstOrNull()

    private fun row(rs: java.sql.ResultSet): Grant {
        val kind = rs.getString("subject_kind")
        return Grant(
            role = rs.getString("subject").takeIf { kind == "role" }, userId = rs.getString("subject").takeIf { kind == "user" },
            profileId = rs.str("profile_id"), canChat = rs.getInt("can_chat") == 1, canManageJobs = rs.getInt("can_manage_jobs") == 1,
            dailyRunLimit = rs.int("daily_run_limit"),
            models = hubJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ModelGrant.serializer()), rs.getString("models_json")),
        )
    }

    private fun requireOwner(user: HubUser) { if (!user.isOwner) throw HubException.forbidden("Only the owner sets who can use an agent.") }

    private fun limitReached(what: String) = HubException(HttpStatusCode.TooManyRequests, "limit_reached", "Daily limit reached",
        "You've reached $what. It resets at midnight UTC.")

    companion object {
        val NONE = Access(false, false, null, emptyMap(), null)
    }
}
