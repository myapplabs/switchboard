package llc.applabs.switchboard.hub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import llc.applabs.switchboard.hub.adapters.Health
import llc.applabs.switchboard.hub.adapters.ProfileTarget

class HermesAdapterTest {
    private val hermes = FakeHermes()
    private fun target(key: String = FakeHermes.KEY) = ProfileTarget("full", "http://mini-1:8642", key)

    @Test fun `capabilities from Hermes 0_14 map to the hub's`() = runTest {
        val c = hermes.adapter.capabilities(target())!!
        assertEquals(Capabilities(chat = true, streaming = true, stop = true, toolEvents = true, approvals = true,
            jobs = true, modelSwitching = true, perUserMemory = true, images = false, clarify = true, steer = true, reasoning = true), c)
        hermes.jobs = false
        assertFalse(hermes.adapter.capabilities(target())!!.jobs)
    }

    @Test fun `a passing connection test checks reachability, key, capabilities and runs`() = runTest {
        val t = hermes.adapter.test(listOf(target()))
        assertTrue(t.ok)
        assertEquals(listOf("reachable", "auth", "capabilities", "runs"), t.checks.map { it.name })
        assertTrue(t.capabilities!!.approvals)
    }

    @Test fun `failures say why`() = runTest {
        hermes.down = true
        val down = hermes.adapter.test(listOf(target())).checks.single()
        assertEquals("reachable" to false, down.name to down.ok)
        assertTrue(down.detail!!.startsWith("Connection refused at http://mini-1:8642"))
        hermes.down = false
        val badKey = hermes.adapter.test(listOf(target("wrong-key-0123456789"))).checks.last()
        assertEquals("auth", badKey.name)
        assertTrue("rejected the API key (HTTP 401)" in badKey.detail!!)
        hermes.hasCapabilities = false
        val old = hermes.adapter.test(listOf(target())).checks.last()
        assertTrue("0.14 or later" in old.detail!!)
        hermes.hasCapabilities = true; hermes.runs = false
        val noRuns = hermes.adapter.test(listOf(target())).checks.last()
        assertEquals("runs" to false, noRuns.name to noRuns.ok)
    }

    @Test fun `every profile is tested`() = runTest {
        val t = hermes.adapter.test(listOf(target(), ProfileTarget("safe", "http://mini-1:8643", "another-key-0123456789")))
        assertFalse(t.ok)
        assertEquals("safe", t.checks.first { !it.ok }.profile)
    }

    @Test fun `health on 0_22 sends the key and tells a rejected key from a down agent`() = runTest {
        assertEquals(Health.Up(0, null), hermes.adapter.health(target()))
        assertEquals(Health.KeyRejected, hermes.adapter.health(target("not-the-key-at-all")))
        val add = hermes.adapter.test(listOf(target("not-the-key-at-all"))).checks
        assertEquals(listOf("reachable" to true, "auth" to false), add.map { it.name to it.ok }, "reachable, then the key is the problem")
    }

    @Test fun `health reports activity, and on 0_14 needs no key`() = runTest {
        hermes.detailedNeedsKey = false
        assertEquals(Health.Up(0, null), hermes.adapter.health(target("not-the-key-at-all")))
        hermes.activeAgents = 2
        assertEquals(Health.Up(2, "Working on 2 runs"), hermes.adapter.health(target()))
        hermes.down = true
        assertTrue(hermes.adapter.health(target()) is Health.Down)
        hermes.down = false
        assertEquals(false, hermes.adapter.keyAccepted(target("wrong-key-0123456789")))
        assertEquals(true, hermes.adapter.keyAccepted(target()))
    }
}
