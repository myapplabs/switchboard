package llc.applabs.switchboard.hub

import io.ktor.http.HttpStatusCode
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class MemberOut(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    @SerialName("last_active_at") val lastActiveAt: String?,
    @SerialName("joined_at") val joinedAt: String,
)

@Serializable data class MemberPatch(val role: String? = null)
@Serializable data class InviteIn(val role: String, val note: String? = null)

@Serializable
data class InviteOut(
    val id: String, val role: String, val note: String? = null,
    @SerialName("created_by") val createdBy: String, @SerialName("expires_at") val expiresAt: String,
    val code: String? = null, val url: String? = null,
)

@Serializable
data class InvitePreview(
    @SerialName("hub_name") val hubName: String, @SerialName("invited_by") val invitedBy: String,
    val role: String, @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class ActivityOut(
    val id: String, val at: String, @SerialName("user_id") val userId: String?, @SerialName("agent_id") val agentId: String?,
    @SerialName("model_label") val modelLabel: String? = null, val action: String, val detail: JsonObject,
)

@Serializable
data class DeviceOut(
    val id: String, val platform: String, val name: String,
    @SerialName("last_used_at") val lastUsedAt: String?, @SerialName("this_device") val thisDevice: Boolean,
    @SerialName("push_enabled") val pushEnabled: Boolean = false,
    @SerialName("notify_prefs") val notifyPrefs: Map<String, Boolean> = emptyMap(),
)

/** Register this device for push (contract registerDevice): the FCM token and the relay's grant for it. */
@Serializable
data class DevicePushIn(
    val platform: String, @SerialName("push_token") val pushToken: String, @SerialName("relay_grant") val relayGrant: String? = null,
    val name: String? = null, @SerialName("notify_prefs") val notifyPrefs: Map<String, Boolean>? = null,
)

@Serializable
data class DevicePatch(
    @SerialName("push_token") val pushToken: String? = null, @SerialName("relay_grant") val relayGrant: String? = null,
    @SerialName("notify_prefs") val notifyPrefs: Map<String, Boolean>? = null,
)

/** An invite being redeemed (checked at passkey options, used up at verify). */
data class InviteRow(val id: String, val hubId: String, val role: Role, val createdBy: String)

/**
 * The hub's people (contract: the member, invite, activity and device endpoints): members and
 * roles, invites, the activity log (metadata only, kept 90 days) and each person's signed-in devices.
 *
 * Owner and admins manage members; admins only `member` and `restricted` people (spec: "not owners"),
 * and only the owner makes admins. Removing someone signs out all their devices at once, deletes their
 * private conversations (and the agents' copies) and takes them out of shared ones.
 */
class Members(
    private val db: Db,
    private val accounts: Accounts,
    private val events: UserEvents,
    private val config: HubConfig,
) {
    private val random = SecureRandom()
    /** Set by the hub: push notices. */
    var notifier: Notifier? = null

    /** Set by the hub: deletes a removed person's conversations everywhere. */
    var onMemberRemoved: suspend (HubUser) -> Unit = {}

    // ── members ─────────────────────────────────────────────────────────────

    fun list(user: HubUser): Items<MemberOut> {
        requireAdmin(user)
        return Items(db.query("""SELECT u.id, u.display_name, m.role, u.last_active_at, m.joined_at FROM user u JOIN membership m ON m.user_id = u.id
                                 WHERE m.hub_id = ? AND u.disabled_at IS NULL ORDER BY m.joined_at""", user.hubId) {
            MemberOut(it.getString(1), it.getString(2), it.getString(3), it.str("last_active_at"), it.getString(5))
        })
    }

    fun updateMember(user: HubUser, userId: String, patch: MemberPatch): MemberOut {
        requireAdmin(user)
        val target = member(user, userId)
        val role = patch.role?.let { r -> runCatching { Role.valueOf(r) }.getOrNull() ?: throw HubException.invalid("role: admin, member or restricted") }
            ?: return list(user).items.first { it.userId == userId }
        if (role == Role.owner) throw HubException.invalid("A hub has one owner.")
        if (target.isOwner) throw HubException.forbidden("The owner's role can't be changed.")
        if (!user.isOwner && (target.role == Role.admin || role == Role.admin)) throw HubException.forbidden("Only the owner manages admins.")
        db.update("UPDATE membership SET role = ? WHERE user_id = ? AND hub_id = ?", role.name, userId, user.hubId)
        accounts.activity(user.hubId, user.userId, null, "role_changed", """{"user_id":"$userId","from":"${target.role}","to":"$role"}""")
        events.publish(listOf(userId), "permissions.changed")
        return list(user).items.first { it.userId == userId }
    }

    suspend fun removeMember(user: HubUser, userId: String) {
        requireAdmin(user)
        val target = member(user, userId)
        if (target.isOwner) throw HubException.forbidden("The owner can't be removed.")
        if (target.userId == user.userId) throw HubException.forbidden("You can't remove yourself.")
        if (!user.isOwner && target.role == Role.admin) throw HubException.forbidden("Only the owner removes admins.")
        db.query("SELECT id FROM device WHERE user_id = ? AND revoked_at IS NULL", userId) { it.getString(1) }.forEach(accounts::revokeDevice)
        db.update("UPDATE token SET revoked_at = COALESCE(revoked_at, ?) WHERE user_id = ?", now(), userId)
        db.update("UPDATE user SET disabled_at = ? WHERE id = ?", now(), userId)
        db.update("DELETE FROM session_participant WHERE user_id = ?", userId)
        db.update("DELETE FROM agent_grant WHERE subject_kind = 'user' AND subject = ?", userId)
        events.publish(listOf(userId), "account.revoked")
        onMemberRemoved(target)
        accounts.activity(user.hubId, user.userId, null, "member_removed", """{"user_id":"$userId"}""")
    }

    private fun member(user: HubUser, userId: String): HubUser =
        accounts.user(userId)?.takeIf { it.hubId == user.hubId } ?: throw HubException.notFound("member")

    /** Record that [user] used the hub (at most every 5 minutes). */
    fun touch(user: HubUser) {
        db.update("UPDATE user SET last_active_at = ? WHERE id = ? AND (last_active_at IS NULL OR last_active_at < ?)",
            now(), user.userId, Instant.now().minus(5, ChronoUnit.MINUTES).toString())
    }

    // ── invites ─────────────────────────────────────────────────────────────

    fun createInvite(user: HubUser, input: InviteIn): InviteOut {
        requireAdmin(user)
        val role = runCatching { Role.valueOf(input.role) }.getOrNull()?.takeIf { it != Role.owner }
            ?: throw HubException.invalid("role: admin, member or restricted")
        if (role == Role.admin && !user.isOwner) throw HubException.forbidden("Only the owner invites admins.")
        input.note?.let { if (it.length > 120) throw HubException.invalid("note: at most 120 characters") }
        val raw = (1..12).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        val code = raw.chunked(4).joinToString("-")
        val id = newId("inv")
        val expires = Instant.now().plus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString()
        db.update("INSERT INTO invite (id, hub_id, code_hash, role, note, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, user.hubId, Accounts.hash(raw), role.name, input.note, user.userId, now(), expires)
        accounts.activity(user.hubId, user.userId, null, "invite_created", """{"role":"$role"}""")
        return InviteOut(id, role.name, input.note, user.userId, expires, code, inviteUrl(code))
    }

    /** The link in the invite's QR code: the app opens it (universal / app link on the Switchboard domain);
     *  the code travels in the fragment, so no web server ever sees it. */
    fun inviteUrl(code: String) = "https://$SWITCHBOARD_RP_ID/join#hub=${URLEncoder.encode(config.hubUrl, "UTF-8")}&code=$code"

    fun invites(user: HubUser): Items<InviteOut> {
        requireAdmin(user)
        return Items(db.query("""SELECT * FROM invite WHERE hub_id = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > ?
                                 ORDER BY created_at DESC""", user.hubId, now()) {
            InviteOut(it.getString("id"), it.getString("role"), it.str("note"), it.getString("created_by"), it.getString("expires_at"))
        })
    }

    fun revokeInvite(user: HubUser, id: String) {
        requireAdmin(user)
        if (db.update("UPDATE invite SET revoked_at = ? WHERE id = ? AND hub_id = ? AND used_at IS NULL", now(), id, user.hubId) == 0)
            throw HubException.notFound("invite")
    }

    fun preview(code: String): InvitePreview {
        val inv = check(code)
        val name = accounts.hub()?.second ?: config.hubName
        val by = accounts.user(inv.createdBy)?.displayName ?: "The owner"
        val expires = db.query("SELECT expires_at FROM invite WHERE id = ?", inv.id) { it.getString(1) }.single()
        return InvitePreview(name, by, inv.role.name, expires)
    }

    /** The invite behind [code], if it can still be used (404 unknown, 410 used / expired / revoked). */
    fun check(code: String): InviteRow {
        data class Row(val id: String, val hubId: String, val role: String, val createdBy: String, val used: String?, val revoked: String?, val expires: String)
        val row = db.query("SELECT * FROM invite WHERE code_hash = ?", Accounts.hash(normalize(code))) { rs ->
            Row(rs.getString("id"), rs.getString("hub_id"), rs.getString("role"), rs.getString("created_by"),
                rs.str("used_at"), rs.str("revoked_at"), rs.getString("expires_at"))
        }.firstOrNull() ?: throw HubException(HttpStatusCode.NotFound, "not_found", "Invite not found", "Check the code, or ask for a new invite.")
        if (row.used != null) throw HubException(HttpStatusCode.Gone, "invite_used", "Invite already used", "Ask for a new invite.")
        if (row.revoked != null || Instant.parse(row.expires).isBefore(Instant.now()))
            throw HubException(HttpStatusCode.Gone, "invite_expired", "Invite expired", "Invites last 24 hours. Ask for a new one.")
        return InviteRow(row.id, row.hubId, Role.valueOf(row.role), row.createdBy)
    }

    /** Use the invite up (at most once); false if someone else just did. */
    fun consume(inviteId: String, userId: String): Boolean =
        db.update("UPDATE invite SET used_at = ?, used_by = ? WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > ?",
            now(), userId, inviteId, now()) == 1

    fun joined(user: HubUser, inviteId: String) {
        accounts.activity(user.hubId, user.userId, null, "member_joined", """{"role":"${user.role}","invite_id":"$inviteId"}""")
        notifier?.notify(accounts.admins(user.hubId), "member_joined", user.hubId)
        events.publish(accounts.admins(user.hubId), "member.joined",
            mapOf("user_id" to user.userId, "display_name" to user.displayName, "role" to user.role.name))
    }

    // ── activity ────────────────────────────────────────────────────────────

    fun activity(user: HubUser, userId: String?, agentId: String?, cursor: String?, limit: Int): Page<ActivityOut> {
        requireAdmin(user)
        val offset = cursor?.let { runCatching { String(Base64.getUrlDecoder().decode(it)).toInt() }.getOrNull() } ?: 0
        val n = limit.coerceIn(1, 100)
        val where = StringBuilder("hub_id = ?")
        val args = mutableListOf<Any?>(user.hubId)
        userId?.let { where.append(" AND user_id = ?"); args += it }
        agentId?.let { where.append(" AND agent_id = ?"); args += it }
        val rows = db.query("SELECT * FROM activity WHERE $where ORDER BY at DESC, rowid DESC LIMIT ? OFFSET ?", *(args + listOf(n + 1, offset)).toTypedArray()) { rs ->
            val detail = runCatching { hubJson.parseToJsonElement(rs.getString("detail_json")).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            ActivityOut(rs.getString("id"), rs.getString("at"), rs.str("user_id"), rs.str("agent_id"),
                detail["model_label"]?.let { runCatching { (it as kotlinx.serialization.json.JsonPrimitive).content }.getOrNull() },
                rs.getString("action"), detail)
        }
        val next = if (rows.size > n) Base64.getUrlEncoder().withoutPadding().encodeToString((offset + n).toString().toByteArray()) else null
        return Page(rows.take(n), next)
    }

    /** Spec F21: retained 90 days. */
    fun pruneActivity() = db.update("DELETE FROM activity WHERE at < ?", Instant.now().minus(90, ChronoUnit.DAYS).toString())

    // ── devices ─────────────────────────────────────────────────────────────

    fun devices(user: HubUser, currentToken: String?): Items<DeviceOut> {
        val current = currentToken?.let { t -> db.query("SELECT device_id FROM token WHERE hash = ?", Accounts.hash(t)) { it.str("device_id") }.firstOrNull() }
        return Items(db.query("SELECT * FROM device WHERE user_id = ? AND revoked_at IS NULL ORDER BY last_used_at DESC", user.userId) {
            DeviceOut(it.getString("id"), it.str("platform") ?: "other", it.str("name") ?: "Device", it.str("last_used_at"), it.getString("id") == current,
                pushEnabled = it.str("push_token") != null && it.str("relay_grant") != null, notifyPrefs = prefs(it.getString("notify_prefs_json")))
        })
    }

    /** Push for the device behind [currentToken]; a test token without a device gets one. */
    fun registerPush(user: HubUser, currentToken: String, input: DevicePushIn): DeviceOut {
        if (input.platform !in setOf("android", "ios")) throw HubException.invalid("platform: android or ios")
        if (input.pushToken.length !in 20..4096) throw HubException.invalid("push_token looks wrong")
        input.notifyPrefs?.let(::checkPrefs)
        var deviceId = db.query("SELECT device_id FROM token WHERE hash = ?", Accounts.hash(currentToken)) { it.str("device_id") }.firstOrNull()
        if (deviceId == null) {
            deviceId = newId("dev")
            db.update("INSERT INTO device (id, hub_id, user_id, name, platform, created_at, last_used_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                deviceId, user.hubId, user.userId, input.name ?: "Device", input.platform, now(), now())
            db.update("UPDATE token SET device_id = ? WHERE hash = ?", deviceId, Accounts.hash(currentToken))
        }
        // One device per push token: a reinstalled app takes the token over from its old sign-in.
        db.update("UPDATE device SET push_token = NULL, relay_grant = NULL WHERE push_token = ? AND id != ?", input.pushToken, deviceId)
        db.update("UPDATE device SET platform = ?, push_token = ?, relay_grant = ?, notify_prefs_json = COALESCE(?, notify_prefs_json), name = COALESCE(?, name) WHERE id = ?",
            input.platform, input.pushToken, input.relayGrant, input.notifyPrefs?.let(::prefsJson), input.name, deviceId)
        return devices(user, currentToken).items.first { it.id == deviceId }
    }

    fun updateDevice(user: HubUser, id: String, currentToken: String?, patch: DevicePatch): DeviceOut {
        if (db.query("SELECT COUNT(*) FROM device WHERE id = ? AND user_id = ? AND revoked_at IS NULL", id, user.userId) { it.getInt(1) }.single() == 0)
            throw HubException.notFound("device")
        patch.notifyPrefs?.let(::checkPrefs)
        db.update("UPDATE device SET push_token = COALESCE(?, push_token), relay_grant = COALESCE(?, relay_grant), notify_prefs_json = COALESCE(?, notify_prefs_json) WHERE id = ?",
            patch.pushToken, patch.relayGrant, patch.notifyPrefs?.let(::prefsJson), id)
        return devices(user, currentToken).items.first { it.id == id }
    }

    private fun checkPrefs(p: Map<String, Boolean>) {
        val unknown = p.keys - Notifier.KINDS
        if (unknown.isNotEmpty()) throw HubException.invalid("unknown notification kinds: ${unknown.joinToString()}")
    }

    private val prefsSerializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), Boolean.serializer())
    private fun prefsJson(p: Map<String, Boolean>) = hubJson.encodeToString(prefsSerializer, p)
    private fun prefs(json: String): Map<String, Boolean> = runCatching { hubJson.decodeFromString(prefsSerializer, json) }.getOrDefault(emptyMap())

    fun removeDevice(user: HubUser, id: String) {
        val owned = db.query("SELECT COUNT(*) FROM device WHERE id = ? AND user_id = ?", id, user.userId) { it.getInt(1) }.single()
        if (owned == 0) throw HubException.notFound("device")
        accounts.revokeDevice(id)
        accounts.activity(user.hubId, user.userId, null, "device_revoked", """{"device_id":"$id"}""")
    }

    /**
     * A member deletes their own account (app stores: deletion from inside the app). Their conversations go
     * (here and on the agents), as do their passkeys, devices and notification tokens, personal quick
     * commands, grants, shares, job-result deliveries and FORGE bridges; the account is disabled and its name
     * replaced, so the activity log and board cards no longer name them. The owner deletes the whole hub
     * instead ([Hub.deleteAccount]).
     */
    suspend fun deleteSelf(user: HubUser) {
        if (user.isOwner) throw HubException.forbidden("The owner deletes the whole hub instead.")
        onMemberRemoved(user)
        val t = now()
        db.tx { c ->
            fun run(sql: String, vararg args: Any?) = c.prepareStatement(sql).use { Db.bind(it, args); it.executeUpdate() }
            run("UPDATE token SET revoked_at = COALESCE(revoked_at, ?) WHERE user_id = ?", t, user.userId)
            run("""UPDATE device SET revoked_at = COALESCE(revoked_at, ?), name = NULL, push_token = NULL, relay_grant = NULL,
                   notify_prefs_json = '{}' WHERE user_id = ?""", t, user.userId)
            run("DELETE FROM credential WHERE user_id = ?", user.userId)
            run("DELETE FROM session_participant WHERE user_id = ?", user.userId)
            run("DELETE FROM agent_grant WHERE subject_kind = 'user' AND subject = ?", user.userId)
            run("DELETE FROM quick_command WHERE user_id = ?", user.userId)
            run("DELETE FROM job_delivery WHERE user_id = ?", user.userId)
            run("UPDATE forge_bridge SET token_hash = NULL, pair_code_hash = NULL, revoked_at = COALESCE(revoked_at, ?) WHERE user_id = ?", t, user.userId)
            run("UPDATE user SET display_name = ?, disabled_at = COALESCE(disabled_at, ?) WHERE id = ?", DELETED_NAME, t, user.userId)
        }
        events.publish(listOf(user.userId), "account.revoked")
        accounts.activity(user.hubId, user.userId, null, "account_deleted", "{}")
    }

    private fun requireAdmin(user: HubUser) { if (!user.isAdmin) throw HubException.forbidden("Only the owner and admins manage members.") }

    companion object {
        const val DELETED_NAME = "Deleted member"
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }
    }
}
