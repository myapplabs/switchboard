package llc.applabs.switchboard.hub

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.SerializationException
import llc.applabs.switchboard.hub.adapters.AgentAdapter
import llc.applabs.switchboard.hub.adapters.HermesAdapter
import org.slf4j.LoggerFactory

/** Everything the hub is made of, wired once (tests build it with fakes). */
class Hub(
    val config: HubConfig,
    val db: Db,
    val secrets: SecretBox,
    adapters: Map<String, AgentAdapter>,
    scope: CoroutineScope,
    healthIntervalMs: Long = config.healthIntervalSeconds * 1000,
    backoffMs: List<Long> = listOf(2_000, 5_000, 15_000, 60_000),
    runRetryDelaysMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000, 30_000, 30_000),
    relayHttp: io.ktor.client.HttpClient = HermesAdapter.client(),
) {
    val accounts = Accounts(db)
    val events = UserEvents()
    val agents = Agents(db, secrets, accounts, events, adapters)
    val poller = HealthPoller(agents, accounts, events, adapters, scope, healthIntervalMs, backoffMs = backoffMs)
    val permissions = Permissions(db, agents, accounts)
    val conversations = Conversations(db, agents, events, accounts)
    val approvals = Approvals(db, accounts)
    val notifier = Notifier(db, config.pushRelayUrl, relayHttp, scope)
    val runs = Runs(db, conversations, agents, events, permissions, accounts, approvals, scope, retryDelaysMs = runRetryDelaysMs).also { it.notifier = notifier }
    val members = Members(db, accounts, events, config)
    val jobs = Jobs(db, agents, permissions, accounts, events).also { it.notifier = notifier; it.conversations = conversations; it.runs = runs }
    val quickCommands = QuickCommands(db, agents)
    val pairing = Pairing(db, accounts)
    val passkeys = Passkeys(db, accounts, pairing, members, config)
    val tasks = Tasks(db, accounts, agents, permissions, conversations, runs, events, scope).also { it.notifier = notifier }
    val forge = ForgeBridges(db, accounts)
    val relay = Relay(db, secrets, agents, conversations, accounts).also { it.notifier = notifier; jobs.relay = it }

    init {
        accounts.ensureHub(config.hubName)
        agents.onChanged = poller::watch
        agents.permissions = permissions
        conversations.permissions = permissions
        members.onMemberRemoved = { conversations.deleteAllOwnedBy(it) }
        members.notifier = notifier
    }

    /** Ask all (spec F15): one prompt to several agents, a new conversation each; agents that can't start say why. */
    suspend fun askAll(user: HubUser, input: AskAllIn): Items<AskAllItem> {
        val ids = input.agentIds.distinct()
        if (ids.size !in 2..8) throw HubException.invalid("agent_ids: 2–8 agents")
        val text = input.text.trim()
        if (text.isEmpty() || text.length > 100_000) throw HubException.invalid("text: 1–100000 characters")
        val title = "Ask all: " + text.lineSequence().first().take(60)
        return Items(kotlinx.coroutines.coroutineScope {
            ids.map { agentId ->
                async {
                    try {
                        val session = conversations.create(user, SessionCreate(agentId, title))
                        try {
                            AskAllItem(agentId, session.id, runs.start(user, session.id, RunInputIn(text)).id)
                        } catch (e: HubException) {
                            conversations.delete(user, session.id)   // don't leave an empty conversation behind
                            AskAllItem(agentId, problem = e.problem())
                        }
                    } catch (e: HubException) {
                        AskAllItem(agentId, problem = e.problem())
                    }
                }
            }.awaitAll()
        })
    }

    /** Models [user] may pick on an agent (contract listAgentModels): the default, plus granted ones. */
    suspend fun models(user: HubUser, agentId: String): Items<ModelOut> {
        val agent = agents.record(agentId)?.takeIf { it.hubId == user.hubId } ?: throw HubException.notFound("agent")
        val access = permissions.access(user, agent)
        if (!access.canChat) throw HubException.notFound("agent")
        val runsApi = agents.adapterFor(agent) as? llc.applabs.switchboard.hub.adapters.AgentRuns
        val target = agents.targetFor(agent, access.profileId)
        val all = if (runsApi != null && target != null) runsApi.models(target).orEmpty() else emptyList()
        val usable = all.filter { it.isDefault || (agent.capabilities.modelSwitching && (access.models == null || it.id in access.models)) }
        return Items(usable.map { m ->
            val limit = access.models?.get(m.id)
            ModelOut(m.id, m.label, m.isPaid, m.isLocal, m.isDefault, limit, limit?.let { permissions.runsToday(user.userId, agent.id, m.id) })
        })
    }

    companion object {
        fun defaultAdapters(): Map<String, AgentAdapter> = mapOf("hermes" to HermesAdapter(HermesAdapter.client()))
    }
}

