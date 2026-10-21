package llc.applabs.switchboard.hub

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** RFC 9457 Problem Details with the contract's stable `code` (contract ErrorCode). */
@Serializable
data class Problem(
    val code: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val type: String = "about:blank",
    @SerialName("retry_after_s") val retryAfterS: Int? = null,
    /** Only for a failed agent connection test (ConnectionTestProblem). */
    val test: ConnectionTest? = null,
)

/** Thrown anywhere in request handling; StatusPages turns it into application/problem+json. */
class HubException(
    val status: HttpStatusCode,
    val code: String,
    val title: String,
    val detail: String? = null,
    val test: ConnectionTest? = null,
    val retryAfterS: Int? = null,
) : RuntimeException("$code: ${detail ?: title}") {
    fun problem() = Problem(code = code, title = title, status = status.value, detail = detail, test = test, retryAfterS = retryAfterS)

    companion object {
        fun forbidden(detail: String = "Your role does not allow this.") =
            HubException(HttpStatusCode.Forbidden, "forbidden", "Not allowed", detail)
        fun notFound(what: String) = HubException(HttpStatusCode.NotFound, "not_found", "Not found", "No such $what.")
        fun invalid(detail: String) = HubException(HttpStatusCode.BadRequest, "validation_failed", "Invalid request", detail)
        fun unauthorized() = HubException(HttpStatusCode.Unauthorized, "unauthorized", "Sign in required",
            "Your session has expired or is not valid. Sign in again.")
    }
}

val ProblemJson: ContentType = ContentType("application", "problem+json")
