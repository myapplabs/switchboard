# switchboard-hub

The self-hosted server behind the Switchboard app. Phones talk only to the hub; the hub holds the agent
keys, decides who may use what, relays chats and stores conversations. It is agent-neutral: each agent
type is an adapter, and [Hermes Agent](https://github.com/NousResearch/hermes-agent) is the first.

## What it does

- **Agents:** add an agent with one or more profiles (an API key each, e.g. a safe and a full tool
  level). Every profile is connection-tested; keys are encrypted (AES-256-GCM) and never returned.
  Health and capabilities are checked continuously.
- **People:** a pairing code for the owner; invites (single use, 24 h, a role preset) for everyone
  else; sign-in with passkeys; rotating tokens per device; an activity log (metadata only, 90 days).
- **Permissions:** deny by default. Per agent, grants for a role or one person: chat, jobs, the profile
  (tool level), a daily run limit, and which models with their own daily limits.
- **Conversations and runs:** streamed over SSE with resume (`Last-Event-ID`); stop, steer, answer an
  agent's question; approvals routed to someone allowed to decide. Agents that keep their own sessions
  (Hermes 0.22+) continue them; otherwise the hub sends the history.
- **Shared conversations:** with chosen members or everyone. They run on the least capable profile any
  participant may use, so sharing never gives anyone more access.
- **The board:** cards with steps on different agents, each step a normal conversation run with the
  creator's permissions, a person's review before done, limits on every card, and an optional
  coordinator agent that plans sub-cards. [FORGE](https://applabs.llc) users can show their tickets on
  the board through `forge switchboard connect`.
- **Jobs, quick commands, Ask all,** and push notices without content (through the push relay).

The API is specified in [`../api`](../api); `contract/` holds the copies the tests validate against.

## Run it

Requires JDK 21.

```sh
./gradlew test             # the test suite (a fake Hermes replaying recorded streams, a software passkey, a permission matrix)
./gradlew installDist
build/install/switchboard-hub/bin/switchboard-hub serve        # listens on 127.0.0.1:8780
```

While the hub has no owner, `serve` prints a **setup code** (and `switchboard-hub setup-code` prints a
new one). In the app, enter the hub's address and the code, and create your passkey.

The data directory is `$SWITCHBOARD_HUB_DATA` or `~/.switchboard-hub`:
- `config.json`: settings (below), created with defaults on first run;
- `hub.db`: everything the hub stores (SQLite);
- `master.key`: encrypts the agents' keys (mode 600).

**Back up `hub.db` and `master.key` together.** Without the key, stored agent keys can't be read.

### `config.json`

| Key | Default | What |
|---|---|---|
| `host`, `port` | `127.0.0.1`, `8780` | Where the hub listens. Keep `127.0.0.1` and put HTTPS in front (below) |
| `publicUrl` | none | The HTTPS address phones use; invite links and the pairing code carry it |
| `hubName` | `Home` | Shown in the app before sign-in |
| `pushRelayUrl` | none | The push relay, e.g. the official `https://switchboard-push-relay.vercel.app`. Without one, the app updates live while open but gets no notifications |
| `healthIntervalSeconds` | `15` | How often healthy agents are checked |
| `rpId`, `origins` | the official app's | Passkey settings; only a fork with its own app and domain changes them (see Passkeys) |

### HTTPS with Tailscale

The simplest way to reach your hub from phones, at home and away:

```sh
tailscale serve --bg --https=443 http://127.0.0.1:8780
```

Then set `publicUrl` to `https://<machine>.<tailnet>.ts.net` and restart the hub. People you invite
need Tailscale on their phone, and you share the hub machine with them from the Tailscale admin console.

## Add an agent

**Hermes Agent:** give the profile an API key (16+ characters) in its `.env` (`API_SERVER_KEY=…`) and
restart its gateway; its API server then listens on `127.0.0.1:8642`. If the agent runs on another
machine, put HTTPS in front of it the same way (for example `tailscale serve --bg --https=443
http://127.0.0.1:8642`).

Then add it from the app (Agents → Add agent), or with the API:

```sh
curl -X POST http://127.0.0.1:8780/v1/agents -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"type":"hermes","name":"mini-1","base_url":"https://mini-1.example.ts.net","profiles":[{"name":"main","api_key":"…"}]}'
```

`switchboard-hub test-agent <url>` checks an agent's connection from the hub machine.

## Install

**The one-step installer** (Linux x86-64 / ARM, macOS on Apple silicon): a signed release with its own
trimmed Java runtime, Tailscale and Hermes set up after asking, a systemd user service or launchd agent,
and the pairing QR code at the end. See [`../install.sh`](../install.sh) and the
[setup guide](../docs/setup-guide.md).

```sh
curl -fsSL https://switchboard.applabs.llc/install.sh | sh
```

**Docker** (`ghcr.io/myapplabs/switchboard-hub`, amd64 and arm64; data in `/data`; `PUBLIC_URL` and
`HUB_NAME` set the first config): see [`docker/`](docker) and the setup guide.

**Releases** are built by `.github/workflows/release.yml` on a version tag: each platform is smoke-tested,
`SHA256SUMS` is signed (`ssh-keygen -Y sign`, namespace `switchboard-release`; the public key is in
`install.sh`), and the Docker image is built from the same Linux builds.

## Install from source (no root needed)

On the computer that will run the hub (needs Java 21: `sudo apt install openjdk-21-jdk`):

```sh
hub/deploy/install.sh [public-url]
```

Or from another computer, over SSH (downloads its own checksum-verified Java 21 runtime on the target):

```sh
hub/deploy/deploy.sh <ssh-host> [public-url]
```

Both install:
- the Java runtime as `~/.local/share/switchboard-hub/jre`;
- the hub in `~/.local/share/switchboard-hub/app`, with a `switchboard-hub` command in `~/.local/bin`;
- a systemd **user** service (`deploy/switchboard-hub.service`), which starts at boot when lingering is
  on (`loginctl enable-linger <user>`).

Running either again updates the hub; `~/.switchboard-hub` is never touched. On the machine:
- `journalctl --user -u switchboard-hub -f` shows the log, including the setup code while there is no owner;
- `systemctl --user restart switchboard-hub` restarts the hub.

## Passkeys

Every hub binds passkeys to one shared relying-party ID, **`switchboard.applabs.llc`**. Phones only create
passkeys for the official app on a domain that vouches for it (`/.well-known/assetlinks.json` on
Android, `apple-app-site-association` on iOS), and that can't follow each hub's own address. The domain
only serves those files ([`../passkey-domain`](../passkey-domain)). The hub never contacts it; passkeys
and challenges stay on each hub.

A fork with its own app sets `rpId` and `origins` in `config.json` to its own domain and app.

## Development

`switchboard-hub dev-token --name "<you>"` prints an owner token for testing with `curl` or a debug build
of the app (it creates the owner if there is none). Never use it on a hub that others rely on.
