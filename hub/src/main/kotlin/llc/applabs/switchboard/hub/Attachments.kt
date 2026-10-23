package llc.applabs.switchboard.hub

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

@Serializable
data class AttachmentOut(
    val id: String,
    val name: String,
    @SerialName("content_type") val contentType: String,
    val size: Long,
)

/** A stored attachment, as the hub needs it to send a run. */
data class StoredAttachment(val id: String, val sessionId: String, val ownerUserId: String, val name: String,
                            val contentType: String, val size: Long, val messageId: String?) {
    val out get() = AttachmentOut(id, name, contentType, size)
    val isImage get() = contentType.lowercase() in INLINE_IMAGE_TYPES
}

private val INLINE_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

/**
 * Files and photos sent with messages. The app uploads each one to its conversation first, then names them
 * when it sends the message. Bytes live in [dir] (the data folder's `files/`), one file per attachment named
 * by its id; deleting a conversation, an account or the hub deletes them too.
 *
 * Agents can't reach the app's sign-in, so each sent attachment gets its own download link for the agent
 * (`/files/{id}/{token}/{name}`): a random token, stored hashed, valid for [AGENT_LINK_DAYS] days.
 */
class Attachments(private val db: Db, private val dir: Path) {
    private val random = SecureRandom()
    private val log = org.slf4j.LoggerFactory.getLogger(Attachments::class.java)

