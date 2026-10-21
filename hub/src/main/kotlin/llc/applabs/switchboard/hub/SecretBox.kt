package llc.applabs.switchboard.hub

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts agent API keys at rest: AES-256-GCM with a random 12-byte IV per value. The 32-byte master
 * key lives in `<data dir>/master.key` (created on first run, owner-read-only). Back it up with the
 * database — without it the stored agent keys cannot be read.
 *
 * Stored form: `v1:` + base64(iv ‖ ciphertext+tag).
 */
class SecretBox(private val key: ByteArray) {
    init { require(key.size == 32) { "master key must be 32 bytes" } }

    fun seal(plain: String): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return "v1:" + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    fun open(sealed: String): String {
        require(sealed.startsWith("v1:")) { "unknown secret format" }
        val raw = Base64.getDecoder().decode(sealed.removePrefix("v1:"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        return String(cipher.doFinal(raw, 12, raw.size - 12))
    }

    companion object {
        private val random = SecureRandom()

        fun fromDataDir(dataDir: Path): SecretBox {
            val file = dataDir.resolve("master.key")
            if (!Files.exists(file)) {
                Files.createDirectories(dataDir)
                val key = ByteArray(32).also { random.nextBytes(it) }
                Files.write(file, Base64.getEncoder().encode(key))
                runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
            }
            return SecretBox(Base64.getDecoder().decode(Files.readString(file).trim()))
        }

        fun random(): SecretBox = SecretBox(ByteArray(32).also { random.nextBytes(it) })
    }
}
