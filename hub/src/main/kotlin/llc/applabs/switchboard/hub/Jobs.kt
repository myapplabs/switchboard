package llc.applabs.switchboard.hub

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import llc.applabs.switchboard.hub.adapters.AgentJob
import llc.applabs.switchboard.hub.adapters.AgentJobs
import llc.applabs.switchboard.hub.adapters.JobFields
import llc.applabs.switchboard.hub.adapters.ProfileTarget

@Serializable
data class JobRunOut(val at: String, val result: String, val summary: String? = null)

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
    @SerialName("recent_runs") val recentRuns: List<JobRunOut> = emptyList(),
)

@Serializable
data class JobIn(val name: String? = null, val schedule: String? = null, val prompt: String? = null, val repeat: Int? = null)

/**
 * Scheduled jobs (spec F17, contract `/agents/{id}/jobs…`), kept by the agent itself (Hermes cron).
 * Anyone who may use the agent sees its jobs; changing them needs job permission (owner, admins,
 * members granted `can_manage_jobs`; never restricted members). Jobs run on the agent's first profile.
 */
class Jobs(private val agents: Agents, private val permissions: Permissions, private val accounts: Accounts, private val events: UserEvents) {

    suspend fun list(user: HubUser, agentId: String): Items<JobOut> {
        val (agent, api, target) = context(user, agentId, manage = false)
        return Items(api.jobs(target).map { out(agent.id, it) })
    }

    suspend fun get(user: HubUser, agentId: String, jobId: String): JobOut =
        list(user, agentId).items.firstOrNull { it.id == jobId } ?: throw HubException.notFound("job")

    suspend fun create(user: HubUser, agentId: String, input: JobIn): JobOut {
        val (agent, api, target) = context(user, agentId, manage = true)
        if (input.name.isNullOrBlank() || input.schedule.isNullOrBlank() || input.prompt.isNullOrBlank())
            throw HubException.invalid("name, schedule and prompt are required")
        return changed(user, agent, "created", out(agent.id, api.createJob(target, fields(input))))
    }

    suspend fun update(user: HubUser, agentId: String, jobId: String, input: JobIn): JobOut {
        val (agent, api, target) = context(user, agentId, manage = true)
        return changed(user, agent, "updated", out(agent.id, api.updateJob(target, jobId, fields(input))))
    }

    suspend fun delete(user: HubUser, agentId: String, jobId: String) {
        val (agent, api, target) = context(user, agentId, manage = true)
        api.deleteJob(target, jobId)
        accounts.activity(user.hubId, user.userId, agent.id, "job_changed", """{"job_id":"$jobId","change":"deleted"}""")
        events.publish(agents.viewers(agent), "job.updated", mapOf("agent_id" to agent.id, "job_id" to jobId, "state" to "deleted", "last_result" to null))
    }

    suspend fun action(user: HubUser, agentId: String, jobId: String, action: String): JobOut {
        if (action !in setOf("pause", "resume", "run")) throw HubException.invalid("action: pause, resume or run")
        val (agent, api, target) = context(user, agentId, manage = true)
        return changed(user, agent, action, out(agent.id, api.jobAction(target, jobId, action)))
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

    private fun out(agentId: String, j: AgentJob) =
        JobOut(j.id, agentId, j.name, j.prompt, j.schedule, j.state, j.repeat, j.lastRunAt, j.lastResult, j.nextRunAt)
}