    init {
        Files.createDirectories(dir)
        runCatching { Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
    }

    private fun file(id: String): Path {
        require(id.matches(Regex("att_[a-z0-9]{1,40}"))) { "bad attachment id" }
        return dir.resolve(id)
    }

    /** Store an upload for [session] (not yet part of a message). */
    fun upload(user: HubUser, session: ConversationRecord, rawName: String?, contentType: String?, bytes: ByteArray): AttachmentOut {
        if (bytes.isEmpty()) throw HubException.invalid("The file is empty.")
        if (bytes.size > MAX_BYTES) throw HubException(HttpStatusCode.PayloadTooLarge, "too_large", "File too large",
            "Files can be up to ${MAX_BYTES / 1_000_000} MB.")
        val pending = db.query("SELECT COUNT(*) FROM attachment WHERE session_id = ? AND owner_user_id = ? AND message_id IS NULL",
            session.id, user.userId) { it.getInt(1) }.single()
        if (pending >= MAX_PER_MESSAGE * 2) throw HubException(HttpStatusCode.TooManyRequests, "too_many_files", "Too many files",
            "Send or remove the files you've added before adding more.")
        val id = newId("att")
        val name = cleanName(rawName)
        val type = cleanType(contentType)
        val path = file(id)
        Files.write(path, bytes)
        runCatching { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        db.update("""INSERT INTO attachment (id, hub_id, session_id, owner_user_id, name, content_type, size, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, session.hubId, session.id, user.userId, name, type, bytes.size.toLong(), now())
        return AttachmentOut(id, name, type, bytes.size.toLong())
    }

    /** The uploads [ids] of [user] in [sessionId] that aren't part of a message yet, in that order; anything else is refused. */
    fun unsent(user: HubUser, sessionId: String, ids: List<String>): List<StoredAttachment> {
        if (ids.size > MAX_PER_MESSAGE) throw HubException.invalid("attachments: at most $MAX_PER_MESSAGE files per message")
        if (ids.toSet().size != ids.size) throw HubException.invalid("attachments: each file once")
        return ids.map { id ->
            stored(id)?.takeIf { it.sessionId == sessionId && it.ownerUserId == user.userId && it.messageId == null }
                ?: throw HubException.invalid("attachments: $id isn't a file you added to this conversation")
        }
    }

    /** Attach [items] to [messageId] and mint each one's download link for the agent (raw tokens, by id). */
    fun attach(items: List<StoredAttachment>, messageId: String): Map<String, String> = items.associate { a ->
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also { random.nextBytes(it) })
        db.update("UPDATE attachment SET message_id = ?, agent_token_hash = ?, agent_token_expires_at = ? WHERE id = ?",
            messageId, Accounts.hash(token), Instant.now().plus(AGENT_LINK_DAYS, ChronoUnit.DAYS).toString(), a.id)
        a.id to token
    }

    /** Undo [attach] (the agent refused the message): the files are unsent again. */
    fun detach(items: List<StoredAttachment>) = items.forEach {
        db.update("UPDATE attachment SET message_id = NULL, agent_token_hash = NULL, agent_token_expires_at = NULL WHERE id = ?", it.id)
    }

    fun bytes(a: StoredAttachment): ByteArray = Files.readAllBytes(file(a.id))

    fun stored(id: String): StoredAttachment? = db.query(
        "SELECT id, session_id, owner_user_id, name, content_type, size, message_id FROM attachment WHERE id = ?", id) {
        StoredAttachment(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getLong(6), it.getString(7))
    }.firstOrNull()

    /** The attachment behind an agent's download link, or null (unknown, wrong token, or expired). */
    fun forAgent(id: String, token: String): StoredAttachment? {
        if (!id.matches(Regex("att_[a-z0-9]{1,40}")) || token.length !in 16..64) return null
        val ok = db.query("SELECT 1 FROM attachment WHERE id = ? AND agent_token_hash = ? AND agent_token_expires_at > ?",
            id, Accounts.hash(token), now()) { 1 }.isNotEmpty()
        return if (ok) stored(id) else null
    }

    /** Sent attachments of these messages, by message id. */
    fun ofMessages(messageIds: Collection<String>): Map<String, List<AttachmentOut>> {
        if (messageIds.isEmpty()) return emptyMap()
        val marks = messageIds.joinToString(",") { "?" }
        return db.query("SELECT message_id, id, name, content_type, size FROM attachment WHERE message_id IN ($marks) ORDER BY created_at, id",
            *messageIds.toTypedArray()) { it.getString(1) to AttachmentOut(it.getString(2), it.getString(3), it.getString(4), it.getLong(5)) }
            .groupBy({ it.first }, { it.second })
    }

    /** Remove one upload that isn't sent yet. */
    fun removeUnsent(user: HubUser, id: String) {
        val a = stored(id)?.takeIf { it.ownerUserId == user.userId && it.messageId == null } ?: throw HubException.notFound("attachment")
        delete(listOf(a.id))
    }

    /** Before a conversation's rows go (they cascade), delete its files. */
    fun deleteForSession(sessionId: String) =
        delete(db.query("SELECT id FROM attachment WHERE session_id = ?", sessionId) { it.getString(1) })

    /** Uploads never sent within a day. */
    fun cleanupUnsent() = delete(db.query("SELECT id FROM attachment WHERE message_id IS NULL AND created_at < ?",
        Instant.now().minus(1, ChronoUnit.DAYS).toString()) { it.getString(1) })

    /** Every file (the hub is being deleted; its rows go with the database). */
    fun deleteAllFiles() {
        Files.list(dir).use { files -> files.forEach { runCatching { Files.deleteIfExists(it) } } }
    }

    private fun delete(ids: List<String>) {
        for (id in ids) {
            runCatching { Files.deleteIfExists(file(id)) }.onFailure { log.info("attachment {}: {}", id, it.message) }
            db.update("DELETE FROM attachment WHERE id = ?", id)
        }
    }

    companion object {
        const val MAX_BYTES = 25_000_000
        const val MAX_PER_MESSAGE = 10
        const val AGENT_LINK_DAYS = 7L
        /** Images sent inline to the agent (as image parts), together at most this many bytes; bigger ones go as links only. */
        const val MAX_INLINE_BYTES = 5_000_000L

        /** A file name without folders, control characters or quotes, at most 120 characters. */
        fun cleanName(raw: String?): String {
            val decoded = runCatching { java.net.URLDecoder.decode(raw ?: "", Charsets.UTF_8) }.getOrDefault(raw ?: "")
            val base = decoded.substringAfterLast('/').substringAfterLast('\\')
                .filter { !it.isISOControl() && it != '"' && it != '<' && it != '>' }.trim().trimStart('.')
            return base.take(120).ifBlank { "file" }
        }

        /** A plain `type/subtype` content type, or application/octet-stream. */
        fun cleanType(raw: String?): String {
            val t = raw?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            return if (t.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+"))) t else "application/octet-stream"
        }

        fun sizeLabel(size: Long): String = when {
            size >= 1_000_000 -> "%.1f MB".format(size / 1_000_000.0)
            size >= 1_000 -> "${size / 1_000} KB"
            else -> "$size bytes"
        }
    }
}
