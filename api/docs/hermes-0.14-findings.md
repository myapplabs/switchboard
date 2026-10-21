# Hermes Agent v0.14.0 — what the API server actually does

Read from the installed source (`gateway/platforms/api_server.py`, `tools/approval.py`; Hermes Agent
v0.14.0, 2026.5.16, commit 2ef501e1f5) on 2026-10-20, to ground this contract and the hub's Hermes
adapter in real behaviour rather than docs. Re-check on each Hermes upgrade.

## Endpoints that exist

| Method and path | Notes |
|---|---|
| `GET /health`, `GET /v1/health` | liveness |
| `GET /health/detailed` | `gateway_state`, `platforms`, `active_agents`, `pid`, `exit_reason`, `updated_at` |
| `GET /v1/models` | lists the agent as one model (`hermes-agent`); not the underlying LLM |
| `GET /v1/capabilities` | feature flags + endpoint list (below) |
| `POST /v1/chat/completions` | OpenAI format, streaming; `X-Hermes-Session-Id` (continuity), `X-Hermes-Session-Key` (memory scope) |
| `POST /v1/responses`, `GET/DELETE /v1/responses/{id}` | OpenAI Responses format; stateful via `previous_response_id` (SQLite response store) |
| `POST /v1/runs` | starts a run, returns `run_id` immediately (202) |
| `GET /v1/runs/{run_id}` | status; **terminal status kept 3600 s** (`_RUN_STATUS_TTL`) |
| `GET /v1/runs/{run_id}/events` | SSE lifecycle events (below) |
| `POST /v1/runs/{run_id}/approval` | resolve a pending approval |
| `POST /v1/runs/{run_id}/stop` | interrupt |
| `GET/POST /api/jobs`, `GET/PATCH/DELETE /api/jobs/{id}`, `POST …/pause`, `…/resume`, `…/run` | cron jobs |

**Not present** (the product spec assumed them): `/api/sessions…` (list, messages, fork),
`/v1/skills`, `/v1/toolsets`.

## Capabilities (`GET /v1/capabilities` → `features`)

`chat_completions`, `chat_completions_streaming`, `responses_api`, `responses_streaming`,
`run_submission`, `run_status`, `run_events_sse`, `run_stop`, **`run_approval_response`** (the spec
called it `run_approval`), `tool_progress_events`, `approval_events`, `session_continuity_header`
(`X-Hermes-Session-Id`), `session_key_header` (`X-Hermes-Session-Key`), `cors`. Also `auth.required`,
`runtime.tool_execution: "server"` (tools run on the agent's host).

## Runs

`POST /v1/runs` body: `input` (string, or messages array — the last is the user message, earlier
ones become history), `instructions` (system prompt for this run), `session_id` (label; also the
approval scope when no session key), `conversation_history` (`[{role, content}]`, wins over
`previous_response_id`), `previous_response_id`, `model` (recorded in the status only — **does not
choose the LLM**).

- **History is the caller's job.** A run sees earlier turns only through `conversation_history` /
  multi-message `input` / `previous_response_id`. Reusing `session_id` does not reload a transcript.
- Max **10 concurrent runs** per Hermes instance (429 `rate_limit_exceeded` beyond).
- `X-Hermes-Session-Key` (per-user long-term memory scope) is **rejected unless the API server has an
  API key** configured.

## Run events (SSE `data:` JSON, field `event`)

| event | fields |
|---|---|
| `message.delta` | `delta` (text) |
| `tool.started` | `tool`, `preview` |
| `tool.completed` | `tool`, `duration` (s), `error` (bool) |
| `reasoning.available` | `text` |
| `approval.request` | `command`, `description`, `pattern_key`, `pattern_keys`, `choices: ["once","session","always","deny"]` |
| `approval.responded` | (after a decision) |
| `run.completed` | `output`, `usage` |
| `run.failed` | `error` |
| `run.cancelled` | — |

Every event also has `run_id` and `timestamp` (epoch seconds). Status values seen in
`GET /v1/runs/{id}`: `queued`, `running`, `waiting_for_approval`, `stopping`, `completed`, `failed`,
`cancelled` (+ `last_event`, `output`, `usage`, `error`, `session_id`, `model`).

## Approvals

`POST /v1/runs/{id}/approval` body: `choice` = `once` | `session` | `always` | `deny` (aliases
`approve`/`approved`/`allow` → `once`), optional `all`/`resolve_all`. Resolution is per **approval
session key** = `X-Hermes-Session-Key` or `session_id` or `run_id`. `always` adds a permanent allow
pattern on the agent — it affects every user of that agent.

## Jobs

Create fields: `name` (required), `schedule` (required), `prompt`, `deliver` (default `local`),
`skills`, `repeat` (positive integer). Response `{ "job": {…} }`.

## Consequences for Switchboard (corrections to the product spec)

1. **The hub owns transcripts.** Sessions, messages and the Sessions browser are hub data; each run
   sends the session's history (`conversation_history`). The spec's "agents own transcripts" and its
   use of `/api/sessions` do not hold for Hermes 0.14.
2. **Reconnection:** the hub stays attached to every run and stores the final output itself; it cannot
   reconcile from Hermes after the 1-hour status TTL.
3. **Approvals:** the hub maps its own choices to Hermes's four. `always` (permanent, all users) is
   owner-only; members get `once` and `session`.
4. **Model switching:** the `model` field is cosmetic — the spec's profile-per-model approach stands.
5. **Per-user memory** needs an API key on every Hermes profile (the spec's setup already requires one).
6. **Concurrency:** ≤ 10 runs per Hermes instance; the hub queues beyond that (Ask all, task board).

## Update: Hermes 0.22.1 (checked live, 2026-10-20)

Checked against a running 0.22.1 (Sept 24, 2026) and its source:

- **Enabling:** setting `API_SERVER_KEY` (16 characters or more) in the profile's `.env` turns the API server on.
  It listens on `127.0.0.1:8642` by default (`API_SERVER_HOST` / `API_SERVER_PORT`).
- **Auth:** `/health` stays open and now includes `version`. **`/health/detailed` now needs the key.** It adds
  `readiness`, per-platform state, `api_server.active_runs`, `metrics_today` and `gateway_busy`. A wrong key
  answers 401 `{"error":{"code":"gateway_auth_failed","type":"gateway_auth_error",…}}`, where 0.14 answered
  `invalid_api_key`. The hub sends the key and uses `/health` to tell "key rejected" from "down".
- **Capabilities:** the 0.14 flags are unchanged, so the hub's mapping still holds. New ones: `run_steer`,
  `runs_idempotency`, `session_chat` and `session_chat_streaming`, `session_fork`, `session_model_lock`,
  `model_options`, `reasoning_streaming`, `skills_api` and `run_unknown_resolution`. `jobs_admin: false`
  is reported even though `/api/jobs` answers 200.
- **New endpoints:** a **Sessions API** (`/api/sessions`, `…/{id}/messages`, `…/{id}/chat`,
  `…/chat/stream`, `…/fork`, `…/model`), `/api/model/options`, and run actions `/v1/runs/{id}/steer`,
  `/clarify` and `/resolve-unknown`. Also `/v1/skills` and `/v1/toolsets`.
- **Consequence for M1 (to decide):** with a Sessions API and per-session model, Hermes can hold the
  conversation and the model choice itself. That reopens two 0.14-era decisions: "the hub owns
  transcripts" and "one profile per model". Read the session endpoints' shapes before building M1. A
  recorded real run stream from this instance can replace the synthetic fixtures.
