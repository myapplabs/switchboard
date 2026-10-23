package llc.applabs.switchboard.ui

import platform.Foundation.NSBundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.ComposeUIViewController
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import llc.applabs.switchboard.data.AppContainer
import llc.applabs.switchboard.domain.HubException
import llc.applabs.switchboard.domain.PasskeyCancelled
import llc.applabs.switchboard.domain.Passkeys
import llc.applabs.switchboard.domain.SecureStore
import platform.UIKit.UIViewController

/**
 * What the Swift side provides (iosApp/iosApp/Platform.swift): passkeys through AuthenticationServices
 * and the Keychain, with callbacks — simpler to implement in Swift than Kotlin suspend functions.
 * `done(json, error)`: exactly one is non-null; error "cancelled" means the person closed the sheet.
 */
interface IosPlatform {
    fun createPasskey(optionsJson: String, done: (String?, String?) -> Unit)
    fun getPasskey(optionsJson: String, done: (String?, String?) -> Unit)
    fun keychainGet(key: String): String?
    fun keychainSet(key: String, value: String)
    fun keychainRemove(key: String)
    val deviceName: String
}

private class IosPasskeys(private val p: IosPlatform) : Passkeys {
    override val platform = "ios"
    override suspend fun create(optionsJson: String) = call { done -> p.createPasskey(optionsJson, done) }
    override suspend fun get(optionsJson: String) = call { done -> p.getPasskey(optionsJson, done) }
    private suspend fun call(start: ((String?, String?) -> Unit) -> Unit): String = suspendCancellableCoroutine { c ->
        start { json, error ->
            when {
                json != null -> c.resume(json)
                error == "cancelled" -> c.resumeWithException(PasskeyCancelled())
                else -> c.resumeWithException(HubException("passkey_unavailable", 0, "Passkey failed", error))
            }
        }
    }
}

private class KeychainStore(private val p: IosPlatform) : SecureStore {
    override fun get(key: String) = p.keychainGet(key)
    override fun set(key: String, value: String) = p.keychainSet(key, value)
    override fun remove(key: String) = p.keychainRemove(key)
}

private val inviteLink = mutableStateOf<String?>(null)

/** Called from Swift when the app is opened with an invite link (universal link). */
fun openInviteLink(url: String) { inviteLink.value = url }

/** Entry point used by iosApp/iosApp/ContentView.swift. */
fun MainViewController(platform: IosPlatform, devSignIn: Boolean): UIViewController {
    val container = AppContainer(IosPasskeys(platform), KeychainStore(platform), devSignIn, platform.deviceName,
        appVersion = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String).orEmpty())
    return ComposeUIViewController { App(deps = container, inviteLink = inviteLink.value) }
}
