package llc.applabs.switchboard.hub

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import llc.applabs.switchboard.hub.adapters.AgentAdapter
import llc.applabs.switchboard.hub.adapters.Health
import org.slf4j.LoggerFactory

/**
 * Watches every agent (product spec F3, error mapping):
 *
 * - healthy → poll every [intervalMs]; `busy` while the agent reports active runs;
 * - not answering → `offline`, retried after 2 s, 5 s, 15 s, then every 60 s;
 * - key rejected (401/403) → `key_rejected`, polling stops until the owner changes the agent;
 * - the key is re-checked every [keyCheckEvery] healthy polls and after every recovery.
 *
 * Each change of status, activity or active runs is sent as `agent.status` to the users who see the agent.
 */
class HealthPoller(
    private val agents: Agents,
    private val accounts: Accounts,
    private val events: UserEvents,
    private val adapters: Map<String, AgentAdapter>,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 15_000,
    private val keyCheckEvery: Int = 20,
    private val backoffMs: List<Long> = listOf(2_000, 5_000, 15_000, 60_000),
) {
    private val log = LoggerFactory.getLogger(HealthPoller::class.java)
    private val jobs = ConcurrentHashMap<String, Job>()

    fun start() = agents.allIds().forEach(::watch)

    /** (Re)start watching [agentId] — after it is added or changed; stops if it was removed. */
    fun watch(agentId: String) {
        jobs.remove(agentId)?.cancel()
        if (agents.record(agentId) == null) return
        jobs[agentId] = scope.launch { loop(agentId) }
    }

    fun stopAll() = jobs.values.forEach { it.cancel() }

    private suspend fun loop(agentId: String) {
        var failures = 0
        var healthyPolls = 0
        var verifyKey = true
        while (scope.isActive) {
            val record = agents.record(agentId) ?: return
            val adapter = adapters[record.type] ?: return
            val target = agents.primaryTarget(record) ?: return
            when (val h = runCatching { adapter.health(target) }.getOrElse { Health.Down(it.message ?: "error") }) {
                is Health.Up -> {
                    if (verifyKey || healthyPolls % keyCheckEvery == 0) {
                        if (adapter.keyAccepted(target) == false) { report(record, "key_rejected", "The agent rejected its API key", 0, true); return }
                        // Same moment: refresh capabilities, so an upgraded agent's new features show up.
                        runCatching { adapter.capabilities(target) }.getOrNull()?.let { caps ->
                            if (agents.setCapabilities(record.id, caps)) log.info("agent {}: capabilities changed", record.name)
                        }
                        verifyKey = false
                    }
                    failures = 0
                    healthyPolls += 1
                    report(record, if (h.activeRuns > 0) "busy" else "online", h.activity, h.activeRuns, seen = true)
                    delay(intervalMs)
                }
                is Health.Down -> {
                    if (failures == 0) log.info("agent {} offline: {}", record.name, h.reason)
                    report(record, "offline", null, 0, seen = false)
                    delay(backoffMs[minOf(failures, backoffMs.lastIndex)])
                    failures += 1
                    verifyKey = true
                    healthyPolls = 0
                }
                Health.KeyRejected -> { report(record, "key_rejected", "The agent rejected its API key", 0, true); return }
            }
        }
    }

    private fun report(r: AgentRecord, status: String, activity: String?, activeRuns: Int, seen: Boolean) {
        if (agents.setHealth(r.id, status, activity, activeRuns, seen)) {
            val now = agents.record(r.id) ?: return
            events.publish(agents.viewers(now), "agent.status", mapOf(
                "agent_id" to r.id, "status" to status, "activity" to activity,
                "last_seen_at" to now.lastSeenAt, "active_runs" to activeRuns,
            ))
            if (status == "key_rejected") accounts.activity(r.hubId, null, r.id, "agent_changed", """{"status":"key_rejected"}""")
        }
    }
}
