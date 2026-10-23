package llc.applabs.switchboard.domain

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
    /** This app's version, as people see it (e.g. "0.3.4"); empty where unknown. */
    val appVersion: String get() = ""
}
