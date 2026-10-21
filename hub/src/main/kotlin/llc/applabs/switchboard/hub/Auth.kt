package llc.applabs.switchboard.hub

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/** The signed-in user, as every request sees them. */
data class HubUser(val userId: String, val hubId: String, val role: Role, val displayName: String) {
    val isOwner get() = role == Role.owner
    val isAdmin get() = role == Role.owner || role == Role.admin
}

/**
 * Accounts and bearer tokens. Tokens are random 32-byte values; only their SHA-256 hash is stored, so a
 * copied database does not hand out sessions. A passkey sign-in creates a device and gives it a
 * 15-minute access token and a 30-day refresh token ([signIn]); refresh tokens rotate, and presenting
 * one that was already used signs that device out (it was copied). The host-side CLI can also issue an
 * `admin` token for testing (`switchboard-hub dev-token`).
 */
class Accounts(private val db: Db) {
    private val random = SecureRandom()

    fun hub(): Pair<String, String>? =
        db.query("SELECT id, name FROM hub ORDER BY created_at LIMIT 1") { it.getString(1) to it.getString(2) }.firstOrNull()

    fun ensureHub(name: String): String =
        hub()?.first ?: newId("hh").also { id -> db.update("INSERT INTO hub (id, name, created_at) VALUES (?, ?, ?)", id, name, now()) }

    fun hasOwner(): Boolean =
        db.query("SELECT COUNT(*) FROM membership WHERE role = 'owner'") { it.getInt(1) }.first() > 0

    fun owner(hubId: String): HubUser? = db.query(
        """SELECT u.id, u.display_name FROM user u JOIN membership m ON m.user_id = u.id
           WHERE m.hub_id = ? AND m.role = 'owner' AND u.disabled_at IS NULL LIMIT 1""", hubId,
    ) { HubUser(it.getString(1), hubId, Role.owner, it.getString(2)) }.firstOrNull()

    fun createUser(hubId: String, displayName: String, role: Role): HubUser {
        val id = newId("usr")
        db.tx { c ->
            c.prepareStatement("INSERT INTO user (id, hub_id, display_name, created_at) VALUES (?, ?, ?, ?)").use {
                Db.bind(it, arrayOf<Any?>(id, hubId, displayName, now())); it.executeUpdate()
            }
            c.prepareStatement("INSERT INTO membership (user_id, hub_id, role, joined_at) VALUES (?, ?, ?, ?)").use {
                Db.bind(it, arrayOf<Any?>(id, hubId, role.name, now())); it.executeUpdate()
            }
        }
        return HubUser(id, hubId, role, displayName)
    }

    /** A new token for [user]; returns the raw value (shown once). */
    fun issue(user: HubUser, kind: String, ttlSeconds: Long, deviceName: String? = null, deviceId: String? = null): String {
        val raw = randomToken()
        val expires = Instant.now().plus(ttlSeconds, ChronoUnit.SECONDS).toString()
        db.update("INSERT INTO token (hash, hub_id, user_id, kind, device_name, created_at, expires_at, device_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            hash(raw), user.hubId, user.userId, kind, deviceName, now(), expires, deviceId)
        return raw
    }

    fun randomToken(bytes: Int = 32): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { random.nextBytes(it) })

