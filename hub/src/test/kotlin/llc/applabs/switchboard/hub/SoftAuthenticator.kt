package llc.applabs.switchboard.hub

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A software passkey (a phone's platform authenticator, faked): ES256 keys, "none" attestation,
 * user verification. It answers the hub's options exactly as a browser / Credential Manager would, so
 * the tests exercise the real WebAuthn verification.
 */
class SoftAuthenticator(private val rpId: String = "hub.test", private val origin: String = "https://hub.test") {
    private class Passkey(val id: ByteArray, val keys: KeyPair, val userHandle: ByteArray, var counter: Int = 0)
    private val passkeys = mutableListOf<Passkey>()

    /** Answer creation options → registration credential JSON. */
    fun create(options: JsonObject, originOverride: String = origin): JsonObject {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val userHandle = b64d(options["user"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        passkeys += Passkey(id, keys, userHandle)
        val clientData = clientData("webauthn.create", options["challenge"]!!.jsonPrimitive.content, originOverride)
        val pub = keys.public as ECPublicKey
        val cose = Cbor.map(1L to Cbor.int(2), 3L to Cbor.int(-7), -1L to Cbor.int(1),
            -2L to Cbor.bytes(unsigned32(pub.w.affineX)), -3L to Cbor.bytes(unsigned32(pub.w.affineY)))
        val authData = rpIdHash() + byteArrayOf(0x45) + int32(0) + ByteArray(16) +
            byteArrayOf((id.size shr 8).toByte(), id.size.toByte()) + id + cose
        val attestation = Cbor.textMap("fmt" to Cbor.text("none"), "attStmt" to Cbor.textMap(), "authData" to Cbor.bytes(authData))
        return buildJsonObject {
            put("type", "public-key"); put("id", b64(id)); put("rawId", b64(id))
            putJsonObject("response") {
                put("clientDataJSON", b64(clientData)); put("attestationObject", b64(attestation))
            }
            putJsonObject("clientExtensionResults") {}
        }
    }

    /** Answer request options with the newest passkey (or [which]) → assertion credential JSON. */
    fun get(options: JsonObject, which: Int = passkeys.lastIndex): JsonObject {
        val p = passkeys[which]
        p.counter += 1
        val clientData = clientData("webauthn.get", options["challenge"]!!.jsonPrimitive.content, origin)
        val authData = rpIdHash() + byteArrayOf(0x05) + int32(p.counter)
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(p.keys.private); update(authData + sha256(clientData)); sign()
        }
        return buildJsonObject {
            put("type", "public-key"); put("id", b64(p.id)); put("rawId", b64(p.id))
            putJsonObject("response") {
                put("clientDataJSON", b64(clientData)); put("authenticatorData", b64(authData))
                put("signature", b64(sig)); put("userHandle", b64(p.userHandle))
            }
            putJsonObject("clientExtensionResults") {}
        }
    }

    private fun clientData(type: String, challenge: String, origin: String) =
        """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}""".toByteArray()

    private fun rpIdHash() = sha256(rpId.toByteArray())

    companion object {
        fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        fun b64d(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
        fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
        private fun int32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()
        private fun unsigned32(v: BigInteger): ByteArray {
            val raw = v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - raw.size) + raw
        }
    }
}

/** Just enough CBOR (RFC 8949) for an attestation object and a COSE key. */
private object Cbor {
    private fun head(major: Int, value: Long): ByteArray {
        val m = major shl 5
        return when {
            value < 24 -> byteArrayOf((m or value.toInt()).toByte())
            value < 256 -> byteArrayOf((m or 24).toByte(), value.toByte())
            value < 65536 -> byteArrayOf((m or 25).toByte(), (value shr 8).toByte(), value.toByte())
            else -> byteArrayOf((m or 26).toByte()) + ByteBuffer.allocate(4).putInt(value.toInt()).array()
        }
    }
    fun int(v: Long): ByteArray = if (v >= 0) head(0, v) else head(1, -1 - v)
    fun int(v: Int) = int(v.toLong())
    fun bytes(b: ByteArray) = head(2, b.size.toLong()) + b
    fun text(s: String) = s.toByteArray().let { head(3, it.size.toLong()) + it }
    fun map(vararg entries: Pair<Long, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        write(head(5, entries.size.toLong())); entries.forEach { (k, v) -> write(int(k)); write(v) }
    }.toByteArray()
    fun textMap(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        write(head(5, entries.size.toLong())); entries.forEach { (k, v) -> write(text(k)); write(v) }
    }.toByteArray()
}
