# Switchboard Hub API (contract)

How the Switchboard app talks to a Switchboard hub. The hub and the app both build to this contract, and
third-party clients can too.

**Version:** `0.4.0` (draft; see [`CHANGELOG.md`](CHANGELOG.md)).

| File | What |
|---|---|
| `openapi.yaml` | The REST API (`/v1`): passkeys and tokens, me and devices, members and invites, agents (models, grants, jobs), sessions and runs, approvals, Ask all, activity, quick commands, the board (`/tasks`) and FORGE cards (`/forge`). Each operation has an `x-release` |
| `events.md` | The two SSE streams: run events and the per-user channel. Wire format, ordering, reconnection, every event type, and the mapping from Hermes events |
| `schemas/events.schema.json` | JSON Schema for every event payload |
| `fixtures/` | Example streams and responses, used as test data by both sides. `fixtures/recorded/` holds real Hermes Agent 0.22.1 streams, the input to the hub's adapter |
| `docs/hermes-0.14-findings.md` | What Hermes Agent's API server actually does (read from its source, 0.14 and 0.22) |
| `scripts/validate.py` | Checks the OpenAPI file, every fixture against the schemas, and the stream rules |

## Rules

- **Versioning:** the path carries the major version (`/v1`). Within v1 every change is additive. The
  contract itself is semver: minor = additive, major = breaking.
- **Tolerance:** clients ignore unknown fields, event types and enum values (enums are open; an unknown
  agent status shows as offline).
- **Capabilities, not types:** clients branch on an agent's `capabilities`, never on its type.
- **Errors:** Problem Details (`application/problem+json`) with a stable `code`.
- **Secrets:** agent API keys are write-only; no response or event contains one. Push notices carry ids,
  never content.

## How the hub works with agents

1. **The hub keeps every conversation.** That copy is what the app shows, searches and deletes. Agents
   that keep their own sessions (Hermes 0.22+) continue the conversation's agent session; others, or one
   that lost its session, get the history from the hub.
2. **The hub stays attached to every run,** stores its events and output, and resumes after a restart.
3. **Approvals have four decisions** (`approve_once`, `approve_session`, `approve_always`, `deny`).
   `approve_always` changes the agent for everyone, so only the owner may choose it.
4. **Model choice:** a run's model goes to the agent where it supports switching (Hermes 0.22+ takes a
   provider and a model); profiles set the tool level.

## Validate

```bash
python -m venv .venv && .venv/bin/pip install openapi-spec-validator==0.7.1 jsonschema pyyaml
.venv/bin/python scripts/validate.py
```
