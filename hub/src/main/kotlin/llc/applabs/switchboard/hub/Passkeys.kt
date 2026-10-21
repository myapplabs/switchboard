package llc.applabs.switchboard.hub

import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.PublicKeyCredentialParameters
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import io.ktor.http.HttpStatusCode
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import com.yubico.webauthn.data.ByteArray as Bytes

/**
 * Passkey (WebAuthn) sign-up and sign-in (contract: the /auth/passkeys endpoints).
 *
 * Passkeys are discoverable (sign-in needs no user name) and always require user verification
 * (Face ID, fingerprint, device PIN). Attestation is not requested. A ceremony (options → verify)
 * lives in memory for 5 minutes; a hub restart in between just means "try again".
 *
 * Two ways in: the owner pairs with a setup code (first run, or another device); a member
 * joins with an invite code (F7), which sets their role. Each is checked at the options step and used up
 * at verify, so two phones racing for one code can't both succeed.
 */
class Passkeys(
    private val db: Db,
    private val accounts: Accounts,
    private val pairing: Pairing,
    private val members: Members,
    config: HubConfig,
) {
    private val log = LoggerFactory.getLogger(Passkeys::class.java)

    private val rp: RelyingParty = RelyingParty.builder()
        .identity(RelyingPartyIdentity.builder().id(config.effectiveRpId).name(config.hubName).build())
        .credentialRepository(Repository())
        .origins(config.effectiveOrigins)
        // What phones' platform authenticators use; the library's default list also offers post-quantum
        // ML-DSA, which this JVM cannot verify (registrations with it would fail).
        .preferredPubkeyParams(listOf(PublicKeyCredentialParameters.ES256, PublicKeyCredentialParameters.EdDSA, PublicKeyCredentialParameters.RS256))
        .build()

    private sealed interface Ceremony { val createdAt: Long }
    private data class Registration(
        val options: PublicKeyCredentialCreationOptions, val setupCode: String?, val invite: InviteRow?, val displayName: String,
        val deviceName: String?, val handle: Bytes, override val createdAt: Long = System.currentTimeMillis(),
    ) : Ceremony
    private data class Login(val request: AssertionRequest, override val createdAt: Long = System.currentTimeMillis()) : Ceremony

    private val ceremonies = ConcurrentHashMap<String, Ceremony>()

    // ── registration ─────────────────────────────────────────────────────────

    fun registerOptions(input: RegisterOptionsIn): PasskeyCeremony {
        val name = input.displayName.trim()
        if (name.isEmpty() || name.length > 60) throw HubException.invalid("display_name: 1–60 characters")
        if (input.deviceName != null && input.deviceName.length > 60) throw HubException.invalid("device_name: at most 60 characters")
        if ((input.setupCode == null) == (input.inviteCode == null)) throw HubException.invalid("Send exactly one of setup_code or invite_code.")
        val invite = input.inviteCode?.let { members.check(it) }
        val owner = if (invite == null) accounts.owner(pairing.check(input.setupCode!!)) else null
        // The owner adding a device keeps their passkey identity; a new person gets a fresh one.
        val handle = owner?.let { handleOf(it.userId) } ?: Bytes(accounts.randomToken(32).let { java.util.Base64.getUrlDecoder().decode(it) })
        val displayName = owner?.displayName ?: name
        val options = rp.startRegistration(StartRegistrationOptions.builder()
            .user(UserIdentity.builder().name(displayName).displayName(displayName).id(handle).build())
            .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                .residentKey(ResidentKeyRequirement.REQUIRED)
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build())
            .timeout(TIMEOUT_MS)
            .build())
        val id = put(Registration(options, input.setupCode, invite, displayName, input.deviceName?.trim(), handle))
        return PasskeyCeremony(id, publicKey(options.toCredentialsCreateJson()))
    }

    fun registerVerify(input: PasskeyResult): Session {
        val c = take(input.ceremonyId) as? Registration ?: throw ceremonyGone()
        val result = try {
            rp.finishRegistration(FinishRegistrationOptions.builder()
                .request(c.options)
                .response(PublicKeyCredential.parseRegistrationResponseJson(input.credential.toString()))
                .build())
        } catch (e: Exception) {
            log.info("passkey registration failed: {}", e.message)
            throw HubException.invalid("The passkey could not be verified (${e.message?.take(120)}).")
        }
        val user: HubUser
        val hubId: String
        if (c.invite != null) {
            hubId = c.invite.hubId
            user = accounts.createUser(hubId, c.displayName, c.invite.role)
            if (!members.consume(c.invite.id, user.userId)) {
                db.update("DELETE FROM user WHERE id = ?", user.userId)
                throw HubException(HttpStatusCode.Gone, "invite_used", "Invite already used", "Someone just used this invite. Ask for a new one.")
            }
        } else {
            hubId = pairing.check(c.setupCode!!)
            if (!pairing.consume(c.setupCode)) throw HubException(HttpStatusCode.Gone, "setup_code_invalid", "Setup code not accepted",
                "That setup code was just used by another device. Run `switchboard-hub setup-code` for a new one.")
            user = accounts.owner(hubId) ?: accounts.createUser(hubId, c.displayName, Role.owner)
        }
        val deviceName = input.deviceName?.trim()?.take(60) ?: c.deviceName
        val credentialId = result.keyId.id.base64Url
        db.tx { conn ->
            conn.prepareStatement("UPDATE user SET webauthn_handle = ? WHERE id = ? AND webauthn_handle IS NULL").use {
                Db.bind(it, arrayOf<Any?>(c.handle.base64Url, user.userId)); it.executeUpdate()
            }
            conn.prepareStatement("""INSERT INTO credential (id, hub_id, user_id, public_key_cose, sign_count, device_name, created_at, last_used_at)
                                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)""").use {
                Db.bind(it, arrayOf<Any?>(credentialId, hubId, user.userId, result.publicKeyCose.base64Url, result.signatureCount,
                    deviceName, now(), now())); it.executeUpdate()
            }
        }
        accounts.activity(hubId, user.userId, null, "passkey_added", """{"credential":"${credentialId.take(12)}"}""")
        c.invite?.let { members.joined(user, it.id) }
        return accounts.signIn(user, deviceName, credentialId, input.platform)
    }

    // ── sign-in ─────────────────────────────────────────────────────────────

    fun loginOptions(): PasskeyCeremony {
        val request = rp.startAssertion(StartAssertionOptions.builder()
            .userVerification(UserVerificationRequirement.REQUIRED)
            .timeout(TIMEOUT_MS)
            .build())
        val id = put(Login(request))
        return PasskeyCeremony(id, publicKey(request.toCredentialsGetJson()))
    }

    fun loginVerify(input: PasskeyResult): Session {
        val c = take(input.ceremonyId) as? Login ?: throw ceremonyGone()
        val result = try {
            rp.finishAssertion(FinishAssertionOptions.builder()
                .request(c.request)
                .response(PublicKeyCredential.parseAssertionResponseJson(input.credential.toString()))
                .build())
        } catch (e: Exception) {
            log.info("passkey sign-in failed: {}", e.message)
            throw notRecognised()
        }
        if (!result.isSuccess) throw notRecognised()
        val user = accounts.user(result.username) ?: throw notRecognised()
        val credentialId = result.credential.credentialId.base64Url
        db.update("UPDATE credential SET sign_count = ?, last_used_at = ? WHERE id = ?", result.signatureCount, now(), credentialId)
        val deviceName = input.deviceName?.trim()?.take(60)
            ?: db.query("SELECT device_name FROM credential WHERE id = ?", credentialId) { it.str("device_name") }.firstOrNull()
        return accounts.signIn(user, deviceName, credentialId, input.platform)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun put(c: Ceremony): String {
        val cutoff = System.currentTimeMillis() - TIMEOUT_MS
        ceremonies.entries.removeIf { it.value.createdAt < cutoff }
        if (ceremonies.size >= MAX_CEREMONIES) throw HubException(HttpStatusCode.TooManyRequests, "rate_limited", "Too many sign-ins in progress",
            "Try again in a minute.")
        return accounts.randomToken(18).also { ceremonies[it] = c }
    }

    private fun take(id: String): Ceremony? =
        ceremonies.remove(id)?.takeIf { it.createdAt >= System.currentTimeMillis() - TIMEOUT_MS }

    private fun handleOf(userId: String): Bytes? =
        db.query("SELECT webauthn_handle FROM user WHERE id = ?", userId) { it.str("webauthn_handle") }.firstOrNull()?.let(Bytes::fromBase64Url)

    /** The options object itself (`publicKey` member of the browser form) — what Credential Manager / ASAuthorization take. */
    private fun publicKey(json: String): JsonObject = hubJson.parseToJsonElement(json).jsonObject["publicKey"]!!.jsonObject

    private fun ceremonyGone() = HubException.invalid("This sign-in attempt expired. Start again.")
    private fun notRecognised() = HubException(HttpStatusCode.Unauthorized, "unauthorized", "Passkey not recognised",
        "That passkey is not registered with this hub, or it could not be verified.")

    private inner class Repository : CredentialRepository {
        // Registration's exclude list: not needed (one passkey per device is fine; the device decides).
        override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> = emptySet()
        override fun getUserHandleForUsername(username: String): Optional<Bytes> = Optional.ofNullable(handleOf(username))
        override fun getUsernameForUserHandle(userHandle: Bytes): Optional<String> = Optional.ofNullable(
            db.query("SELECT id FROM user WHERE webauthn_handle = ? AND disabled_at IS NULL", userHandle.base64Url) { it.getString(1) }.firstOrNull())
        override fun lookup(credentialId: Bytes, userHandle: Bytes): Optional<RegisteredCredential> =
            Optional.ofNullable(credentials("c.id = ? AND u.webauthn_handle = ?", credentialId.base64Url, userHandle.base64Url).firstOrNull())
        override fun lookupAll(credentialId: Bytes): Set<RegisteredCredential> = credentials("c.id = ?", credentialId.base64Url).toSet()

        private fun credentials(where: String, vararg args: Any?) = db.query(
            "SELECT c.id, c.public_key_cose, c.sign_count, u.webauthn_handle FROM credential c JOIN user u ON u.id = c.user_id WHERE $where", *args,
        ) { rs ->
            RegisteredCredential.builder()
                .credentialId(Bytes.fromBase64Url(rs.getString("id")))
                .userHandle(Bytes.fromBase64Url(rs.getString("webauthn_handle")))
                .publicKeyCose(Bytes.fromBase64Url(rs.getString("public_key_cose")))
                .signatureCount(rs.getLong("sign_count"))
                .build()
        }
    }

    companion object {
        const val TIMEOUT_MS = 5 * 60_000L
        const val MAX_CEREMONIES = 500
    }
}
