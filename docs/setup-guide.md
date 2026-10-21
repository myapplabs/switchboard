# Setting up Switchboard, step by step

This guide takes you from nothing to your whole team using your AI agents from their phones. It's
written for people who aren't developers: every command is explained, and you can copy and paste each one.
Plan for about an hour.

**If you get stuck,** any technically minded friend or IT person can follow this guide for you. You only
need to do it once.

- [Before you start](#before-you-start)
- [Step 1: Tailscale (the private network)](#step-1-tailscale-the-private-network)
- [Step 2: Your AI agent](#step-2-your-ai-agent)
- [Step 3: Install the hub](#step-3-install-the-hub)
- [Step 4: Give the hub its secure address](#step-4-give-the-hub-its-secure-address)
- [Step 5: Connect your phone](#step-5-connect-your-phone)
- [Step 6: Add your agent](#step-6-add-your-agent)
- [Step 7: Invite your people](#step-7-invite-your-people)
- [Step 8: Notifications](#step-8-notifications)
- [Keeping it running](#keeping-it-running)
- [Troubleshooting](#troubleshooting)

## Before you start

You need:
- **A computer that can stay on,** running Linux (Ubuntu 24.04 or newer is what we test). A small mini PC
  is plenty. You'll need its administrator password once or twice.
- **An Android phone** (Android 8 or newer).
- **The Switchboard app.** It's in private testing: ask AppLabs ([applabs.llc](https://applabs.llc)) to
  add you. It will be on Google Play soon.

**How to type commands:** on the Ubuntu computer, open the **Terminal** app (press `Ctrl` + `Alt` + `T`).
Type or paste a command, press `Enter`, and wait for it to finish. If a command asks for your password,
type it (you won't see the letters) and press `Enter`.

## Step 1: Tailscale (the private network)

Tailscale lets your phones reach the hub privately from anywhere, without opening your computer to the
internet.

1. On the hub computer, install Tailscale and sign in:

   ```sh
   curl -fsSL https://tailscale.com/install.sh | sh
   sudo tailscale up --operator=$USER
   ```

   The first line installs Tailscale. The second connects this computer and prints a **login link**:
   open it in a browser and sign in (create a free account if you don't have one).

2. In the [Tailscale admin console](https://login.tailscale.com/admin/dns), under **DNS**, turn on
   **MagicDNS** and **HTTPS Certificates**. This gives your computer a secure web address.

3. On your phone, install **Tailscale** from Google Play and sign in with the **same** account.

Find your computer's Tailscale name: run `tailscale status`. The first line shows it, for example
`mini-1`. Your hub's address will be `https://mini-1.<your-tailnet>.ts.net`. The full name is in the
admin console under **Machines**.

## Step 2: Your AI agent

Switchboard works with [Hermes Agent](https://github.com/NousResearch/hermes-agent). If it isn't
installed yet, follow Hermes's own instructions first, and make sure you can chat with it.

Then give Hermes an **API key**, a long password that only the hub will know, so the hub may talk to it:

1. Make a key:

   ```sh
   openssl rand -hex 32
   ```

   This prints a long random line of letters and numbers. Copy it, and keep it private.

2. Add it to your Hermes profile's settings file, `~/.hermes/.env` (for a named profile:
   `~/.hermes/profiles/<name>/.env`). Open it with a text editor, for example:

   ```sh
   nano ~/.hermes/.env
   ```

   Add this line at the end, with your key after the `=`:

   ```
   API_SERVER_KEY=paste-your-key-here
   ```

   Save with `Ctrl` + `O`, `Enter`, then leave with `Ctrl` + `X`.

3. Restart Hermes so it notices:

   ```sh
   hermes gateway restart
   ```

4. Check it answers. This should print `{"status": "ok", …}`:

   ```sh
   curl http://127.0.0.1:8642/health
   ```

**Agent on a different computer?** Do this step on that computer. Install Tailscale there too (Step 1),
then run `tailscale serve --bg --https=443 http://127.0.0.1:8642` on it, so the hub can reach it securely
at `https://<that-computer>.<your-tailnet>.ts.net`.

## Step 3: Install the hub

On the hub computer:

1. Install the tools the hub needs (Git, to download it, and Java 21, to run it):

   ```sh
   sudo apt update && sudo apt install -y git openjdk-21-jdk
   ```

2. Download Switchboard:

   ```sh
   git clone https://github.com/myapplabs/switchboard.git
   cd switchboard
   ```

3. Install and start the hub, giving it the address from Step 1:

   ```sh
   hub/deploy/install.sh https://mini-1.your-tailnet.ts.net
   ```

   The first time takes a few minutes. When it says **"The hub is running"**, it's done. It will also
   start by itself if it ever stops.

4. So the hub also starts when the computer restarts, before anyone logs in, run once:

   ```sh
   sudo loginctl enable-linger $USER
   ```

## Step 4: Give the hub its secure address

The hub listens only on the computer itself. This command lets Tailscale give it its secure address:

```sh
tailscale serve --bg --https=443 http://127.0.0.1:8780
```

Check it from your phone's browser, with Tailscale on: open `https://mini-1.<your-tailnet>.ts.net/v1/hub`.
You should see a short line of text starting with `{"name":"Home"`.

## Step 5: Connect your phone

1. On the hub computer, get your one-time **setup code**:

   ```sh
   ~/.local/bin/switchboard-hub setup-code
   ```

   It looks like `ABCD-EFGH`.

2. On your phone, make sure Tailscale is on, then open **Switchboard**.
3. Enter your hub's address (`mini-1.<your-tailnet>.ts.net`) and tap **Continue**.
4. Enter the setup code and your name, then tap **Create passkey and pair**. Confirm with your fingerprint
   or face.

You're now the hub's **owner**. If you get a new phone later, run the setup-code command again and use
**Owner: add this phone with a setup code** in the app.

## Step 6: Add your agent

In the app: **Agents → Add agent**.

- **Name:** anything you like, for example `office-assistant`.
- **Address:** `127.0.0.1:8642` if Hermes runs on the hub computer, otherwise its Tailscale address from
  Step 2.
- **API key:** the key you made in Step 2.

The hub tests the connection before saving it. The agent then shows as **Online**: tap it to chat.

Then tell everyone what each agent is good at in **More → What agents are good at**. This helps people,
and the coordinator, pick the right agent for each task.

## Step 7: Invite your people

1. **Share the hub computer with them in Tailscale:** in the admin console, open **Machines**, click
   your hub computer's menu (`…`), choose **Share**, and send the invite. Each person installs Tailscale
   on their phone and accepts it. (On a paid Tailscale plan you can add them to your network instead.)
2. **Invite them in Switchboard:** **Members → Invite**, choose their role, and show them the QR code or
   send the link. It works once, for 24 hours.
3. They install Switchboard, open the link (or enter the code), and create their passkey.
4. **Decide what they may use:** **Members → Agent access**. Nobody can use an agent until you allow it.
   For each agent you choose per role or person: chat, the tool level, which models, and a daily limit.

Roles in short: **admins** help manage people, **members** use what you give them, and **restricted**
people (for example children or guests) get only the safest settings and can never approve risky
actions.

## Step 8: Notifications

So phones are told when an approval, a question or a finished task is waiting, connect your hub to the
Switchboard push relay. Open the hub's settings file:

```sh
nano ~/.switchboard-hub/config.json
```

Add this line inside the braces, after the `"port"` line:

```
    "pushRelayUrl": "https://switchboard-push-relay.vercel.app",
```

Save (`Ctrl` + `O`, `Enter`, `Ctrl` + `X`), then restart the hub:

```sh
systemctl --user restart switchboard-hub
```

Notifications never contain your messages, only that something is waiting. Each person can choose which
kinds they get in **More → Notifications on this device**.

## Keeping it running

**Back up** the folder `~/.switchboard-hub` regularly (for example, copy it to a USB drive or another
computer). It holds everything: conversations, settings, and the encrypted agent keys. The files
`hub.db` and `master.key` must be backed up **together**.

**Update** the hub when a new version comes out:

```sh
cd ~/switchboard
git pull
hub/deploy/install.sh
```

Your data and settings are kept.

**Useful commands:**

| To | Run |
|---|---|
| See if the hub is running | `systemctl --user status switchboard-hub` |
| See what it's doing (`Ctrl` + `C` to stop watching) | `journalctl --user -u switchboard-hub -f` |
| Restart it | `systemctl --user restart switchboard-hub` |
| Get a new setup code | `~/.local/bin/switchboard-hub setup-code` |

## Troubleshooting

**The app says it can't reach the hub.**
- Is Tailscale on, on the phone? Is the hub computer on?
- Is the hub running? `systemctl --user status switchboard-hub` should say *active (running)*.
- Did you run Step 4's `tailscale serve` command? `tailscale serve status` should show your address.

**Adding the agent fails.**
- "Key rejected": the API key in the app doesn't match `API_SERVER_KEY` in Hermes's `.env`.
- "Can't reach": check Step 2's `curl http://127.0.0.1:8642/health` on the agent's computer. For an agent
  on another computer, check its Tailscale address from the hub computer.

**The passkey screen doesn't appear or fails.** Make sure you're using the official app from AppLabs, and
that the phone has a screen lock (fingerprint, face or PIN) and is signed in to a Google account.

**An invitee can't connect.** They need Tailscale with your shared computer accepted (Step 7, part 1)
before the Switchboard invite works.

**Still stuck?** Open an issue on [GitHub](https://github.com/myapplabs/switchboard/issues). Leave out
keys, codes and addresses.
