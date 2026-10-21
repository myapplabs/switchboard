package llc.applabs.switchboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import llc.applabs.switchboard.domain.AuthState
import llc.applabs.switchboard.ui.App

class MainActivity : ComponentActivity() {
    /** An invite link the app was opened with (or re-opened with: launchMode singleTask). */
    private val inviteLink = mutableStateOf<String?>(null)
    /** The owner's pairing link (the hub machine's QR code, opened with the phone's camera). */
    private val pairLink = mutableStateOf<String?>(null)
    /** A conversation a notification asked to open. */
    private val openSession = mutableStateOf<String?>(null)
    /** A card a notification asked to open. */
    private val openTask = mutableStateOf<String?>(null)
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        val container = (application as SwitchboardApp).container
        setContent {
            App(deps = container, inviteLink = inviteLink.value, openSession = openSession.value, onSessionOpened = { openSession.value = null },
                openTask = openTask.value, onTaskOpened = { openTask.value = null }, pairLink = pairLink.value)
        }
        // Ask for notifications once someone is signed in (Android 13+).
        lifecycleScope.launch {
            container.auth.state.collect { s ->
                if (s is AuthState.SignedIn && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.dataString?.takeIf { intent.action == Intent.ACTION_VIEW && it.contains("/join") }?.let { inviteLink.value = it }
        intent.dataString?.takeIf { intent.action == Intent.ACTION_VIEW && it.contains("/pair") }?.let { pairLink.value = it }
        intent.getStringExtra(Notifications.EXTRA_SESSION)?.let { openSession.value = it }
        intent.getStringExtra(Notifications.EXTRA_TASK)?.let { openTask.value = it }
    }
}
