package llc.applabs.switchboard.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import llc.applabs.switchboard.domain.model.PickedFile

/**
 * Everything the UI needs from the data layer, as domain interfaces.
 *
 * `:shared:ui` depends on this interface only. The app's entry points (Android `SwitchboardApp`, iOS
 * `MainViewController`) create the concrete `AppContainer` from `:shared:data` and pass it in — manual
 * dependency injection, no DI framework.
 */
interface AppDependencies {
    val auth: AuthRepository
    val agents: AgentsRepository
    val chat: ChatRepository
    val members: MembersRepository
    val control: ControlRepository
    val push: PushRepository
    val tasks: TasksRepository
    val forge: ForgeRepository
    val live: LiveEvents
    /** Debug builds: the test-token sign-in is offered (passkeys need the published domain). */
    val devSignIn: Boolean
    /** A name for this device in the hub's device list (e.g. "Pixel 9"). */
    val deviceName: String
    /** Scans a QR code with the camera and returns its text (null: cancelled); null where scanning isn't available. */
    val scanQr: (suspend () -> String?)? get() = null
    /** Picks a photo (made small enough to send); null result: cancelled. Null where picking isn't available. */
    val pickPhoto: (suspend () -> PickedFile?)? get() = null
    /** Picks any file; null result: cancelled. Null where picking isn't available. */
    val pickFile: (suspend () -> PickedFile?)? get() = null
    /** Opens a downloaded file in another app (a viewer); null where that isn't available. */
    val openFile: ((name: String, contentType: String, bytes: ByteArray) -> Unit)? get() = null
    /** Light, dark, or the phone's setting: the person's choice, kept on this device. */
    val themeMode: StateFlow<ThemeMode> get() = MutableStateFlow(ThemeMode.SYSTEM)
    fun setThemeMode(mode: ThemeMode) {}
    /** This app's version, as people see it (e.g. "0.3.4"); empty where unknown. */
    val appVersion: String get() = ""
}

/** The app's look: the phone's setting, or always light or dark. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }
