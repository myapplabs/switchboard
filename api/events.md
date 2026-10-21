# Event streams (Server-Sent Events)

Two streams carry live state from the hub to the app. Payloads are JSON objects validated by
`schemas/events.schema.json`; examples are in `fixtures/`.

| Stream | Endpoint | Carries |
|---|---|---|
| **Run events** | `GET /v1/runs/{run_id}/events` | One run (one agent turn): text, tool activity, approvals, the end |
| **User events** | `GET /v1/me/events` | Everything else the signed-in user should see change live |

## Wire format

```
id: 42
event: message.delta
data: {"type":"message.delta","run_id":"run_01J…","seq":42,"at":"2026-10-20T19:02:11.481Z","delta":"Hello"}

```

- `event:` and the payload's `type` are the same string; `data:` is one line of JSON.
- `id:` is the event's sequence number (`seq`, per stream, increasing by 1). Reconnect with
  `Last-Event-ID: <seq>`; the hub replays everything after it. Run events are kept until the run's
  session is deleted (the hub stores runs; it does not depend on the agent keeping them).
- The hub sends `: ping` comment lines every 15 s; clients treat 45 s of silence as a dropped stream.
- **Unknown `type` values must be ignored** (logged, never a crash) — new event types are additive.
- **Unknown fields must be ignored.**
- Times are ISO-8601 UTC (`at`). Hermes's epoch-second timestamps are converted by the hub.

## Run events

A run's stream always starts with `run.started` and ends with exactly one terminal event
(`run.completed`, `run.failed`, `run.cancelled`), after which the hub closes the stream. Connecting to
a finished run replays its events and the terminal event.

| `type` | Fields (besides `type`, `run_id`, `seq`, `at`) | Notes |
|---|---|---|
| `run.started` | `session_id`, `agent_id`, `model_label`, `message_id` | `message_id`: the assistant message this run writes |
| `message.delta` | `delta` | Append to the assistant message (Markdown) |
| `tool.started` | `tool_call_id`, `tool`, `preview` | e.g. `terminal`, `"df -h"` |
| `tool.completed` | `tool_call_id`, `tool`, `state` (`done`/`error`), `duration_s`, `output_excerpt` | Excerpt ≤ 4 KB; full output via the message API |
| `reasoning` | `text` | Only when the user enabled "show reasoning"; may be omitted |
| `approval.requested` | `approval_id`, `command`, `description`, `allowed_decisions`, `can_decide` | `can_decide`: whether **this** user may decide (else others were notified) |
| `approval.decided` | `approval_id`, `decision`, `decided_by` | |
| `clarify.requested` | `clarification_id`, `question`, `choices`, `multi_select`, `expires_at`, `can_answer` | The agent asks the user; the run waits (`waiting_for_input`). Answer with `answerClarification`. 0.2.0 |
| `clarify.answered` | `clarification_id`, `outcome` (`answered`/`timed_out`/`cancelled`), `answer`, `answered_by` | 0.2.0 |
| `steer.accepted` | `text`, `by`, `message_id` | Guidance added mid-run (`steerRun`), also stored as a `kind: steer` message. 0.2.0 |
| `run.status` | `status` (`queued`, `running`, `waiting_for_approval`, `waiting_for_input`, `stopping`) | Status changes without other events |
| `run.completed` | `output`, `usage` | `output` is the final text (the deltas joined) |
| `run.failed` | `error`, `code` | `code` from the error codes (e.g. `agent_offline`, `agent_key_rejected`) |
| `run.cancelled` | `by` | `user_id` that stopped it, or `null` for the system |

**Interrupted runs.** If the hub loses the agent's stream it keeps polling the agent; if the agent no
longer knows the run (Hermes forgets finished runs after 1 h), the hub ends the stream with `run.failed`
and `code: "run_interrupted"`, keeping the partial text as the message (`status: "partial"`).

### Mapping from Hermes (`/v1/runs/{id}/events`; 0.14 source, confirmed on a live 0.22.1)

| Hermes event | Hub event |
|---|---|
| (run created) | `run.started` |
| `message.delta` | `message.delta` |
| `tool.started` (`tool`, `preview`, `tool_call_id` from 0.22) | `tool.started` (Hermes's `tool_call_id`; the hub assigns one only for agents that send none) |
| `tool.completed` (`tool`, `duration`, `error`) | `tool.completed` (`state` from `error`) |
| `reasoning.available` (`text`) | `reasoning` (if enabled) |
| `approval.request` (`command`, `description`, `choices`) | `approval.requested` (hub `approval_id`; decisions mapped below) |
| `approval.responded` | `approval.decided` |
| `run.completed` (`output`, `usage`) | `run.completed` |
| `run.failed` (`error`) | `run.failed` |
| `run.cancelled` | `run.cancelled` |
| `clarify.request` (`prompt_id`, `question`, `choices`, `multi_select`, `timeout_s`, `execution_generation`) — 0.22, from source | `clarify.requested` (hub `clarification_id`; `expires_at` from `timeout_s`) + `run.status` `waiting_for_input` |
| (answer accepted by `POST /v1/runs/{id}/clarify` `{request_id, execution_generation, answer}`) | `clarify.answered` |
| (`POST /v1/runs/{id}/steer` `{input}` → 200; 409 `run_not_accepting_steer`) | `steer.accepted` / 409 `run_not_accepting_input` |

Recorded real streams: `fixtures/recorded/hermes-0.22.1/`, with their framing, fields and what is not
covered yet. Hermes names the event inside the JSON (`event`), not in an SSE `event:` line.

In 0.22 an approval is answered the same way as a clarification: `POST /v1/runs/{id}/approval`
`{request_id, execution_generation, choice}`. The hub keeps `execution_generation` from the request
event. Clarify and approvals are read from 0.22's source; neither is recorded yet (approvals are off on
the test agent).

Decisions: `approve_once` → `once`, `approve_session` → `session`, `approve_always` → `always`
(owner only — it changes the agent for every user), `deny` → `deny`.

## User events

| `type` | Fields | Shown as |
|---|---|---|
| `agent.status` | `agent_id`, `status`, `activity`, `last_seen_at`, `active_runs` | Dashboard card |
| `agent.changed` | `agent_id` | Refetch the agent (added, renamed, capabilities changed, grants changed) |
| `agent.removed` | `agent_id` | Remove the card |
| `approval.requested` | `approval_id`, `agent_id`, `session_id`, `run_id`, `requested_by`, `command` | Approval card / badge — sent to everyone who may decide |
| `approval.decided` | `approval_id`, `decision`, `decided_by` | Clear the card |
| `session.updated` | `session_id`, `agent_id`, `title`, `last_message_preview`, `active_run_id` | Sessions list; shared sessions |
| `session.removed` | `session_id` | |
| `clarify.requested` | `clarification_id`, `agent_id`, `session_id`, `run_id`, `question` | Notification / badge for the person who started the run, when they are not watching it. 0.2.0 |
| `run.finished` | `run_id`, `session_id`, `agent_id`, `status` | "Long task done" when the user was not watching the run |
| `job.updated` | `agent_id`, `job_id`, `state`, `last_result` | Jobs list |
| `member.joined` | `user_id`, `display_name`, `role` | Members (owner/admin) |
| `permissions.changed` | — | Refetch `/me`, `/agents`; hide what is no longer allowed |
| `account.revoked` | — | Sign out on this device and wipe this hub's cache |
| `task.changed` | `task_id`, `column` | Board: refetch the card (created, moved, step started or finished). Sent to its creator, the owner and admins. 0.4.0 |
| `task.removed` | `task_id` | Board: remove the card. 0.4.0 |

The user channel replays from `Last-Event-ID` for up to 24 h; older gaps return a single
`resync` event (`{ "type": "resync" }`) telling the app to refetch its screens.
