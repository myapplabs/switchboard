package llc.applabs.switchboard.hub

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import llc.applabs.switchboard.hub.adapters.AgentJob
import llc.applabs.switchboard.hub.adapters.AgentJobs
import llc.applabs.switchboard.hub.adapters.JobFields
import llc.applabs.switchboard.hub.adapters.ProfileTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Serializable
data class JobRunOut(val at: String, val result: String?, val summary: String? = null)

@Serializable
data class JobOut(
    val id: String,
    @SerialName("agent_id") val agentId: String,
    val name: String,
    val prompt: String?,
    val schedule: String,
    val state: String,
    val repeat: Int?,
    @SerialName("last_run_at") val lastRunAt: String?,
    @SerialName("last_result") val lastResult: String?,
    @SerialName("next_run_at") val nextRunAt: String?,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("recent_runs") val recentRuns: List<JobRunOut> = emptyList(),
    /** Results are posted into a conversation here (through the agent's relay). */
    @SerialName("results_here") val resultsHere: Boolean = false,
    /** That conversation, for the person whose conversation it is. */
    @SerialName("results_session_id") val resultsSessionId: String? = null,
)

@Serializable
data class JobIn(
    val name: String? = null, val schedule: String? = null, val prompt: String? = null, val repeat: Int? = null,
    @SerialName("results_here") val resultsHere: Boolean? = null,
)

/**
 * Scheduled jobs (spec F17, contract `/agents/{id}/jobs…`), kept by the agent itself (Hermes cron).
 * Anyone who may use the agent sees its jobs; changing them needs job permission (owner, admins,
 * members granted `can_manage_jobs`; never restricted members). Jobs run on the agent's first profile.
 *
 * Agents keep only a job's last run, so [watch] checks every agent's jobs every minute and records each
 * new run (time, result, error) for `recent_runs`; a failed run notifies the people who manage the
 * agent's jobs (`job_failed`). A job seen for the first time is recorded without a notice.
 */
