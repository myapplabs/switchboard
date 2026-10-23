package llc.applabs.switchboard.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import llc.applabs.design.AppLabsTheme
import llc.applabs.switchboard.domain.AppDependencies
import llc.applabs.switchboard.domain.AuthState
import llc.applabs.switchboard.domain.model.Me
import llc.applabs.switchboard.ui.agents.AddAgentScreen
import llc.applabs.switchboard.ui.agents.AddAgentViewModel
import llc.applabs.switchboard.ui.agents.AgentsScreen
import llc.applabs.switchboard.ui.agents.AgentsViewModel
import llc.applabs.switchboard.ui.chat.ChatScreen
import llc.applabs.switchboard.ui.chat.ChatViewModel
import llc.applabs.switchboard.ui.common.Loading
import llc.applabs.switchboard.ui.connect.ConnectScreen
import llc.applabs.switchboard.ui.connect.ConnectViewModel
import llc.applabs.switchboard.ui.members.ActivityScreen
import llc.applabs.switchboard.ui.members.ActivityViewModel
import llc.applabs.switchboard.ui.members.AgentAccessScreen
import llc.applabs.switchboard.ui.members.AgentAccessViewModel
import llc.applabs.switchboard.ui.members.MembersScreen
import llc.applabs.switchboard.ui.members.MembersViewModel
import llc.applabs.switchboard.ui.sessions.SessionsScreen
import llc.applabs.switchboard.ui.sessions.SessionsViewModel
import llc.applabs.switchboard.ui.settings.SettingsScreen
import llc.applabs.switchboard.ui.settings.SettingsViewModel

/** Navigation destinations. */
object Routes {
    const val AGENTS = "agents"
    const val SESSIONS = "sessions"
    const val JOBS = "jobs"
    const val MEMBERS = "members"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{sessionId}"
    const val ADD_AGENT = "add-agent"
    const val ACCESS = "access"
    const val ACTIVITY = "activity"
    const val ASK_ALL = "ask-all"
    const val BOARD = "board"
    const val TASK = "task/{taskId}"
    const val NEW_TASK = "new-task"
    const val SPECIALTIES = "specialties"
    const val FORGE_TICKET = "forge-ticket/{cardId}"
    const val FORGE_LINKS = "forge"
    fun forgeTicket(id: String) = "forge-ticket/$id"
    fun chat(id: String) = "chat/$id"
    fun task(id: String) = "task/$id"
}

/**
 * The whole app UI, shared by Android and iOS. Signed out: onboarding. Signed in: the tabs (Agents,
 * Conversations, Members for owner and admins, Settings) with Chat and the editors on top.
 * [inviteLink] is an invite link the platform opened the app with.
 */
@Composable
fun App(deps: AppDependencies, inviteLink: String? = null, openSession: String? = null, onSessionOpened: () -> Unit = {},
        openTask: String? = null, onTaskOpened: () -> Unit = {}, pairLink: String? = null) {
    val themeMode by deps.themeMode.collectAsState()
    AppLabsTheme(useDarkTheme = when (themeMode) {
        llc.applabs.switchboard.domain.ThemeMode.LIGHT -> false
        llc.applabs.switchboard.domain.ThemeMode.DARK -> true
        llc.applabs.switchboard.domain.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }) {
        val auth by deps.auth.state.collectAsState()
        LaunchedEffect(Unit) { deps.auth.restore() }
        when (val a = auth) {
            AuthState.Loading -> Loading()
            is AuthState.SignedOut -> {
                val vm = viewModel(key = "connect") { ConnectViewModel(deps.auth, deps.deviceName, a.hubUrl) }
                LaunchedEffect(inviteLink) { inviteLink?.let { vm.onEvent(llc.applabs.switchboard.ui.connect.ConnectUiEvent.InviteLink(it)) } }
                LaunchedEffect(pairLink) { pairLink?.let { vm.onEvent(llc.applabs.switchboard.ui.connect.ConnectUiEvent.PairLink(it)) } }
                ConnectScreen(vm, deps.devSignIn, deps.scanQr)
            }
            is AuthState.SignedIn -> Main(deps, a.me, a.hubUrl, openSession, onSessionOpened, openTask, onTaskOpened)
        }
    }
}

