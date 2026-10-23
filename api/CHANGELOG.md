# Changelog

## 0.4.0 — draft (2026-10-21)

M5, the task board (spec "Task board: agents working together"). Additive.
- `/tasks`, `/tasks/{task_id}`, `/tasks/{task_id}/move`, `/tasks/{task_id}/review`; schemas `Task`, `TaskStep`,
  `TaskInput`, `TaskPatch`, `TaskColumn`. A card's steps each run in their own conversation with the
  creator's permissions; only a person ends a card (Review). Limits on every card.
- Agent specialties: `Agent.about`, `tags`, `cost_level`, set with `PATCH /agents/{id}` by the owner or an admin.
- The coordinator: `Agent.coordinator` (owner sets one per hub), `TaskInput.coordinated`, `Task.parent_id`,
  `coordinated`, `plan`, `children`, `TaskStep.kind` (work / plan / combine), schema `PlanItem`, reasons `plan` and
  `plan_invalid`. The plan is read from the coordinator's reply (a JSON block) and checked by the hub.
- FORGE tickets on the board: `/forge/bridges` (a one-time code for `forge switchboard connect --code`; FORGE trades it
  at `/forge/bridge/pair` for its own token, which the app never sees),
  `/forge/cards`, `/forge/tickets/start` (preview, then confirmed), `/forge/runs/{id}` and `/forge/runs/{id}/decide`;
  the bridge's `/forge/bridge/poll` and `/forge/bridge/results` (its own token). FORGE stays the system of record.
- User events `task.changed`, `task.removed`; notification kinds `task_needs_you`, `task_review`; activity
  actions `agent_described`, `task_created`, `task_reviewed`, `task_deleted`.
- Job run history: `Job.last_error`, and `Job.recent_runs` filled in (newest first, up to 10; `result` may be
  null). The hub checks agents' jobs every minute and records each new run; a failed run sends `job_failed`
  (with `agent_id`, `job_id`) to the people who manage the agent's jobs.
- Job results in Switchboard through Hermes's relay (EXPERIMENTAL in Hermes): `/agents/{id}/relay` (status, remove),
  `/agents/{id}/relay/setup` (a one-time code; `GET /relay/setup/{code}` outside `/v1` serves the setup script),
  the gateway's `/relay` WebSocket (outside `/v1`, Hermes relay contract v1: `send` and `typing` only).
  `Job.results_here`, `Job.results_session_id`, `JobPatch.results_here` / `JobInput.results_here`; message kind
  `job_result`; notification kind `job_finished` (`session_id`, `agent_id`, `job_id`); error `relay_not_set_up`.
- `POST /agents/{id}/jobs/{job_id}/result` (`at` optional): a conversation in which the agent shows a run's saved
  result, without the relay and whatever the job's delivery; error `no_runs`.

## 0.3.0 — draft (2026-10-21)

Neutral naming: Switchboard serves families, teams and companies alike, so "household" is gone from the API.
**Breaking** (draft; the hub and app move together):
- `/household/members…` → `/members…`; `/household/invites` → `/invites`; revoking an invite is
  `DELETE /invites/{invite}`, which shares its path with the preview `GET /invites/{invite}` (by code).
- `Me.household` → `Me.hub` (`{id, name}`); `InvitePreview.household_name` → `hub_name`.
- `ShareTarget.household` → `ShareTarget.everyone` (every member who may use the agent).
- Quick-command scope `household` → `everyone`. The tag `household` becomes `members`.
- Push (M4, additive): `relay_grant` on `POST /devices` and `PATCH /devices/{id}`, `Device.push_enabled`, the
  notification kinds in `NotifyPrefs`. `POST /devices` registers the device that is signed in.

## 0.2.0 — draft (2026-10-20)

For M1 (sessions and runs). Additive: 0.1.0 clients keep working, since unknown events and fields are ignored.

- **Where conversations live: hybrid.** The hub stores every conversation, and that copy
  is what the app shows, searches and deletes. Agents with their own sessions (Hermes 0.22) continue the
  conversation's agent session; others, or one that lost its session, get the history from the hub.
  Described in `info`, `startRun` and `deleteSession`, which now deletes the agent-side session too,
  retrying while the agent is offline.
- **Clarify:** `POST /runs/{run_id}/clarifications/{clarification_id}` (MVP), run events
  `clarify.requested` and `clarify.answered`, user event `clarify.requested`, run status
  `waiting_for_input`, schema `Clarification` and `Message.clarifications`.
- **Steer:** `POST /runs/{run_id}/steer` (v1) and run event `steer.accepted`. `Message.kind` adds `steer` and
  `clarification_answer`.
- **Models:** switching a conversation's `model_id` (`updateSession`) moves from v1 to MVP. It works directly
  on agents with `capabilities.model_switching` (Hermes 0.22 honours the run's model and provider).
- **Capabilities:** `clarify`, `steer`, `reasoning`.
- **Errors:** `run_not_accepting_input`, `clarification_not_pending`, `agent_error` (the agent refused or
  reported a failure).
- `ChatSession.usage`: tokens and estimated cost per conversation.
- Household (implemented by the hub's M2): `PasskeyResult.platform`, `Device.platform` adds `other`, and
  the invite `url` format (`https://switchboard.applabs.llc/join#hub=…&code=…`).
- Fixtures: `run-clarify-steer.sse`, and **recorded real Hermes 0.22.1 streams** in `fixtures/recorded/`.
  The Hermes mapping in `events.md` is confirmed and extended: tool_call_id from Hermes, clarify, steer,
  and the 0.22 approval body.

## 0.1.0 — draft (2026-10-20)

First draft, for review before the hub and the app are built.

- REST API `/v1` (37 paths, 53 operations) across MVP and v1: hub identity, passkey auth, me and devices,
  household members and invites, agents (add/test/update, models, grants), hub-owned sessions and
  messages, runs (start, status, stop, events), ask-all, approvals, jobs, activity, quick commands.
- Event streams: run events and the per-user channel (`events.md`, `schemas/events.schema.json`).
- Fixtures: a chat with a tool call, an approval, an offline agent, user events, an agents list.
- Grounded in Hermes Agent 0.14's API server source (`docs/hermes-0.14-findings.md`).
