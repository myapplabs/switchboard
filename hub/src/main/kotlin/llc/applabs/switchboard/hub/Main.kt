package llc.applabs.switchboard.hub

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * switchboard-hub — the Switchboard hub.
 *
 *   switchboard-hub [serve]                 run the hub (data: $SWITCHBOARD_HUB_DATA or ~/.switchboard-hub)
 *   switchboard-hub setup-code              on the hub machine: a new pairing code + QR code for the owner's
 *                                     phone (first pairing, or another device / a lost phone)
 *   switchboard-hub dev-token [--name NAME] on the hub machine: create the owner if there is none and print
 *                                     an admin token (30 days) for testing without the app
 *   switchboard-hub test-agent URL [--type hermes]
 *                                     check an agent's connection as the app's "Add agent" does; the
 *                                     API key is read from stdin (never an argument)
 *   switchboard-hub add-agent --name NAME --url URL [--type hermes] [--profile main]
 *                                     on the hub machine (the installer uses it): test and add an agent before
 *                                     anyone has paired; the API key is read from stdin; restart the hub after
 *   switchboard-hub version
 */
fun main(args: Array<String>) {
    val dataDir = defaultDataDir()
    when (args.firstOrNull() ?: "serve") {
        "serve" -> serve(dataDir)
        "dev-token" -> devToken(dataDir, args.drop(1))
        "setup-code" -> setupCode(dataDir)
        "test-agent" -> testAgent(args.drop(1))
        "add-agent" -> addAgent(dataDir, args.drop(1))
        "version" -> println("switchboard-hub $HUB_VERSION (API $API_VERSION)")
        else -> { System.err.println("usage: switchboard-hub [serve | setup-code | test-agent URL | add-agent --name NAME --url URL | dev-token [--name NAME] | version]"); exitProcess(2) }
    }
}

private fun serve(dataDir: java.nio.file.Path) {
    val config = HubConfig.load(dataDir)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val hub = Hub(config, Db.open(dataDir.resolve("hub.db")), SecretBox.fromDataDir(dataDir), Hub.defaultAdapters(), scope)
    hub.poller.start()
    hub.runs.recover()
    hub.conversations.startCleanup(scope)
    hub.tasks.startQueue()
    if (!hub.accounts.hasOwner()) {
        val code = hub.pairing.newCode(hub.accounts.ensureHub(config.hubName))
        println(Pairing.banner(config, code, ownerExists = false))
    }
    println("switchboard-hub $HUB_VERSION listening on http://${config.host}:${config.port} (data: $dataDir)")
    embeddedServer(Netty, port = config.port, host = config.host) { hubModule(hub) }.start(wait = true)
}

private fun devToken(dataDir: java.nio.file.Path, args: List<String>) {
    val name = args.indexOf("--name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: "Owner"
    val config = HubConfig.load(dataDir)
    Db.open(dataDir.resolve("hub.db")).use { db ->
        val accounts = Accounts(db)
        val hubId = accounts.ensureHub(config.hubName)
        val owner = accounts.owner(hubId) ?: accounts.createUser(hubId, name, Role.owner)
        val token = accounts.issue(owner, "admin", ttlSeconds = 30L * 24 * 3600, deviceName = "dev-token")
        println("Owner: ${owner.displayName} (${owner.userId})")
        println("Token (30 days, shown once — treat like a password):")
        println(token)
    }
}

private fun setupCode(dataDir: java.nio.file.Path) {
    val config = HubConfig.load(dataDir)
    Db.open(dataDir.resolve("hub.db")).use { db ->
        val accounts = Accounts(db)
        val hubId = accounts.ensureHub(config.hubName)
        val code = Pairing(db, accounts).newCode(hubId)
        println(Pairing.banner(config, code, ownerExists = accounts.hasOwner()))
    }
}

private fun addAgent(dataDir: java.nio.file.Path, args: List<String>) {
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val name = opt("--name") ?: run { System.err.println("usage: switchboard-hub add-agent --name NAME --url URL [--type hermes] [--profile main] < key"); exitProcess(2) }
    val url = opt("--url") ?: run { System.err.println("--url is required"); exitProcess(2) }
    val key = readlnOrNull()?.trim().orEmpty()
    if (key.isEmpty()) { System.err.println("The API key is read from stdin."); exitProcess(2) }
    val config = HubConfig.load(dataDir)
    Db.open(dataDir.resolve("hub.db")).use { db ->
        val accounts = Accounts(db)
        val agents = Agents(db, SecretBox.fromDataDir(dataDir), accounts, UserEvents(), Hub.defaultAdapters())
        val hubId = accounts.ensureHub(config.hubName)
        if (agents.records(hubId).any { it.name.equals(name.trim(), ignoreCase = true) }) { println("An agent named $name is already on this hub."); return }
        try {
            kotlinx.coroutines.runBlocking { agents.addForHub(hubId, AgentInput(opt("--type") ?: "hermes", name, url, listOf(ProfileInput(opt("--profile") ?: "main", null, key)))) }
            println("Added $name. Restart the hub to start using it: systemctl --user restart switchboard-hub")
        } catch (e: HubException) {
            System.err.println("Couldn't add $name: ${e.detail ?: e.title}")
            e.test?.checks?.forEach { System.err.println("  ${if (it.ok) "ok  " else "FAIL"} ${it.name}${it.detail?.let { d -> " — $d" } ?: ""}") }
            exitProcess(1)
        }
    }
}

private fun testAgent(args: List<String>) {
    val url = args.firstOrNull { !it.startsWith("--") } ?: run { System.err.println("usage: switchboard-hub test-agent URL < key"); exitProcess(2) }
    val type = args.indexOf("--type").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: "hermes"
    val adapter = Hub.defaultAdapters()[type] ?: run { System.err.println("unknown agent type $type"); exitProcess(2) }
    if (System.console() != null) System.err.print("API key (input hidden): ")
    val key = System.console()?.readPassword()?.let(::String) ?: readlnOrNull()?.trim().orEmpty()
    val result = kotlinx.coroutines.runBlocking { adapter.test(listOf(llc.applabs.switchboard.hub.adapters.ProfileTarget("main", url.trimEnd('/'), key))) }
    result.checks.forEach { println("${if (it.ok) "ok  " else "FAIL"} ${it.name}${it.detail?.let { d -> " — $d" } ?: ""}") }
    result.capabilities?.let { println("capabilities: " + hubJson.encodeToString(Capabilities.serializer(), it)) }
    exitProcess(if (result.ok) 0 else 1)
}