private val log = LoggerFactory.getLogger("llc.applabs.switchboard.hub.Server")

fun Application.hubModule(hub: Hub) {
    install(ContentNegotiation) { json(hubJson) }
    install(CallLogging)   // logs method, path and status only — never headers (tokens) or bodies
    install(SSE)
    install(WebSockets) { pingPeriod = 30.seconds; maxFrameSize = 1L shl 20 }
    install(StatusPages) {
        exception<HubException> { call, e -> call.problem(e.status, e.problem()) }
        exception<SerializationException> { call, e ->
            call.problem(HttpStatusCode.BadRequest, HubException.invalid("Request body: ${e.message?.take(200)}").problem())
        }
        exception<io.ktor.server.plugins.BadRequestException> { call, e ->
            call.problem(HttpStatusCode.BadRequest, HubException.invalid(e.message ?: "Bad request").problem())
        }
        exception<Throwable> { call, e ->
            // A phone that went away mid-stream (app closed, network change, hub restart) is normal, not an error.
            if (generateSequence(e) { it.cause }.any { it is io.ktor.utils.io.ClosedWriteChannelException }) {
                log.debug("client went away on {}", call.request.local.uri)
                return@exception
            }
            log.error("unhandled error on {}", call.request.local.uri, e)
            call.problem(HttpStatusCode.InternalServerError, Problem("hub_error", "Hub error", 500, "Something went wrong on the hub."))
        }
        status(HttpStatusCode.Unauthorized) { call, _ -> call.problem(HttpStatusCode.Unauthorized, HubException.unauthorized().problem()) }
    }
    install(Authentication) {
        bearer("bearer") { authenticate { cred -> hub.accounts.authenticate(cred.token)?.also(hub.members::touch) } }
    }

    routing {
        // Where the pairing QR code points: a phone camera that opens it gets a hint, the app reads the code.
        get("/pair") {
            call.respondText("Open the app and scan this code from the app's \"Connect to hub\" screen.\n")
        }
        // Hermes's relay (scheduled-job results): the setup script for a one-time code, and the gateway's
        // WebSocket. Both carry their own credentials (the code; the per-agent secret), never a person's.
        get("/relay/setup/{code}") {
            val host = call.request.headers["Host"].orEmpty()
            val local = host.startsWith("127.0.0.1") || host.startsWith("localhost") || host.startsWith("[::1]")
            val scheme = call.request.headers["X-Forwarded-Proto"]?.takeIf { it == "http" || it == "https" } ?: if (local) "http" else "https"
            val url = Relay.safeHubUrl(scheme, host) ?: throw HubException.invalid("Host")
            val script = hub.relay.setupScript(call.parameters["code"]!!, url)
                ?: return@get call.respondText("echo 'This setup code is unknown, used or expired. Make a new one in the app.' >&2; exit 1\n",
                    io.ktor.http.ContentType.Text.Plain, HttpStatusCode.NotFound)
            call.response.headers.append("Cache-Control", "no-store")
            call.respondText(script, io.ktor.http.ContentType.Text.Plain)
        }
        webSocket("/relay") {
            val agentId = hub.relay.authenticate(call.request.headers["Authorization"])
            if (agentId == null) { close(io.ktor.websocket.CloseReason(Relay.UNAUTHORIZED, "unauthorized")); return@webSocket }
            hub.relay.serve(agentId, this)
        }
        route("/v1") {
            get("/hub") {
                val (_, name) = hub.accounts.hub() ?: ("" to hub.config.hubName)
                call.respond(HubInfo(name, HUB_VERSION, API_VERSION, setupRequired = !hub.accounts.hasOwner(),
                    publicUrlMode = false))
            }
            route("/auth") {
                post("/passkeys/register/options") { call.respond(hub.passkeys.registerOptions(call.receive<RegisterOptionsIn>())) }
                post("/passkeys/register/verify") { call.respond(hub.passkeys.registerVerify(call.receive<PasskeyResult>())) }
                post("/passkeys/login/options") { call.respond(hub.passkeys.loginOptions()) }
                post("/passkeys/login/verify") { call.respond(hub.passkeys.loginVerify(call.receive<PasskeyResult>())) }
                post("/refresh") { call.respond(hub.accounts.refresh(call.receive<RefreshIn>().refreshToken)) }
            }
            get("/invites/{invite}") { call.respond(hub.members.preview(call.parameters["invite"]!!)) }
            // A FORGE bridge (`forge switchboard connect`) polls with its own token, not a person's.
            post("/forge/bridge/pair") { call.respond(hub.forge.pair(call.receive<BridgePairIn>())) }
            post("/forge/bridge/poll") { call.respond(hub.forge.poll(call.bridgeToken())) }
            post("/forge/bridge/results") {
                hub.forge.results(call.bridgeToken(), call.receive<BridgeResults>())
                call.respond(HttpStatusCode.NoContent)
            }
            authenticate("bearer") {
                get("/me") { call.respond(hub.accounts.me(call.user())) }
                run {   // members and invites (contract 0.3.0; was /household/…)
                    get("/members") { call.respond(hub.members.list(call.user())) }
                    patch("/members/{user_id}") { call.respond(hub.members.updateMember(call.user(), call.parameters["user_id"]!!, call.receive<MemberPatch>())) }
                    delete("/members/{user_id}") {
                        hub.members.removeMember(call.user(), call.parameters["user_id"]!!)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    get("/invites") { call.respond(hub.members.invites(call.user())) }
                    post("/invites") { call.respond(HttpStatusCode.Created, hub.members.createInvite(call.user(), call.receive<InviteIn>())) }
                    delete("/invites/{invite}") {
                        hub.members.revokeInvite(call.user(), call.parameters["invite"]!!)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
                get("/activity") {
                    val p = call.request.queryParameters
                    call.respond(hub.members.activity(call.user(), p["user_id"], p["agent_id"], p["cursor"], p["limit"]?.toIntOrNull() ?: 50))
                }
                post("/devices") {
                    val raw = call.request.headers["Authorization"]!!.removePrefix("Bearer ").trim()
                    call.respond(hub.members.registerPush(call.user(), raw, call.receive<DevicePushIn>()))
                }
                patch("/devices/{device_id}") {
                    call.respond(hub.members.updateDevice(call.user(), call.parameters["device_id"]!!,
                        call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim(), call.receive<DevicePatch>()))
                }
                get("/devices") {
                    call.respond(hub.members.devices(call.user(), call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()))
                }
                delete("/devices/{device_id}") {
                    hub.members.removeDevice(call.user(), call.parameters["device_id"]!!)
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/agents/{agent_id}/grants") { call.respond(hub.permissions.getGrants(call.user(), call.parameters["agent_id"]!!)) }
                put("/agents/{agent_id}/grants") {
                    val u = call.user()
                    val out = hub.permissions.setGrants(u, call.parameters["agent_id"]!!, call.receive<AgentGrants>())
                    hub.accounts.memberIds(u.hubId).let { hub.events.publish(it, "permissions.changed") }
                    call.respond(out)
                }
                route("/agents/{agent_id}/relay") {
                    get { call.respond(hub.relay.status(call.user(), call.parameters["agent_id"]!!)) }
                    post("/setup") { call.respond(HttpStatusCode.Created, hub.relay.createSetup(call.user(), call.parameters["agent_id"]!!)) }
                    delete {
                        hub.jobs.disconnectRelay(call.user(), call.parameters["agent_id"]!!)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
                get("/agents/{agent_id}/models") { call.respond(hub.models(call.user(), call.parameters["agent_id"]!!)) }
                route("/agents/{agent_id}/jobs") {
                    get { call.respond(hub.jobs.list(call.user(), call.parameters["agent_id"]!!)) }
                    post { call.respond(HttpStatusCode.Created, hub.jobs.create(call.user(), call.parameters["agent_id"]!!, call.receive<JobIn>())) }
                    get("/{job_id}") { call.respond(hub.jobs.get(call.user(), call.parameters["agent_id"]!!, call.parameters["job_id"]!!)) }
                    patch("/{job_id}") { call.respond(hub.jobs.update(call.user(), call.parameters["agent_id"]!!, call.parameters["job_id"]!!, call.receive<JobIn>())) }
                    delete("/{job_id}") {
                        hub.jobs.delete(call.user(), call.parameters["agent_id"]!!, call.parameters["job_id"]!!)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/{job_id}/result") {
                        call.respond(HttpStatusCode.Created, hub.jobs.openResult(call.user(), call.parameters["agent_id"]!!,
                            call.parameters["job_id"]!!, runCatching { call.receive<JobResultIn>() }.getOrDefault(JobResultIn())))
                    }
                    post("/{job_id}/{action}") {
                        call.respond(hub.jobs.action(call.user(), call.parameters["agent_id"]!!, call.parameters["job_id"]!!, call.parameters["action"]!!))
                    }
                }
                post("/ask-all") { call.respond(HttpStatusCode.Accepted, hub.askAll(call.user(), call.receive<AskAllIn>())) }
                get("/approvals") { call.respond(hub.approvals.list(call.user(), call.request.queryParameters["status"] ?: "pending")) }
                post("/approvals/{approval_id}/decision") {
                    call.respond(hub.runs.decide(call.user(), call.parameters["approval_id"]!!, call.receive<DecisionIn>().decision))
                }
                route("/tasks") {
                    get { call.respond(hub.tasks.list(call.user(), call.request.queryParameters["column"])) }
                    post { call.respond(HttpStatusCode.Created, hub.tasks.create(call.user(), call.receive<TaskIn>())) }
                    get("/{task_id}") { call.respond(hub.tasks.get(call.user(), call.parameters["task_id"]!!)) }
                    patch("/{task_id}") { call.respond(hub.tasks.update(call.user(), call.parameters["task_id"]!!, call.receive<TaskPatch>())) }
                    delete("/{task_id}") {
                        hub.tasks.delete(call.user(), call.parameters["task_id"]!!)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/{task_id}/move") { call.respond(hub.tasks.move(call.user(), call.parameters["task_id"]!!, call.receive<MoveIn>())) }
                    post("/{task_id}/review") { call.respond(hub.tasks.review(call.user(), call.parameters["task_id"]!!, call.receive<ReviewIn>())) }
                }
                route("/forge") {
                    get("/bridges") { call.respond(hub.forge.list(call.user())) }
                    post("/bridges") { call.respond(HttpStatusCode.Created, hub.forge.create(call.user(), call.receive<BridgeIn>())) }
                    delete("/bridges/{bridge_id}") {
                        hub.forge.revoke(call.user(), call.parameters["bridge_id"]!!)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    get("/cards") { call.respond(hub.forge.board(call.user(), fresh = call.request.queryParameters["fresh"] == "true")) }
                    post("/tickets/start") { call.respond(hub.forge.start(call.user(), call.receive<ForgeStartIn>())) }
                    get("/runs/{run_id}") { call.respond(hub.forge.run(call.user(), call.parameters["run_id"]!!)) }
                    post("/runs/{run_id}/decide") { call.respond(hub.forge.decide(call.user(), call.parameters["run_id"]!!, call.receive<ForgeDecideIn>())) }
                }
                route("/quick-commands") {
                    get { call.respond(hub.quickCommands.list(call.user())) }
                    post { call.respond(HttpStatusCode.Created, hub.quickCommands.create(call.user(), call.receive<QuickCommandIn>())) }
                    patch("/{command_id}") { call.respond(hub.quickCommands.update(call.user(), call.parameters["command_id"]!!, call.receive<QuickCommandIn>())) }
                    delete("/{command_id}") {
                        hub.quickCommands.delete(call.user(), call.parameters["command_id"]!!)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
                post("/auth/logout") {
                    call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()?.let(hub.accounts::signOut)
                    call.respond(HttpStatusCode.NoContent)
                }
                sse("/me/events") {
                    heartbeat { period = 15.seconds; event = ServerSentEvent(comments = "ping") }
                    val user = call.user()
                    val last = call.request.headers["Last-Event-ID"]?.toLongOrNull()
                    hub.events.subscribe(user.userId, last).collect { e ->
                        send(ServerSentEvent(data = e.json, event = e.type, id = e.seq.toString()))
                    }
                }
                route("/sessions") {
                    get {
                        val p = call.request.queryParameters
                        call.respond(hub.conversations.list(call.user(), p["agent_id"], p["q"], p["cursor"], p["limit"]?.toIntOrNull() ?: 30))
                    }
                    post { call.respond(HttpStatusCode.Created, hub.conversations.create(call.user(), call.receive<SessionCreate>())) }
                    get("/{session_id}") {
                        val u = call.user()
                        call.respond(hub.conversations.out(hub.conversations.get(u, call.parameters["session_id"]!!)))
                    }
                    patch("/{session_id}") { call.respond(hub.conversations.update(call.user(), call.parameters["session_id"]!!, call.receive<SessionPatch>())) }
                    delete("/{session_id}") {
                        hub.conversations.delete(call.user(), call.parameters["session_id"]!!)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    get("/{session_id}/messages") {
                        val p = call.request.queryParameters
                        call.respond(hub.conversations.messages(call.user(), call.parameters["session_id"]!!, p["cursor"], p["limit"]?.toIntOrNull() ?: 30))
                    }
                    post("/{session_id}/runs") {
                        call.respond(HttpStatusCode.Accepted, hub.runs.start(call.user(), call.parameters["session_id"]!!, call.receive<RunInputIn>()))
                    }
                }
                route("/runs/{run_id}") {
                    get { call.respond(hub.runs.getRun(call.user(), call.parameters["run_id"]!!)) }
                    sse("/events") {
                        heartbeat { period = 15.seconds; event = ServerSentEvent(comments = "ping") }
                        val flow = hub.runs.subscribe(call.user(), call.parameters["run_id"]!!, call.request.headers["Last-Event-ID"]?.toLongOrNull())
                        flow.collect { e -> send(ServerSentEvent(data = e.json, event = e.type, id = e.seq.toString())) }
                    }
                    post("/stop") {
                        hub.runs.stop(call.user(), call.parameters["run_id"]!!)
                        call.respond(HttpStatusCode.Accepted)
                    }
                    post("/steer") {
                        hub.runs.steer(call.user(), call.parameters["run_id"]!!, call.receive<SteerIn>())
                        call.respond(HttpStatusCode.Accepted)
                    }
                    post("/clarifications/{clarification_id}") {
                        hub.runs.answer(call.user(), call.parameters["run_id"]!!, call.parameters["clarification_id"]!!, call.receive<AnswerIn>())
                        call.respond(HttpStatusCode.OK)
                    }
                }
                route("/agents") {
                    get { call.respond(Items(hub.agents.list(call.user()))) }
                    post {
                        val agent = hub.agents.add(call.user(), call.receive<AgentInput>())
                        call.respond(HttpStatusCode.Created, agent)
                    }
                    post("/test") { call.respond(hub.agents.test(call.user(), call.receive<AgentInput>())) }
                    get("/{agent_id}") { call.respond(hub.agents.get(call.user(), call.parameters["agent_id"]!!)) }
                    patch("/{agent_id}") {
                        call.respond(hub.agents.update(call.user(), call.parameters["agent_id"]!!, call.receive<AgentPatch>()))
                    }
                    delete("/{agent_id}") {
                        hub.agents.remove(call.user(), call.parameters["agent_id"]!!)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}

private fun ApplicationCall.user(): HubUser = principal<HubUser>() ?: throw HubException.unauthorized()

private fun ApplicationCall.bridgeToken(): String? = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()

private suspend fun ApplicationCall.problem(status: HttpStatusCode, p: Problem) {
    respond(status, TextContent(hubJson.encodeToString(Problem.serializer(), p), ProblemJson, status))
}
