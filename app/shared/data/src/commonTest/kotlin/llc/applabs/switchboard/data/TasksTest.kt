package llc.applabs.switchboard.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import llc.applabs.switchboard.domain.LiveEvents
import llc.applabs.switchboard.domain.model.Columns
import llc.applabs.switchboard.domain.model.UserEvent

private val json = headersOf(HttpHeaders.ContentType, "application/json")
private fun card(id: String, column: String, position: Double) =
    """{"id":"$id","title":"Card $id","instructions":"","column":"$column","reason":null,"reason_detail":null,"outcome":null,"source":"switchboard",
        "source_ref":null,"created_by":"usr_1","created_by_name":"paulfranco","due":null,"review_each_step":false,
        "steps":[{"id":"stp_$id","position":0,"agent_id":"agt_1","tag":null,"model_id":null,"status":"pending"}],
        "runs_used":0,"send_backs":0,"limits":{"max_steps":6,"max_runs":12,"max_send_backs":3,"max_minutes":120},
        "position":$position,"created_at":"2026-10-21T00:00:00Z","updated_at":"2026-10-21T00:00:00Z"}"""

class TasksTest {
    private val noEvents = object : LiveEvents {
        override val events: SharedFlow<UserEvent> = MutableSharedFlow()
        override val connected = kotlinx.coroutines.flow.MutableStateFlow(true)
    }

    @Test fun `board events parse`() {
        assertEquals(UserEvent.TaskChanged("tsk_1", "review"),
            EventParser.user(Json, """{"type":"task.changed","seq":4,"at":"2026-10-21T00:00:00Z","task_id":"tsk_1","column":"review"}"""))
        assertEquals(UserEvent.TaskRemoved("tsk_1"), EventParser.user(Json, """{"type":"task.removed","seq":5,"at":"2026-10-21T00:00:00Z","task_id":"tsk_1"}"""))
    }

    @Test fun `review sends the decision and note, and the board keeps the new card`() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req ->
            (req.body as? TextContent)?.let { bodies += "${req.url.encodedPath} ${it.text}" }
            when (req.url.encodedPath) {
                "/v1/tasks" -> respond("""{"items":[${card("a", "review", 1.0)},${card("b", "backlog", 2.0)}]}""", HttpStatusCode.OK, json)
                "/v1/tasks/a/review" -> respond(card("a", "ready", 1.0), HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        val tokens = TokenStore(MemoryStore()).apply { hubUrl = "http://hub:8780"; accessToken = "a"; refreshToken = "r" }
        val repo = TasksRepositoryImpl(HubClient(engine, tokens), noEvents, backgroundScope)
        repo.refresh()
        assertEquals(listOf(Columns.REVIEW, Columns.BACKLOG), repo.tasks.value.map { it.column })
        repo.review("a", "send_back", "Only direct flights.")
        assertEquals("""/v1/tasks/a/review {"decision":"send_back","note":"Only direct flights."}""", bodies.single())
        assertEquals(listOf("a" to Columns.READY, "b" to Columns.BACKLOG), repo.tasks.value.map { it.id to it.column })
    }
}
