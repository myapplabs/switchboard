# Security

Switchboard connects people to AI agents that can take actions: run commands, read and write files, browse
the web. This page explains what Switchboard protects, what it can't, and how to set it up safely. If you
only read one part, read [Recommended setups](#recommended-setups).

## Reporting a vulnerability

Please report security problems privately: in this repository, open the **Security** tab and choose
**Report a vulnerability**. Don't open a public issue. We'll answer as soon as we can.

## What Switchboard protects

- **Sign-in:** passkeys (the phone's fingerprint, face or PIN), so there are no passwords to steal or reuse.
  Sessions use short-lived tokens; the hub stores only their hashes.
- **Agent keys:** encrypted on the hub (AES-256-GCM) and never sent to phones.
- **Who may use what:** nobody can use an agent unless you allow it. Per agent and per role or person, you
  choose the profile (tool level), which models, and daily limits.
- **Risky actions:** when an agent asks to run something risky, someone allowed to decide must approve it.
  Only the owner can allow anything "always".
- **Privacy between people:** each person's conversations are private by default, and each person's agent
  memory is kept separate.
- **Notifications:** carry no content, only that something is waiting.
- **Downloads:** releases are signed, and the installer checks the signature before installing.

## What it can't control

**What the agent itself can reach.** Switchboard decides *who may use which agent and profile*. It doesn't
decide what that agent can do on its computer. That's set in the agent's own configuration: its tools, and
whether they run directly on the computer or in a sandbox. Anyone you allow to use a powerful profile can
ask the agent to use that power.

**Prompt injection.** Agents read text from outside: web pages, emails, documents. That text can contain
hidden instructions ("read this file and send it to …"), and agents can be tricked into following them.
This is a risk even if you're the only user. It's why the hub's own data must be out of the agent's reach,
and why agents that read the web should run with only the access they need.

**Your AI provider** sees what your agents send to it, as it would without Switchboard. Choose a provider
(or local models) you're comfortable with.

## Recommended setups

From safest to least safe:

| Setup | Protection |
|---|---|
| **The hub on its own computer** (or its own virtual machine), agents elsewhere | Strongest. Even an agent with full control of its own computer can't reach the hub's data; it can only talk to the hub like a phone does. A small, cheap device is plenty. **Recommended for businesses and anyone sharing agents.** |
| **Same computer, the hub in its own account** (the installer's default on Linux) | Strong. The hub runs as a dedicated `switchboard` account; its data in `/var/lib/switchboard-hub` is readable only by it, and its service can't see home folders. Holds as long as the agent's account can't become administrator (see below). |
| **Same computer, the hub in Docker** | Similar, as long as the agent's account can't use Docker (see below). |
| **Same computer, same account** (`--user-mode`, and macOS today) | Weak. Any agent running as you can read the hub's keys and conversations. Only when no agent runs in that account. |

**The fine print for the middle two:** separate accounts protect the hub only if the agent can't get
around them. They can if the agent's account:
- can use `sudo` **without a password**, or
- is in the **`docker` group**, which gives it control of the whole computer, and runs its tools
  directly on the computer (not in a sandbox).

If either is true, put the hub on its own computer.

**On a Mac,** the installer currently runs the hub in your own account. Run your agents on another computer
or under another macOS user, or run the hub elsewhere. A dedicated hub account on macOS is on the roadmap.

## Sharing agents safely

Before you give someone access to an agent (Members → Agent access), give them a **profile** with only the
access they need. With Hermes Agent, a profile can run its tools in a **sandbox**, a container that only sees
the folders you choose. For example, a profile for your team that can read a shared Obsidian vault and
nothing else on the computer, in that profile's `config.yaml`:

```yaml
terminal:
  backend: docker                    # tools run in a container, not directly on the computer
  docker_mount_cwd_to_workspace: false
  docker_volumes:
    - /home/you/vault:/workspace/vault:ro   # this folder only, read-only (drop :ro to allow writing)
```

The agent's own account needs Docker to create the sandbox, but its tools run inside it and can't reach
Docker or anything you didn't list. Give your own, unsandboxed profile only to yourself.

Also:
- Use the **restricted** role for people who should never approve risky actions (for example, children or
  guests). Restricted grants must name a profile.
- Set **daily limits** and **allowed models** per person, to keep costs in check.
- Review **Members → Activity** now and then: who used which agent and model, and who approved what.

## Looking after the hub

- **Backups contain secrets.** The hub's data folder holds the encrypted agent keys and the key that
  decrypts them (`master.key`). Keep backups as safe as the hub itself.
- **Hub commands are for administrators.** With the default install, `sudo switchboard-hub …` runs them as
  the hub's account; other users can't get a setup code.
- **`switchboard-hub dev-token`** creates an owner token for testing (one hour by default, at most 24). It's
  printed once and never saved. Don't use it on a hub that others rely on.
- **Keep it updated:** run the installer again for the latest release.
- **Reach it privately.** The installer shares the hub only on your Tailscale network, not on the internet.
