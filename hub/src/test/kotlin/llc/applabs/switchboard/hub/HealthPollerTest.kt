package llc.applabs.switchboard.hub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class HealthPollerTest {
    private val hermes = FakeHermes()

    private fun TestScope.hub() = Hub(HubConfig(), Db.inMemory(), SecretBox.random(), mapOf("hermes" to hermes.inMemory),
        backgroundScope, healthIntervalMs = 15_000)

    private suspend fun Hub.addAgent(): Pair<HubUser, String> {
        val hh = accounts.ensureHub("Home")
        val owner = accounts.createUser(hh, "Paul", Role.owner)
        val id = agents.add(owner, AgentInput("hermes", "mini-ops", "http://mini-ops:8642",
            listOf(ProfileInput("full", null, FakeHermes.KEY)))).id
        return owner to id
    }

    /** All agent.status events on [user]'s channel so far, each validated against the contract schema. */
    private fun Hub.statuses(user: HubUser): List<String> {
        val got = mutableListOf<UserEvent>()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(200) { events.subscribe(user.userId, 0).collect { got += it } }
        }
        got.forEach { assertEquals(emptyList(), ContractSchema.eventErrors(it.json), it.json) }
        return got.filter { it.type == "agent.status" }
            .map { hubJson.parseToJsonElement(it.json).jsonObject["status"]!!.jsonPrimitive.content }
    }

    @Test fun `online, busy, offline with backoff, back online`() = runTest {
        val hub = hub()
        val (owner, id) = hub.addAgent()
        runCurrent()
        assertEquals("online", hub.agents.record(id)!!.status)
        hermes.activeAgents = 1
        advanceTimeBy(15_001)
        assertEquals("busy" to "Working on 1 run", hub.agents.record(id)!!.let { it.status to it.activity })
        hermes.down = true
        advanceTimeBy(15_001)
        assertEquals("offline", hub.agents.record(id)!!.status)
        val before = hermes.calls.count { it.endsWith("/health/detailed") }
        fun polls() = hermes.calls.count { it.endsWith("/health/detailed") } - before
        advanceTimeBy(2_001); advanceTimeBy(5_001); advanceTimeBy(15_001)
        assertEquals(3, polls(), "retries after 2 s, 5 s and 15 s")
        advanceTimeBy(59_000)
        assertEquals(3, polls(), "then every 60 s")
        hermes.down = false; hermes.activeAgents = 0
        advanceTimeBy(1_100)
        assertEquals("online", hub.agents.record(id)!!.status)
        assertEquals(listOf("busy", "offline", "online"), hub.statuses(owner))
    }

    @Test fun `a rejected key stops polling until the agent is changed`() = runTest {
        val hub = hub()
        val (owner, id) = hub.addAgent()
        runCurrent()
        hermes.key = "rotated-on-the-agent-0123456"
        advanceTimeBy(15_001 * 20)
        assertEquals("key_rejected", hub.agents.record(id)!!.status)
        val calls = hermes.calls.size
        advanceTimeBy(600_000)
        assertEquals(calls, hermes.calls.size, "no polling after key_rejected")
        assertTrue("key_rejected" in hub.statuses(owner))
        hub.agents.update(owner, id, AgentPatch(profiles = listOf(ProfilePatch(
            id = hub.agents.record(id)!!.profiles.single().id, name = "full", apiKey = "rotated-on-the-agent-0123456"))))
        runCurrent()
        assertEquals("online", hub.agents.record(id)!!.status)
    }
}
