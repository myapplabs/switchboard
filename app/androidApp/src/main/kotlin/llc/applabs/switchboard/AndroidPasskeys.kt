package llc.applabs.switchboard

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import llc.applabs.switchboard.domain.HubException
import llc.applabs.switchboard.domain.PasskeyCancelled
import llc.applabs.switchboard.domain.Passkeys

/**
 * Passkeys through Android Credential Manager (Google Password Manager or another provider). The RP ID
 * is `switchboard.applabs.llc`: Android only allows it once that domain's assetlinks.json lists this
 * app's signing certificate (passkey-domain/ in the repo).
 */
class AndroidPasskeys(context: Context, private val activity: () -> Activity?) : Passkeys {
    private val manager = CredentialManager.create(context)
    private companion object { const val TAG = "SwitchboardPasskeys" }
    override val platform = "android"

    override suspend fun create(optionsJson: String): String {
        val a = activity() ?: throw HubException("passkey_unavailable", 0, "Passkeys unavailable", "Open the app and try again.")
        val started = System.currentTimeMillis()
        return try {
            (manager.createCredential(a, CreatePublicKeyCredentialRequest(requestJson = optionsJson)) as CreatePublicKeyCredentialResponse).registrationResponseJson
        } catch (e: CreateCredentialCancellationException) {
            throw cancelledOrRefused(started, e)
        } catch (e: CreateCredentialException) {
            Log.w(TAG, "create failed: ${e.type}: ${e.errorMessage}")
            throw explain(e.type, e.errorMessage?.toString())
        }
    }

    override suspend fun get(optionsJson: String): String {
        val a = activity() ?: throw HubException("passkey_unavailable", 0, "Passkeys unavailable", "Open the app and try again.")
        val started = System.currentTimeMillis()
        return try {
            val result = manager.getCredential(a, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson = optionsJson))))
            (result.credential as? PublicKeyCredential)?.authenticationResponseJson
                ?: throw HubException("passkey_unavailable", 0, "No passkey", "No Switchboard passkey was chosen.")
        } catch (e: GetCredentialCancellationException) {
            throw cancelledOrRefused(started, e)
        } catch (e: NoCredentialException) {
            throw HubException("passkey_unavailable", 0, "No passkey on this device",
                "This device has no passkey for this hub yet. Join with an invite, or (owner) pair with a setup code.")
        } catch (e: GetCredentialException) {
            Log.w(TAG, "get failed: ${e.type}: ${e.errorMessage}")
            throw explain(e.type, e.errorMessage?.toString())
        }
    }

    /** A "cancel" before anyone could react means no sheet was shown: the system refused (e.g. an unverified domain). */
    private fun cancelledOrRefused(started: Long, e: Exception): Exception {
        val ms = System.currentTimeMillis() - started
        Log.w(TAG, "passkey cancelled after ${ms}ms: ${e.message}")
        return if (ms >= 1_500) PasskeyCancelled() else HubException("passkey_unavailable", 0, "Passkeys not available",
            "No passkey sheet could open. Passkeys need switchboard.applabs.llc to list this app (assetlinks.json), and this device needs a " +
                "Switchboard passkey. Until then, debug builds can sign in with a test token.")
    }

    private fun explain(type: String, message: String?): HubException {
        val domain = message?.contains("RP ID", ignoreCase = true) == true || message?.contains("domain", ignoreCase = true) == true ||
            type.contains("DOM", ignoreCase = true)
        return if (domain) HubException("passkey_unavailable", 0, "Passkeys not set up yet",
            "Passkeys need switchboard.applabs.llc to list this app (assetlinks.json). Until then, debug builds can sign in with a test token.")
        else HubException("passkey_unavailable", 0, "Passkey failed", message ?: type)
    }
}
