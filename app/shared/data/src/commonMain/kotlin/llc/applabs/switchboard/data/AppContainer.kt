package llc.applabs.switchboard.data

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
    store: SecureStore,
    override val devSignIn: Boolean,
    override val deviceName: String,
    engine: HttpClientEngine? = null,
    pushTokens: PushTokens? = null,
) : AppDependencies {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = HubClient(engine, TokenStore(store))
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
}
