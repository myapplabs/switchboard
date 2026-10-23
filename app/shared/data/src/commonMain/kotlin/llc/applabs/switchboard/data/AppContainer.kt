package llc.applabs.switchboard.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import llc.applabs.switchboard.domain.ThemeMode
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import llc.applabs.switchboard.domain.AppDependencies
import llc.applabs.switchboard.domain.Passkeys
import llc.applabs.switchboard.domain.PushTokens
import llc.applabs.switchboard.domain.SecureStore
import llc.applabs.switchboard.domain.AuthState
import kotlinx.coroutines.launch

/**
 * The app's object graph: creates every repository and service once. Built by the platform entry points
 * with that platform's [Passkeys] and [SecureStore], and passed to the UI as [AppDependencies].
 */
class AppContainer(
    passkeys: Passkeys,
    private val store: SecureStore,
    override val devSignIn: Boolean,
    override val deviceName: String,
    engine: HttpClientEngine? = null,
    pushTokens: PushTokens? = null,
    override val scanQr: (suspend () -> String?)? = null,
    override val appVersion: String = "",
    override val pickPhoto: (suspend () -> llc.applabs.switchboard.domain.model.PickedFile?)? = null,
    override val pickFile: (suspend () -> llc.applabs.switchboard.domain.model.PickedFile?)? = null,
    override val openFile: ((name: String, contentType: String, bytes: ByteArray) -> Unit)? = null,
) : AppDependencies {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = HubClient(engine, TokenStore(store))
    private val _themeMode = MutableStateFlow(
        runCatching { store.get(THEME_KEY)?.let { ThemeMode.valueOf(it) } }.getOrNull() ?: ThemeMode.SYSTEM)
    override val themeMode: StateFlow<ThemeMode> = _themeMode
    override fun setThemeMode(mode: ThemeMode) { store.set(THEME_KEY, mode.name); _themeMode.value = mode }
    private val authImpl = AuthRepositoryImpl(client, passkeys)
    private val liveImpl = LiveEventsImpl(client, authImpl.state, scope).also { it.onRevoked = authImpl::revoked }

    override val auth = authImpl
    override val live = liveImpl
    override val agents = AgentsRepositoryImpl(client, liveImpl, scope)
    override val chat = ChatRepositoryImpl(client)
    override val members = MembersRepositoryImpl(client)
    override val control = ControlRepositoryImpl(client)
    override val push = PushRepositoryImpl(client, pushTokens)
    override val tasks = TasksRepositoryImpl(client, liveImpl, scope)
    override val forge = ForgeRepositoryImpl(client)

    init {
        // Register for push whenever someone signs in (best effort).
        scope.launch { authImpl.state.collect { if (it is AuthState.SignedIn) runCatching { push.register() } } }
    }

    private companion object { const val THEME_KEY = "theme_mode" }
}
