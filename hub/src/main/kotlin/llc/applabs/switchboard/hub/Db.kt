package llc.applabs.switchboard.hub

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * The hub's SQLite database: one connection, used under a lock (SQLite serialises writes anyway and
 * the hub's load is a small group's). Versioned migrations from `resources/db/V<n>__*.sql`, applied in
 * order once each. Every table carries `hub_id` (an organisation in the hosted tier).
 */
class Db private constructor(private val conn: Connection) : AutoCloseable {

    fun <T> tx(block: (Connection) -> T): T = synchronized(conn) {
        conn.autoCommit = false
        try {
            block(conn).also { conn.commit() }
        } catch (e: Throwable) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = synchronized(conn) {
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    fun update(sql: String, vararg args: Any?): Int = synchronized(conn) {
        conn.prepareStatement(sql).use { st -> bind(st, args); st.executeUpdate() }
    }

    override fun close() = conn.close()

    companion object {
        fun open(file: Path): Db = open("jdbc:sqlite:${file.toAbsolutePath()}")

        fun inMemory(): Db = open("jdbc:sqlite::memory:")

        private fun open(url: String): Db {
            val conn = DriverManager.getConnection(url)
            conn.createStatement().use {
                it.execute("PRAGMA foreign_keys = ON")
                it.execute("PRAGMA busy_timeout = 5000")
                if (!url.endsWith(":memory:")) it.execute("PRAGMA journal_mode = WAL")
            }
            return Db(conn).also { it.migrate() }
        }

        /** Migration files in order; add new ones at the end, never edit an applied one. */
        val MIGRATIONS = listOf("V001__init.sql", "V002__passkeys.sql", "V003__conversations.sql", "V004__household.sql", "V005__control.sql", "V006__push.sql", "V007__hub_names.sql", "V008__tasks.sql", "V009__coordinator.sql", "V010__forge_bridges.sql", "V011__job_runs.sql", "V012__relay.sql")

        internal fun bind(st: PreparedStatement, args: Array<out Any?>) {
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> st.setObject(i + 1, null)
                    is Int -> st.setInt(i + 1, a)
                    is Long -> st.setLong(i + 1, a)
                    is Boolean -> st.setInt(i + 1, if (a) 1 else 0)
                    else -> st.setString(i + 1, a.toString())
                }
            }
        }
    }

    private fun migrate() {
        synchronized(conn) {
            conn.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)") }
        }
        val applied = query("SELECT version FROM schema_version") { it.getInt(1) }.toSet()
        MIGRATIONS.forEachIndexed { i, name ->
            val version = i + 1
            if (version in applied) return@forEachIndexed
            val sql = Db::class.java.getResource("/db/$name")?.readText() ?: error("missing migration $name")
            tx { c ->
                // Comment lines may contain ';' — drop them before splitting into statements.
                sql.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                    .split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { stmt ->
                    c.createStatement().use { it.execute(stmt) }
                }
                c.prepareStatement("INSERT INTO schema_version (version, applied_at) VALUES (?, ?)").use {
                    it.setInt(1, version); it.setString(2, now()); it.executeUpdate()
                }
            }
        }
    }
}

fun ResultSet.str(col: String): String? = getString(col)
fun ResultSet.long(col: String): Long? = getLong(col).takeUnless { wasNull() }
fun ResultSet.int(col: String): Int? = getInt(col).takeUnless { wasNull() }
