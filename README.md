<p align="center"><img src="app/design/app-icon-dark.svg" width="96" alt="Switchboard"></p>

# Switchboard: Agent Hub

**Share your self-hosted AI agents with the people you trust.** You run AI agents on your own machines;
Switchboard puts them on everyone's phone. It works through a hub you host, with the permissions you
choose, and you stay in the loop for anything risky.

Website: [switchboard.applabs.llc](https://switchboard.applabs.llc)

## What it does

- **Chat with your agents from anywhere.** Answers stream as they're written, tool activity shows as it
  happens, you can switch models, and conversations are kept on your own hub.
- **Share without sharing keys.** Invite people with a QR code or link. Roles (owner, admin, member,
  restricted) and per-agent grants decide who may use which agent, tool level and model. Agent keys
  never leave the hub.
- **Approvals reach the right person.** When an agent wants to run something risky, someone allowed to
  decide gets the request on their phone.
- **A board where agents work together.** Give a card to one agent, or to several in turn. A
  coordinator agent can plan the work into sub-cards; you approve the plan. Agents never mark their own
  work done: every card ends in your review.
- **Jobs, quick commands, Ask all.** Manage scheduled jobs, keep prompt templates, and ask several agents
  the same question side by side.
- **Private by design.** Passkeys instead of passwords. Push notifications carry no content. Each
  member's chats and agent memory are separate.

Hermes Agent is supported today; the hub talks to agents through adapters, so other agent types can be
added.

## How it fits together

```
 phones (app)  ──HTTPS──▶  your hub  ──▶  your agents (Hermes Agent, …)
 passkeys, push            keys, rules,     on your machines, any models
                           conversations
```

The **hub** is a small server on a computer you own. Phones talk only to the hub, and the hub talks to
your agents. The hub is usually reached privately over [Tailscale](https://tailscale.com).

## This repository

| Folder | What |
|---|---|
| [`hub/`](hub) | The hub server, `switchboard-hub` (Kotlin, Ktor, SQLite). Start here to run your own |
| [`app/`](app) | The Android and iOS app (Kotlin Multiplatform, Compose Multiplatform) |
| [`api/`](api) | The API contract between the app and a hub (OpenAPI and event streams) |
| [`push-relay/`](push-relay) | The relay that delivers content-free notifications from hubs to phones |
| [`passkey-domain/`](passkey-domain) | The `switchboard.applabs.llc` website, which passkeys are bound to |

## Status

Early, and in daily use by its makers. The Android app is in private testing. The iOS app is written
but not yet built or released. Expect breaking changes before 1.0. See [`ROADMAP.md`](ROADMAP.md).

## Getting started

1. Run a hub on a computer that stays on: see [`hub/README.md`](hub/README.md).
2. Give a [Hermes Agent](https://github.com/NousResearch/hermes-agent) profile an API key, and add it to
   the hub.
3. Install the app, enter the hub's address and its setup code, and create your passkey.

## License

- The hub ([`hub/`](hub)) is licensed under the **GNU Affero General Public License v3.0**
  ([`hub/LICENSE`](hub/LICENSE)). If you run a modified hub for others, you share your changes.
- Everything else (the app, the API contract, the push relay and the website) is licensed under the
  **Apache License 2.0** ([`LICENSE`](LICENSE)), so you can build your own clients and tools freely.

The licenses cover the code, not the Switchboard name or logo; see [`NOTICE`](NOTICE).

## Security

Please report vulnerabilities privately to AppLabs, LLC (see [applabs.llc](https://applabs.llc)) rather
than in a public issue.

---

Made by [AppLabs, LLC](https://applabs.llc).
