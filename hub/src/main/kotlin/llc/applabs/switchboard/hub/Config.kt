package llc.applabs.switchboard.hub

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.Json

/** The hub's settings: `<data dir>/config.json`, created with defaults on first run. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HubConfig(
    /** Address to listen on. 127.0.0.1 by default: phones reach the hub through Tailscale Serve (HTTPS). */
    val host: String = "127.0.0.1",
    val port: Int = 8780,
    /** The hub's name, shown in the app before sign-in (`householdName` in older config files). */
    @JsonNames("householdName") val hubName: String = "Home",
    /** The hub's public HTTPS address (Tailscale Serve MagicDNS name); passkeys and invite links use it. */
    val publicUrl: String? = null,
    /** Seconds between health checks of a healthy agent. */
    val healthIntervalSeconds: Long = 15,
    /**
     * Passkeys: the relying-party ID the app's passkeys are bound to. Every hub uses the Switchboard
     * app's shared domain, which vouches for the official app (`/.well-known/assetlinks.json`,
     * `apple-app-site-association`) — see README "Passkeys". A fork sets its own.
     */
    val rpId: String = SWITCHBOARD_RP_ID,
    /**
     * Origins allowed to finish a passkey ceremony, besides [publicUrl]'s: the app's
     * `android:apk-key-hash:<…>` and `https://<rp id>` for iOS.
     */
    val origins: List<String> = emptyList(),
    /** The push relay (push-relay/): AppLabs' official one by default; null = no push notifications. */
    val pushRelayUrl: String? = OFFICIAL_PUSH_RELAY,
) {
    /** The address phones use (also what the pairing QR code carries). */
    val hubUrl: String get() = (publicUrl ?: "http://$host:$port").trimEnd('/')

    val effectiveRpId: String get() = rpId

    /** [origins], or the official app's when none are set; `https://<rp id>` (iOS apps report it) always. */
    val effectiveOrigins: Set<String> get() = (origins.ifEmpty { SWITCHBOARD_APP_ORIGINS } + "https://$rpId").toSet()

    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

        fun load(dataDir: Path): HubConfig {
            val file = dataDir.resolve("config.json")
            if (!Files.exists(file)) {
                Files.createDirectories(dataDir)
                Files.writeString(file, json.encodeToString(serializer(), HubConfig()))
            }
            return json.decodeFromString(serializer(), Files.readString(file))
        }
    }
}

/**
 * Makes the data folder readable only by the hub's account (folder 700, files 600). Run at startup, so older
 * installs are tightened too; new files are private through the service's umask. No-op where POSIX
 * permissions don't exist.
 */
fun restrictDataDir(dataDir: Path) {
    if (!Files.isDirectory(dataDir)) return
    runCatching {
        Files.setPosixFilePermissions(dataDir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        Files.list(dataDir).use { files ->
            files.filter { Files.isRegularFile(it) }.forEach {
                Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            }
        }
    }
}

/** Where the hub keeps its database, master key and config: `SWITCHBOARD_HUB_DATA`, else `~/.switchboard-hub`. */
fun defaultDataDir(): Path =
    System.getenv("SWITCHBOARD_HUB_DATA")?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".switchboard-hub")

/** The Switchboard app's passkey domain (hosts assetlinks.json + apple-app-site-association; see passkey-domain/). */
const val SWITCHBOARD_RP_ID = "switchboard.applabs.llc"

/**
 * The official app's Android origins (`android:apk-key-hash:<base64url SHA-256 of the signing cert>`),
 * filled in once the app's release and debug signing keys exist. iOS needs none (it reports `https://<rp id>`).
 */
val SWITCHBOARD_APP_ORIGINS: List<String> = listOf(
    // The builds handed out during private testing (development signing key). The Play app-signing key is
    // added here, and to passkey-domain's assetlinks.json, when the Play release exists.
    "android:apk-key-hash:LSIYZm4GpwDVFN9eUXFOhbPKSNFDTIGpnVapeikD0mM",
)

/** AppLabs' push relay for the official app (push-relay/); a hub's config can name another or none. */
const val OFFICIAL_PUSH_RELAY = "https://switchboard-push-relay.vercel.app"

const val HUB_VERSION = "0.3.6"
const val API_VERSION = "1.0"
