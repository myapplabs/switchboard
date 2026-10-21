# Recorded Hermes 0.22.1 run streams

These are raw `GET /v1/runs/{id}/events` streams from a real Hermes Agent 0.22.1 (a test machine, 2026-10-20),
recorded with the free OpenRouter model `nvidia/nemotron-3-ultra-550b-a55b:free`. They are **Hermes's**
events, the input to the hub's adapter, not hub events. `scripts/validate.py` does not check them against
`events.schema.json`.

| Recording | What happened |
|---|---|
| `run-tool-date` | Ran `date` with the terminal tool, then answered: `tool.started` → `tool.completed` → `message.delta` ×7 → `run.completed` (9.8 s) |
| `run-followup-same-session` | Same `session_id`: "what did I ask you?". It answered correctly, so **Hermes holds the session history itself** (4.7 s) |
| `run-stopped` | Long answer, `POST /v1/runs/{id}/stop` after 6 s: `message.delta` ×78 → `run.cancelled` (`interrupted: true`, `turn_exit_reason: interrupted_during_api_call`) |

Each run has three files:
- `.sse`, the bytes exactly as received;
- `.timing.json`, each line with its arrival time in seconds, for replaying at real speed in tests;
- `.status.json`, from `GET /v1/runs/{id}` afterwards.

What the recordings show:
- **Framing:** `: open` first, then `id: <seq>` and one `data: {json}` line per event, with **no `event:`
  line** (the name is the JSON's `event` field), and `: stream closed` last. Every event carries `run_id`,
  `timestamp` (epoch seconds) and `seq`, counting from 0.
- **No `run.started`:** the hub emits its own when `POST /v1/runs` answers 202 `{"run_id", "status": "started"}`.
- **Tools:** `tool.started` has `tool`, `preview` (the command) and **`tool_call_id`** (Hermes assigns it
  now). `tool.completed` has `duration`, `error` and an empty `preview`.
- **Text:** the first `message.delta` may begin with `"\n\n"`. `run.completed.output` is the full text.
- **Ends:** `run.completed` has `usage` (input/output/total tokens), `runtime`, `completed`, `partial`
  and `interrupted`. `run.cancelled` has `completed: false`, `interrupted: true` and `turn_exit_reason`.
- **Cost:** about **30,000 input tokens per run**, mostly Hermes's system prompt and tool definitions.
- **Not recorded:** approvals (`approvals.mode` is off on this Hermes, and the owner keeps it that way),
  reasoning (this model sent none) and `run.failed`. The synthetic fixtures still cover those.
