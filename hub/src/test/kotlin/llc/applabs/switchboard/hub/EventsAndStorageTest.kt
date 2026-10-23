package llc.applabs.switchboard.hub

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class EventsAndStorageTest {

    private fun collect(events: UserEvents, user: String, last: Long?, n: Int) = runBlocking {
        withTimeoutOrNull(500) { events.subscribe(user, last).take(n).toList() } ?: emptyList()
    }

    @Test fun `each user has their own sequence and reconnecting replays what was missed`() {
        val events = UserEvents()
        events.publish(listOf("a", "b"), "agent.changed", mapOf("agent_id" to "agt_1"))
        events.publish(listOf("a"), "agent.removed", mapOf("agent_id" to "agt_1"))
        val a = collect(events, "a", 0, 2)
        assertEquals(listOf(1L to "agent.changed", 2L to "agent.removed"), a.map { it.seq to it.type })
        assertEquals(listOf(1L), collect(events, "b", 0, 1).map { it.seq })
        assertEquals(listOf(2L), collect(events, "a", 1, 1).map { it.seq }, "after Last-Event-ID 1")
        a.forEach { assertEquals(emptyList(), ContractSchema.eventErrors(it.json), it.json) }
    }

    @Test fun `a gap older than the buffer becomes one resync`() {
        val events = UserEvents(bufferSize = 2)
        repeat(5) { events.publish(listOf("a"), "agent.changed", mapOf("agent_id" to "agt_$it")) }
        val got = collect(events, "a", 1, 1).single()
        assertEquals("resync", got.type)
        assertEquals(emptyList(), ContractSchema.eventErrors(got.json))
    }

    @Test fun `secrets are encrypted, tamper-proof and the master key is private`() {
        val dir = Files.createTempDirectory("hub")
        val box = SecretBox.fromDataDir(dir)
        val sealed = box.seal("api-key-0123456789")
        assertNotEquals(sealed, box.seal("api-key-0123456789"), "a new IV every time")
        assertEquals("api-key-0123456789", SecretBox.fromDataDir(dir).open(sealed))
        val flipped = sealed.dropLast(2) + (if (sealed[sealed.length - 2] == 'A') "B" else "A") + sealed.last()
        assertFails { box.open(flipped) }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("master.key"))))
    }

    @Test fun `migrations run once and a reopened database keeps its data`() {
        val file = Files.createTempDirectory("hub").resolve("hub.db")
        Db.open(file).use { Accounts(it).ensureHub("Home") }
        Db.open(file).use { db ->
            assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14), db.query("SELECT version FROM schema_version ORDER BY version") { it.getInt(1) })
            assertEquals("Home", Accounts(db).hub()!!.second)
        }
    }

    @Test fun `a database from before the rename keeps its hub, people and links`() {
        val file = Files.createTempDirectory("hub").resolve("hub.db")
        // Build a version-6 database the way older hubs did.
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys = ON")
                st.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)")
                Db.MIGRATIONS.take(6).forEachIndexed { i, name ->
                    Db::class.java.getResource("/db/$name")!!.readText().lines().filterNot { it.trimStart().startsWith("--") }
                        .joinToString("\n").split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach(st::execute)
                    st.execute("INSERT INTO schema_version VALUES (${i + 1}, '2026-10-01T00:00:00Z')")
                }
                st.execute("INSERT INTO household (id, name, created_at) VALUES ('hh_old', 'Office', '2026-10-01T00:00:00Z')")
            }
        }
        Db.open(file).use { db ->
            assertEquals("hh_old" to "Office", Accounts(db).hub())
            val owner = Accounts(db).createUser("hh_old", "Pat", Role.owner)
            assertEquals("hh_old", owner.hubId)
            assertEquals(emptyList(), db.query("PRAGMA foreign_key_check") { it.getString(1) }, "links point at the renamed table")
            assertEquals(listOf("hub"), db.query("SELECT DISTINCT \"table\" FROM pragma_foreign_key_list('user')") { it.getString(1) })
        }
    }

    @Test fun `the data folder is made private at startup, also for older installs`() {
        val dir = Files.createTempDirectory("hub")
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxr-x"))
        val db = Files.writeString(dir.resolve("hub.db"), "x")
        Files.setPosixFilePermissions(db, PosixFilePermissions.fromString("rw-rw-r--"))
        restrictDataDir(dir)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(db)))
    }
}
