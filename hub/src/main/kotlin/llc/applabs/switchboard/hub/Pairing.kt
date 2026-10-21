package llc.applabs.switchboard.hub

import io.ktor.http.HttpStatusCode
import io.nayuki.qrcodegen.QrCode
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Setup codes: how the owner's phone pairs with the hub (product spec F1, onboarding). The code is
 * printed on the hub machine (by `serve` while there is no owner, and by `switchboard-hub setup-code`), so
 * holding it proves access to that machine.
 *
 * - The first code used creates the owner; later codes add another device for the owner (a new or
 *   lost phone — the recovery path).
 * - Single use, 24 h, one active code at a time; only its hash is stored.
 * - 10 wrong attempts burn the active code (8 characters from a 31-letter alphabet ≈ 40 bits).
 */
class Pairing(private val db: Db, private val accounts: Accounts) {
    private val random = SecureRandom()

    /** A new code (replacing any unused one), formatted `XXXX-XXXX`. */
    fun newCode(hubId: String): String {
        val raw = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        db.tx { c ->
            c.prepareStatement("DELETE FROM setup_code WHERE used_at IS NULL").use { it.executeUpdate() }
            c.prepareStatement("INSERT INTO setup_code (hash, hub_id, created_at, expires_at) VALUES (?, ?, ?, ?)").use {
                Db.bind(it, arrayOf<Any?>(Accounts.hash(raw), hubId, now(), Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS).toString()))
                it.executeUpdate()
            }
        }
        return raw.chunked(4).joinToString("-")
    }

    /** Is [code] the active, unexpired code? Wrong guesses count against the active code. */
    fun check(code: String): String {
        val hash = Accounts.hash(normalize(code))
        val row = db.query("SELECT hub_id, expires_at, failed_attempts FROM setup_code WHERE hash = ? AND used_at IS NULL", hash) {
            Triple(it.getString(1), Instant.parse(it.getString(2)), it.getInt(3))
        }.firstOrNull()
        if (row == null) {
            db.update("UPDATE setup_code SET failed_attempts = failed_attempts + 1 WHERE used_at IS NULL")
            db.update("DELETE FROM setup_code WHERE used_at IS NULL AND failed_attempts >= ?", MAX_ATTEMPTS)
            throw invalid("That setup code is not valid. Check it, or run `switchboard-hub setup-code` on the hub machine for a new one.")
        }
        if (row.second.isBefore(Instant.now()) || row.third >= MAX_ATTEMPTS)
            throw invalid("That setup code has expired. Run `switchboard-hub setup-code` on the hub machine for a new one.", HttpStatusCode.Gone)
        return row.first
    }

    /** Use the code up; false if another device used it first. */
    fun consume(code: String): Boolean =
        db.update("UPDATE setup_code SET used_at = ? WHERE hash = ? AND used_at IS NULL", now(), Accounts.hash(normalize(code))) == 1

    companion object {
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"   // no 0/O, 1/I/L
        const val TTL_HOURS = 24L
        const val MAX_ATTEMPTS = 10

        fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

        private fun invalid(detail: String, status: HttpStatusCode = HttpStatusCode.BadRequest) =
            HubException(status, "setup_code_invalid", "Setup code not accepted", detail)

        /**
         * What the pairing QR code carries: a link on the app's own domain with the hub's address and the code
         * after `#` (a browser never sends that part anywhere). A phone's camera opens it in the app, filled in.
         */
        fun pairingUrl(config: HubConfig, code: String) =
            "https://${config.rpId}/pair#hub=${java.net.URLEncoder.encode(config.hubUrl, Charsets.UTF_8)}&code=$code"

        /** [text] as a QR code for a terminal (light modules drawn, for dark terminals; 2 rows per line). */
        fun terminalQr(text: String): String {
            val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
            val border = 2
            fun dark(x: Int, y: Int) = x in 0 until qr.size && y in 0 until qr.size && qr.getModule(x, y)
            val sb = StringBuilder()
            var y = -border
            while (y < qr.size + border) {
                for (x in -border until qr.size + border) {
                    val top = !dark(x, y)
                    val bottom = !dark(x, y + 1)
                    sb.append(when {
                        top && bottom -> '█'
                        top -> '▀'
                        bottom -> '▄'
                        else -> ' '
                    })
                }
                sb.append('\n')
                y += 2
            }
            return sb.toString()
        }

        /** The block printed on the hub machine. */
        fun banner(config: HubConfig, code: String, ownerExists: Boolean): String = buildString {
            val url = pairingUrl(config, code)
            appendLine()
            appendLine(if (ownerExists) "Add a phone for the owner: scan this with the phone's camera." else "Pair the owner's phone: scan this with the phone's camera.")
            append(terminalQr(url))
            appendLine("Or enter in the app:  hub ${config.hubUrl}   code $code")
            appendLine("Single use, valid for $TTL_HOURS h. A new code: switchboard-hub setup-code")
            if (config.publicUrl == null)
                appendLine("Note: publicUrl is not set in config.json, so this code points at ${config.hubUrl}. Set it to the address phones use (e.g. https://mini-1.<tailnet>.ts.net).")
        }
    }
}
