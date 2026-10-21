package llc.applabs.switchboard.data

import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import llc.applabs.switchboard.domain.LiveEvents
import llc.applabs.switchboard.domain.TasksRepository
import llc.applabs.switchboard.domain.model.Items
import llc.applabs.switchboard.domain.model.Task
import llc.applabs.switchboard.domain.model.TaskInput
import llc.applabs.switchboard.domain.model.TaskStepInput
import llc.applabs.switchboard.domain.model.UserEvent

/** The board, kept current from `task.changed` / `task.removed` user events. */
class TasksRepositoryImpl(private val client: HubClient, live: LiveEvents, scope: CoroutineScope) : TasksRepository {
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    override val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    init {
        scope.launch {
            live.events.collect { e ->
                when (e) {
                    is UserEvent.TaskChanged -> runCatching { put(task(e.taskId)) }
                    is UserEvent.TaskRemoved -> _tasks.update { list -> list.filterNot { it.id == e.taskId } }
                    UserEvent.Resync, UserEvent.PermissionsChanged -> runCatching { refresh() }
                    else -> Unit
                }
            }
        }
    }

    override suspend fun refresh() { _tasks.value = client.call<Items<Task>>(HttpMethod.Get, "/tasks").items }
    override suspend fun task(id: String): Task = client.call(HttpMethod.Get, "/tasks/$id")
    override suspend fun create(input: TaskInput): Task = client.call<Task>(HttpMethod.Post, "/tasks", input).also(::put)
    override suspend fun update(id: String, title: String?, instructions: String?, steps: List<TaskStepInput>?): Task =
        client.call<Task>(HttpMethod.Patch, "/tasks/$id", buildJsonObject {
            title?.let { put("title", it) }; instructions?.let { put("instructions", it) }
            steps?.let { put("steps", client.json.encodeToJsonElement(ListSerializer(TaskStepInput.serializer()), it)) }
        }).also(::put)
    override suspend fun move(id: String, column: String): Task =
        client.call<Task>(HttpMethod.Post, "/tasks/$id/move", buildJsonObject { put("column", column) }).also(::put)
    override suspend fun review(id: String, decision: String, note: String?, step: Int?): Task =
        client.call<Task>(HttpMethod.Post, "/tasks/$id/review", buildJsonObject {
            put("decision", decision); note?.let { put("note", it) }; step?.let { put("step", it) }
        }).also(::put)
    override suspend fun delete(id: String) {
        client.call<Unit>(HttpMethod.Delete, "/tasks/$id")
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    private fun put(t: Task) = _tasks.update { list ->
        (if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else list + t).sortedBy { it.position }
    }
}