@Composable
private fun Main(deps: AppDependencies, me: Me, hubUrl: String, openSession: String?, onSessionOpened: () -> Unit,
                 openTask: String?, onTaskOpened: () -> Unit) {
    val nav = rememberNavController()
    val themeMode by deps.themeMode.collectAsState()
    // A tapped notification: open its conversation, or its card.
    LaunchedEffect(openSession) { openSession?.let { nav.navigate(Routes.chat(it)); onSessionOpened() } }
    LaunchedEffect(openTask) { openTask?.let { nav.navigate(Routes.task(it)); onTaskOpened() } }
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    // Five tabs at most on phones (spec M5): Members and Settings live under More.
    val tabs = listOfNotNull(
        Routes.AGENTS to "Agents", Routes.SESSIONS to "Chats", Routes.BOARD to "Board",
        // Restricted members get no jobs tab (spec: role-aware UI).
        (Routes.JOBS to "Jobs").takeIf { me.role != llc.applabs.switchboard.domain.model.Roles.RESTRICTED },
        Routes.SETTINGS to "More",
    )
    Scaffold(bottomBar = {
        if (route in tabs.map { it.first }) TabBar(tabs, route) { r -> nav.navigate(r) { popUpTo(Routes.AGENTS); launchSingleTop = true } }
    }) { pad ->
        // Screens get the space above the tab bar (or the system bar), already accounted for: their own Scaffolds
        // and the chat's keyboard padding don't add it again.
        NavHost(nav, startDestination = Routes.AGENTS, modifier = Modifier.padding(bottom = pad.calculateBottomPadding())
            .consumeWindowInsets(PaddingValues(bottom = pad.calculateBottomPadding()))) {
            composable(Routes.AGENTS) {
                val vm = viewModel { AgentsViewModel(deps.agents, deps.chat, deps.live, deps.control) }
                AgentsScreen(vm, me.hub.name, me.permissions.manageAgents, me.permissions.manageMembers, onOpenChat = { nav.navigate(Routes.chat(it)) },
                    onAddAgent = { nav.navigate(Routes.ADD_AGENT) }, onAskAll = { nav.navigate(Routes.ASK_ALL) })
            }
            composable(Routes.SESSIONS) {
                val vm = viewModel { SessionsViewModel(deps.chat, deps.live) }
                SessionsScreen(vm, deps.agents, me.userId, onOpen = { nav.navigate(Routes.chat(it)) })
            }
            composable(Routes.JOBS) {
                val owner = me.role == llc.applabs.switchboard.domain.model.Roles.OWNER
                val vm = viewModel { llc.applabs.switchboard.ui.jobs.JobsViewModel(deps.agents, deps.control, deps.live, canSeeRelay = owner) }
                llc.applabs.switchboard.ui.jobs.JobsScreen(vm, isOwner = owner, onOpenChat = { nav.navigate(Routes.chat(it)) })
            }
            composable(Routes.MEMBERS) {
                val vm = viewModel { MembersViewModel(deps.members, deps.live) }
                MembersScreen(vm, me, onAgentAccess = { nav.navigate(Routes.ACCESS) }, onActivity = { nav.navigate(Routes.ACTIVITY) }, onBack = { nav.back() })
            }
            composable(Routes.SETTINGS) {
                val vm = viewModel { SettingsViewModel(deps.auth, deps.members, deps.push) }
                SettingsScreen(vm, me, hubUrl, onMembers = { nav.navigate(Routes.MEMBERS) }.takeIf { me.permissions.manageMembers },
                    onSpecialties = { nav.navigate(Routes.SPECIALTIES) }.takeIf { me.permissions.manageMembers },
                    onForge = { nav.navigate(Routes.FORGE_LINKS) }.takeIf { me.role != llc.applabs.switchboard.domain.model.Roles.RESTRICTED },
                    appVersion = deps.appVersion, themeMode = themeMode, onThemeMode = deps::setThemeMode)
            }
            composable(Routes.BOARD) {
                val vm = viewModel { llc.applabs.switchboard.ui.board.BoardViewModel(deps.tasks, deps.agents,
                    deps.forge.takeIf { me.role != llc.applabs.switchboard.domain.model.Roles.RESTRICTED }) }
                llc.applabs.switchboard.ui.board.BoardScreen(vm, me.userId, onOpen = { nav.navigate(Routes.task(it)) }, onNew = { nav.navigate(Routes.NEW_TASK) },
                    onOpenForge = { nav.navigate(Routes.forgeTicket(it)) })
            }
            composable(Routes.FORGE_TICKET) { entry ->
                val id = entry.savedStateHandle.get<String>("cardId") ?: return@composable
                val vm = viewModel(key = "forge-$id") { llc.applabs.switchboard.ui.board.ForgeTicketViewModel(id, deps.forge) }
                llc.applabs.switchboard.ui.board.ForgeTicketScreen(vm, onBack = { nav.back() })
            }
            composable(Routes.FORGE_LINKS) {
                val vm = viewModel { llc.applabs.switchboard.ui.board.ForgeLinksViewModel(deps.forge) }
                llc.applabs.switchboard.ui.board.ForgeLinksScreen(vm, hubUrl, onBack = { nav.back() })
            }
            composable(Routes.TASK) { entry ->
                val id = entry.savedStateHandle.get<String>("taskId") ?: return@composable
                val vm = viewModel(key = "task-$id") { llc.applabs.switchboard.ui.board.TaskViewModel(id, deps.tasks, deps.agents) }
                llc.applabs.switchboard.ui.board.TaskScreen(vm, onOpenChat = { nav.navigate(Routes.chat(it)) }, onOpenTask = { nav.navigate(Routes.task(it)) },
                    onBack = { nav.back() })
            }
            composable(Routes.NEW_TASK) {
                val vm = viewModel { llc.applabs.switchboard.ui.board.NewTaskViewModel(deps.tasks, deps.agents, maxSteps = 6) }
                llc.applabs.switchboard.ui.board.NewTaskScreen(vm, allowSteps = me.role != llc.applabs.switchboard.domain.model.Roles.RESTRICTED,
                    onCreated = { id -> nav.popBackStack(); nav.navigate(Routes.task(id)) }, onBack = { nav.back() })
            }
            composable(Routes.SPECIALTIES) {
                val vm = viewModel { llc.applabs.switchboard.ui.board.SpecialtiesViewModel(deps.agents) }
                llc.applabs.switchboard.ui.board.SpecialtiesScreen(vm, isOwner = me.role == llc.applabs.switchboard.domain.model.Roles.OWNER, onBack = { nav.back() })
            }
            composable(Routes.CHAT) { entry ->
                val id = entry.savedStateHandle.get<String>("sessionId") ?: return@composable
                val vm = viewModel(key = "chat-$id") { ChatViewModel(id, me.userId, deps.chat, deps.agents, deps.live, deps.control) }
                ChatScreen(vm, me.userId, onBack = { nav.back() }, pickPhoto = deps.pickPhoto, pickFile = deps.pickFile, openFile = deps.openFile)
            }
            composable(Routes.ADD_AGENT) {
                val vm = viewModel { AddAgentViewModel(deps.agents) }
                AddAgentScreen(vm, onDone = { nav.back() })
            }
            composable(Routes.ACCESS) {
                val vm = viewModel { AgentAccessViewModel(deps.agents) }
                AgentAccessScreen(vm, onBack = { nav.back() })
            }
            composable(Routes.ASK_ALL) {
                val vm = viewModel { llc.applabs.switchboard.ui.askall.AskAllViewModel(deps.agents, deps.chat, deps.control) }
                llc.applabs.switchboard.ui.askall.AskAllScreen(vm, onOpenChat = { nav.navigate(Routes.chat(it)) }, onBack = { nav.back() })
            }
            composable(Routes.ACTIVITY) {
                val vm = viewModel { ActivityViewModel(deps.members, deps.agents) }
                ActivityScreen(vm, onBack = { nav.back() })
            }
        }
    }
}

/** The bottom tabs: words only, each an equal share of the width, the current one highlighted. */
@Composable
private fun TabBar(tabs: List<Pair<String, String>>, route: String?, onSelect: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp).selectableGroup().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { (r, label) ->
                val selected = route == r
                Box(Modifier.weight(1f).fillMaxHeight().selectable(selected, role = Role.Tab, onClick = { onSelect(r) }).testTag("tab-$r"),
                    contentAlignment = Alignment.Center) {
                    Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }
}

private fun NavHostController.back() { if (!popBackStack()) navigate(Routes.AGENTS) }
