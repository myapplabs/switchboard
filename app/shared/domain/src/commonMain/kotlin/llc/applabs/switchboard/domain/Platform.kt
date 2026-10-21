package llc.applabs.switchboard.domain

/**
 * Passkeys, provided by each platform (Android Credential Manager, iOS AuthenticationServices).
 * The hub's WebAuthn options go in as JSON, the credential comes back as JSON (the WebAuthn JSON forms).
 */
interface Passkeys {
    /** Create a passkey from PublicKeyCredentialCreationOptions JSON; returns the registration credential JSON. */
    suspend fun create(optionsJson: String): String
    /** Sign in with a passkey from PublicKeyCredentialRequestOptions JSON; returns the assertion credential JSON. */
    suspend fun get(optionsJson: String): String
    /** `ios` or `android` (shown in the hub's device list). */
    val platform: String
}

/** The person cancelled the passkey sheet (not an error to show). */
class PasskeyCancelled : Exception("Cancelled")

/** The platform's push token (FCM on Android). Null where push isn't available. */
interface PushTokens {
    val platform: String
    suspend fun token(): String?
}

/** Small secrets (the hub address and session tokens) in the Android Keystore / iOS Keychain. */
interface SecureStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}