    /** A passkey sign-in: a new device for [user] with its first access + refresh tokens. */
    fun signIn(user: HubUser, deviceName: String?, credentialId: String?, platform: String? = null): Session {
        val deviceId = newId("dev")
        val p = platform?.lowercase()?.takeIf { it in setOf("ios", "android") }
        db.update("INSERT INTO device (id, hub_id, user_id, credential_id, name, platform, created_at, last_used_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            deviceId, user.hubId, user.userId, credentialId, deviceName, p, now(), now())
        activity(user.hubId, user.userId, null, "signed_in", """{"device_id":"$deviceId"}""")
        return Session(tokens(user, deviceId, deviceName), me(user))
    }

    private fun tokens(user: HubUser, deviceId: String, deviceName: String?) = Tokens(
        accessToken = issue(user, "access", ACCESS_TTL_S, deviceName, deviceId),
        refreshToken = issue(user, "refresh", REFRESH_TTL_S, deviceName, deviceId),
        expiresIn = ACCESS_TTL_S,
    )

    /** New tokens for a refresh token (which is used up). A reused refresh token revokes its device. */
    fun refresh(raw: String): Tokens {
        data class Row(val user: HubUser, val deviceId: String?, val deviceName: String?, val revoked: Boolean, val expired: Boolean, val deviceRevoked: Boolean)
        val row = db.query(
            """SELECT t.user_id, t.hub_id, t.device_id, t.device_name, t.revoked_at, t.expires_at, m.role, u.display_name, d.revoked_at AS device_revoked
               FROM token t JOIN user u ON u.id = t.user_id JOIN membership m ON m.user_id = t.user_id AND m.hub_id = t.hub_id
               LEFT JOIN device d ON d.id = t.device_id
               WHERE t.hash = ? AND t.kind = 'refresh' AND u.disabled_at IS NULL""", hash(raw),
        ) { rs ->
            Row(HubUser(rs.getString("user_id"), rs.getString("hub_id"), Role.valueOf(rs.getString("role")), rs.getString("display_name")),
                rs.str("device_id"), rs.str("device_name"), rs.str("revoked_at") != null,
                Instant.parse(rs.getString("expires_at")).isBefore(Instant.now()), rs.str("device_revoked") != null)
        }.firstOrNull() ?: throw HubException.unauthorized()
        if (row.deviceId == null || row.deviceRevoked || row.expired) throw HubException.unauthorized()
        if (row.revoked) {
            // Used twice: someone else has a copy. Sign the device out everywhere.
            revokeDevice(row.deviceId)
            activity(row.user.hubId, row.user.userId, null, "device_revoked", """{"device_id":"${row.deviceId}","reason":"refresh_reused"}""")
            throw HubException.unauthorized()
        }
        val used = db.update("UPDATE token SET revoked_at = ? WHERE hash = ? AND revoked_at IS NULL", now(), hash(raw))
        if (used == 0) throw HubException.unauthorized()   // a concurrent refresh won
        db.update("UPDATE device SET last_used_at = ? WHERE id = ?", now(), row.deviceId)
        return tokens(row.user, row.deviceId, row.deviceName)
    }

    /** Sign out: the device behind [raw] (and every token it holds); a device-less token alone. */
    fun signOut(raw: String) {
        val deviceId = db.query("SELECT device_id FROM token WHERE hash = ?", hash(raw)) { it.str("device_id") }.firstOrNull()
        if (deviceId != null) revokeDevice(deviceId) else revoke(raw)
    }

    fun revokeDevice(deviceId: String) = db.tx { c ->
        c.prepareStatement("UPDATE device SET revoked_at = COALESCE(revoked_at, ?) WHERE id = ?").use { Db.bind(it, arrayOf<Any?>(now(), deviceId)); it.executeUpdate() }
        c.prepareStatement("UPDATE token SET revoked_at = COALESCE(revoked_at, ?) WHERE device_id = ?").use { Db.bind(it, arrayOf<Any?>(now(), deviceId)); it.executeUpdate() }
    }

    fun user(userId: String): HubUser? = db.query(
        """SELECT u.id, u.hub_id, m.role, u.display_name FROM user u JOIN membership m ON m.user_id = u.id
           WHERE u.id = ? AND u.disabled_at IS NULL""", userId,
    ) { HubUser(it.getString(1), it.getString(2), Role.valueOf(it.getString(3)), it.getString(4)) }.firstOrNull()

    /** The user behind a bearer token, or null (unknown, expired, revoked, or a refresh token). */
    fun authenticate(raw: String): HubUser? = db.query(
        """SELECT t.user_id, t.hub_id, t.expires_at, m.role, u.display_name FROM token t
           JOIN user u ON u.id = t.user_id JOIN membership m ON m.user_id = t.user_id AND m.hub_id = t.hub_id
           LEFT JOIN device d ON d.id = t.device_id
           WHERE t.hash = ? AND t.revoked_at IS NULL AND t.kind IN ('access', 'admin') AND u.disabled_at IS NULL
             AND d.revoked_at IS NULL""",
        hash(raw),
    ) { rs ->
        val expires = Instant.parse(rs.getString("expires_at"))
        if (expires.isBefore(Instant.now())) null
        else HubUser(rs.getString("user_id"), rs.getString("hub_id"), Role.valueOf(rs.getString("role")), rs.getString("display_name"))
    }.firstOrNull()

    fun revoke(raw: String) = db.update("UPDATE token SET revoked_at = ? WHERE hash = ?", now(), hash(raw))

    fun me(user: HubUser): Me {
        val (hid, hname) = hub() ?: (user.hubId to "Home")
        return Me(user.userId, user.displayName, user.role.name, HubRef(hid, hname),
            MePermissions(manageMembers = user.isAdmin, manageAgents = user.isOwner, viewActivity = user.isAdmin, approve = user.isAdmin))
    }

    /** Users who manage agents and see every agent (owner and admins) — M0 recipients of agent events. */
    fun memberIds(hubId: String): List<String> = db.query(
        "SELECT m.user_id FROM membership m JOIN user u ON u.id = m.user_id WHERE m.hub_id = ? AND u.disabled_at IS NULL", hubId) { it.getString(1) }

    fun admins(hubId: String): List<String> = db.query(
        "SELECT user_id FROM membership WHERE hub_id = ? AND role IN ('owner', 'admin')", hubId) { it.getString(1) }

    fun activity(hubId: String, userId: String?, agentId: String?, action: String, detail: String = "{}") {
        db.update("INSERT INTO activity (id, hub_id, at, user_id, agent_id, action, detail_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
            newId("act"), hubId, now(), userId, agentId, action, detail)
    }

    companion object {
        const val ACCESS_TTL_S = 15L * 60
        const val REFRESH_TTL_S = 30L * 24 * 3600

        fun hash(raw: String): String =
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