class Jobs(
    private val db: Db, private val agents: Agents, private val permissions: Permissions,
    private val accounts: Accounts, private val events: UserEvents,
) {
    var notifier: Notifier? = null
    lateinit var relay: Relay
    private val log = org.slf4j.LoggerFactory.getLogger(Jobs::class.java)

    suspend fun list(user: HubUser, agentId: String): Items<JobOut> {
        val (agent, api, target) = context(user, agentId, manage = false)
        return Items(api.jobs(target).map { out(agent.id, it, user) })
    }

    suspend fun get(user: HubUser, agentId: String, jobId: String): JobOut =
        list(user, agentId).items.firstOrNull { it.id == jobId } ?: throw HubException.notFound("job")

    suspend fun create(user: HubUser, agentId: String, input: JobIn): JobOut {
        val (agent, api, target) = context(user, agentId, manage = true)
        if (input.name.isNullOrBlank() || input.schedule.isNullOrBlank() || input.prompt.isNullOrBlank())
            throw HubException.invalid("name, schedule and prompt are required")
        var job = api.createJob(target, fields(input))
        if (input.resultsHere == true) job = withResults(user, agent, api, target, job, true)
        return changed(user, agent, "created", out(agent.id, job, user))
    }

    suspend fun update(user: HubUser, agentId: String, jobId: String, input: JobIn): JobOut {
        val (agent, api, target) = context(user, agentId, manage = true)
        val f = fields(input)
        var job = if (f == JobFields()) api.jobs(target).firstOrNull { it.id == jobId } ?: throw HubException.notFound("job")
            else api.updateJob(target, jobId, f)
        input.resultsHere?.let { job = withResults(user, agent, api, target, job, it) }
        return changed(user, agent, "updated", out(agent.id, job, user))
    }

    /** Forget [agentId]'s relay, switching its jobs back to their other targets first so they keep running. */
    suspend fun disconnectRelay(user: HubUser, agentId: String) {
        if (!user.isOwner) throw HubException.forbidden("Only the owner disconnects job results.")
        val (agent, api, target) = context(user, agentId, manage = true)
        for (job in api.jobs(target)) if (relay.delivery(agent.id, job.id) != null || job.deliver?.contains("relay:") == true)
            relay.setDelivery(user, agent, api, target, job.id, job.name, job.deliver, false)
        relay.remove(user, agentId)
    }

    private suspend fun withResults(user: HubUser, agent: AgentRecord, api: AgentJobs, target: ProfileTarget, job: AgentJob, on: Boolean): AgentJob {
        // Until the agent's gateway has connected, Hermes doesn't know the relay and refuses to run a job
        // that delivers there (blocked_config), so only a connected agent's jobs may be switched over.
        if (on && relay.statusOf(agent.id).status != "connected")
            throw HubException(HttpStatusCode.Conflict, "relay_not_set_up", "Job results not connected",
                "${agent.name} isn't connected for job results yet. Finish Connect in Jobs first (run the setup command on its computer).")
        val deliver = relay.setDelivery(user, agent, api, target, job.id, job.name, job.deliver, on)
        return job.copy(deliver = deliver)
    }

    suspend fun delete(user: HubUser, agentId: String, jobId: String) {
        val (agent, api, target) = context(user, agentId, manage = true)
        api.deleteJob(target, jobId)
        db.update("DELETE FROM job_run WHERE agent_id = ? AND job_id = ?", agent.id, jobId)
        db.update("DELETE FROM job_watch WHERE agent_id = ? AND job_id = ?", agent.id, jobId)
        relay.forget(agent.id, jobId)
        accounts.activity(user.hubId, user.userId, agent.id, "job_changed", """{"job_id":"$jobId","change":"deleted"}""")
        events.publish(agents.viewers(agent), "job.updated", mapOf("agent_id" to agent.id, "job_id" to jobId, "state" to "deleted", "last_result" to null))
    }

    suspend fun action(user: HubUser, agentId: String, jobId: String, action: String): JobOut {
        if (action !in setOf("pause", "resume", "run")) throw HubException.invalid("action: pause, resume or run")
        val (agent, api, target) = context(user, agentId, manage = true)
        return changed(user, agent, action, out(agent.id, api.jobAction(target, jobId, action), user))
    }

    private fun changed(user: HubUser, agent: AgentRecord, change: String, job: JobOut): JobOut {
        accounts.activity(user.hubId, user.userId, agent.id, "job_changed", """{"job_id":"${job.id}","change":"$change"}""")
        events.publish(agents.viewers(agent), "job.updated", mapOf("agent_id" to agent.id, "job_id" to job.id, "state" to job.state, "last_result" to job.lastResult))
        return job
    }

    private fun fields(i: JobIn): JobFields {
        i.name?.let { if (it.isBlank() || it.length > 100) throw HubException.invalid("name: 1–100 characters") }
        i.prompt?.let { if (it.isBlank() || it.length > 5000) throw HubException.invalid("prompt: 1–5000 characters") }
        i.schedule?.let { if (it.isBlank() || it.length > 100) throw HubException.invalid("schedule: e.g. \"every 6h\" or \"0 9 * * *\"") }
        i.repeat?.let { if (it < 1) throw HubException.invalid("repeat must be ≥ 1") }
        return JobFields(i.name?.trim(), i.schedule?.trim(), i.prompt, i.repeat)
    }

    private fun context(user: HubUser, agentId: String, manage: Boolean): Triple<AgentRecord, AgentJobs, ProfileTarget> {
        val agent = agents.record(agentId)?.takeIf { it.hubId == user.hubId } ?: throw HubException.notFound("agent")
        val access = permissions.access(user, agent)
        if (!access.canChat && !access.canManageJobs) throw HubException.notFound("agent")
        if (manage && !access.canManageJobs) throw HubException.forbidden("You don't have job permission on ${agent.name}.")
        val api = agents.adapterFor(agent) as? AgentJobs
        if (api == null || !agent.capabilities.jobs) throw HubException(HttpStatusCode.UnprocessableEntity, "not_supported", "No jobs", "${agent.name} doesn't run scheduled jobs.")
        val target = agents.targetFor(agent, null) ?: throw HubException.notFound("agent")
        return Triple(agent, api, target)
    }

    private fun out(agentId: String, j: AgentJob, viewer: HubUser): JobOut {
        val d = relay.delivery(agentId, j.id)
        val here = d != null && j.deliver?.split(",")?.any { it.trim() == "relay:${d.first}" } == true
        return JobOut(j.id, agentId, j.name, j.prompt, j.schedule, j.state, j.repeat, j.lastRunAt, j.lastResult, j.nextRunAt,
            lastError = j.lastError.takeIf { j.lastResult == "failure" }, recentRuns = recentRuns(agentId, j.id),
            resultsHere = here, resultsSessionId = d?.second?.takeIf { here && d.third == viewer.userId })
    }

    private fun recentRuns(agentId: String, jobId: String): List<JobRunOut> =
        db.query("SELECT ran_at, result, error FROM job_run WHERE agent_id = ? AND job_id = ? ORDER BY ran_at DESC LIMIT $RECENT",
            agentId, jobId) { JobRunOut(it.getString(1), it.getString(2), it.getString(3)) }

    // ── run history ─────────────────────────────────────────────────────────

    /** Check every agent's jobs every [intervalMs] and record new runs. */
    fun watch(scope: kotlinx.coroutines.CoroutineScope, intervalMs: Long = 60_000) = scope.launch {
        while (isActive) {
            for (id in agents.allIds()) runCatching { check(id) }.onFailure { log.debug("jobs of {}: {}", id, it.message) }
            delay(intervalMs)
        }
    }

    /** One check of [agentId]'s jobs; returns the runs it recorded. Skips agents without jobs or offline. */
    suspend fun check(agentId: String): List<JobRunOut> {
        val agent = agents.record(agentId) ?: return emptyList()
        val api = agents.adapterFor(agent) as? AgentJobs ?: return emptyList()
        if (!agent.capabilities.jobs) return emptyList()
        val target = agents.primaryTarget(agent) ?: return emptyList()
        val recorded = mutableListOf<JobRunOut>()
        for (job in api.jobs(target)) {
            val seen = db.query("SELECT last_run_at FROM job_watch WHERE agent_id = ? AND job_id = ?", agent.id, job.id) {
                Seen(it.getString(1)) }.firstOrNull()
            if (seen != null && seen.lastRunAt == job.lastRunAt) continue
            db.update("INSERT INTO job_watch (agent_id, job_id, last_run_at, seen_at) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (agent_id, job_id) DO UPDATE SET last_run_at = excluded.last_run_at, seen_at = excluded.seen_at",
                agent.id, job.id, job.lastRunAt, now())
            val at = job.lastRunAt ?: continue
            val error = job.lastError.takeIf { job.lastResult == "failure" }
            val inserted = db.update("INSERT OR IGNORE INTO job_run (id, agent_id, job_id, ran_at, result, error, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                newId("jr"), agent.id, job.id, at, job.lastResult, error, now())
            db.update("DELETE FROM job_run WHERE agent_id = ? AND job_id = ? AND id NOT IN " +
                "(SELECT id FROM job_run WHERE agent_id = ? AND job_id = ? ORDER BY ran_at DESC LIMIT $KEEP)", agent.id, job.id, agent.id, job.id)
            if (inserted == 0 || seen == null) continue  // first sighting: history only, no notice
            recorded += JobRunOut(at, job.lastResult, error)
            events.publish(agents.viewers(agent), "job.updated", mapOf("agent_id" to agent.id, "job_id" to job.id, "state" to job.state, "last_result" to job.lastResult))
            if (job.lastResult == "failure") {
                log.info("job {} on {} failed", job.name, agent.name)
                notifier?.notify(permissions.jobManagers(agent), "job_failed", agent.hubId, mapOf("agent_id" to agent.id, "job_id" to job.id))
            }
        }
        return recorded
    }

    private data class Seen(val lastRunAt: String?)

    companion object {
        const val RECENT = 10
        const val KEEP = 50
    }
}
