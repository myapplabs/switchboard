# Setting up Switchboard, step by step

This guide takes you from nothing to your whole team using your AI agents from their phones. It's
written for people who aren't developers: one command does the technical part, and it asks before it
changes anything. Plan for about 20 minutes.

- [Before you start](#before-you-start)
- [Step 1: Run the installer on the hub computer](#step-1-run-the-installer-on-the-hub-computer)
- [Step 2: Pair your phone](#step-2-pair-your-phone)
- [Step 3: Add your agents](#step-3-add-your-agents)
- [Step 4: Invite your people](#step-4-invite-your-people)
- [Keeping it running](#keeping-it-running)
- [Windows](#windows)
- [Docker](#docker)
- [Troubleshooting](#troubleshooting)

## Before you start

You need:
- **A computer that can stay on.** For the best protection, a small computer that runs only the hub, with
  your agents on other computers (see [SECURITY.md](../SECURITY.md)). Linux (for example Ubuntu, on a mini PC; Intel/AMD or ARM processors)
  or a Mac with Apple silicon (M1 or newer). You'll need its administrator password once or twice. On
  Windows, an Intel Mac or a NAS, see [Windows](#windows) or [Docker](#docker) instead of Step 1.
- **A free Tailscale account,** which the installer helps you create. Tailscale links your devices into
  a private network, so phones reach your hub from anywhere without it being on the open internet.
- **An Android phone** (Android 8 or newer), with the **Switchboard** app and the **Tailscale** app. The
  Switchboard app is in private testing: ask AppLabs ([applabs.llc](https://applabs.llc)) to add you. It
  will be on Google Play soon.
- **Your AI agent,** [Hermes Agent](https://github.com/NousResearch/hermes-agent), installed and working.
  If it's on the hub computer, the installer connects it for you.

**How to type a command:** on Ubuntu, open the **Terminal** app (press `Ctrl` + `Alt` + `T`); on a Mac,
open **Terminal** (in Applications → Utilities). Paste the command, press `Enter`, and answer its
questions. If it asks for your password, type it (you won't see the letters) and press `Enter`.

## Step 1: Run the installer on the hub computer

```sh
curl -fsSL https://switchboard.applabs.llc/install.sh | sh
```

It explains each step as it goes, and asks before changing anything outside Switchboard:

1. **Downloads the hub** for this computer and checks that the download is genuine, signed by AppLabs.
   Nothing else needs installing: the hub brings everything it needs.
   - On Linux it gives the hub **its own locked-down account** (asking for your administrator password),
     so no AI agent on the computer can read the hub's keys or conversations.
   - On a Mac the hub runs in your account for now; see [SECURITY.md](../SECURITY.md).
2. **Tailscale:** installs it if needed (on a Mac, it asks you to install the Tailscale app), and has
   you sign in by opening a link.
   - If HTTPS isn't on for your Tailscale network yet, it asks you to turn on **MagicDNS** and **HTTPS
     Certificates** in the [Tailscale admin console](https://login.tailscale.com/admin/dns), then
     continues.
   - It then gives the hub its private, secure address, like `https://mini-1.your-tailnet.ts.net`.
3. **Asks what to call the hub,** for example "Office" or "Home". People see this name in the app.
4. **Hermes Agent:** if Hermes is on this computer, it offers to connect it. It gives Hermes an API key
   (keeping a backup of Hermes's settings file first), restarts Hermes, and adds it to the hub.
5. **Starts the hub** in the background, so it keeps running and restarts by itself, and starts with the
   computer (on a Mac, when you log in).
6. **Shows a QR code:** the one-time setup code for your phone.

## Step 2: Pair your phone

1. On your phone, open **Tailscale**, sign in with the same account, and make sure it's on.
2. **Scan the QR code** on the hub computer's screen with your phone's camera (or open Switchboard and tap
   **Scan a QR code**). Switchboard opens with your hub and the code filled in.
3. Enter your name, tap **Create passkey and pair**, and confirm with your fingerprint or face.

You're now the hub's **owner**. Lost the QR code, or got a new phone later? On the hub computer run
`sudo switchboard-hub setup-code` (on a Mac: `~/.local/bin/switchboard-hub setup-code`) for a new one. On an already-paired hub, use **Owner: add this phone with a
setup code** in the app.

## Step 3: Add your agents

If the installer connected Hermes, your agent is already in the **Agents** tab. Tap it to chat.

**To add an agent on another computer:**
1. On that computer, give Hermes an API key. Make one with `openssl rand -hex 32`, add a line
   `API_SERVER_KEY=<the key>` to Hermes's settings file `~/.hermes/.env` (for a named profile,
   `~/.hermes/profiles/<name>/.env`), and restart it with `hermes gateway restart`.
2. Install Tailscale on that computer too (sign in with the same account), and share Hermes privately
   with `tailscale serve --bg --https=443 http://127.0.0.1:8642`.
3. In the app: **Agents → Add agent**. For **Address**, enter that computer's Tailscale address (for
   example `https://office-pc.your-tailnet.ts.net`); for **API key**, the key from step 1.

**Before sharing an agent with others,** read [Sharing agents safely](../SECURITY.md#sharing-agents-safely):
give them a profile with only the access they need.

Then describe what each agent is good at in **More → What agents are good at**. That helps people, and
the coordinator, pick the right agent for each task.

### Scheduled-job results in Switchboard (optional)

The **Jobs** tab always shows when each job ran and whether it worked, and tells you when one fails. To
also get each run's **results** in a conversation, the way Telegram shows them:

1. In the app (as the owner): **Jobs → Job results from <agent> → Connect**. The app shows one command.
2. On that agent's computer, in a terminal, as the account Hermes runs as, run the command. It saves a key
   for this agent only in Hermes's settings and restarts its gateway. The command works once, for 10
   minutes. For a named Hermes profile, put `HERMES_HOME=~/.hermes/profiles/<name>` before `sh`.
3. When the app says the agent is connected, turn on **Send results here** for the jobs you want. Results
   arrive in a conversation named after the job, with a notification. Reply there to ask about them.

This uses Hermes's *relay*, which Hermes still marks experimental, so a Hermes update could change it. If
results stop arriving, the Jobs tab says the agent isn't connected; run **Set up again**. The hub only
lets an agent post into its own jobs' conversations.

## Step 4: Invite your people

1. **Share the hub computer with them in Tailscale.** In the admin console, open **Machines**, click the
   hub computer's menu (`…`), choose **Share**, and send the invite. Each person installs Tailscale on
   their phone and accepts it.
2. **Invite them in Switchboard:** **Members → Invite**. Choose their role, and show them the QR code or
   send the link. It works once, for 24 hours.
3. They install Switchboard, scan or open the invite, and create their passkey.
4. **Decide what they may use:** **Members → Agent access**. Nobody can use an agent until you allow it.
   For each agent, choose per role or person: chat, the tool level, which models, and a daily limit.

Roles in short:
- **Admins** help manage people.
- **Members** use what you give them.
- **Restricted** people (for example children or guests) get only the safest settings and can never
  approve risky actions.

**Notifications** are on: phones are told when an approval, a question or a finished task is waiting
(never the content). Each person chooses which kinds in **More → Notifications on this device**.

## Keeping it running

**Back up** the hub's data folder regularly, for example to a USB drive: `/var/lib/switchboard-hub` on
Linux (copy it with `sudo`), `~/.switchboard-hub` on a Mac. It holds everything: conversations, settings
and the encrypted agent keys. The files `hub.db` and `master.key` must be backed up **together**, and the
backup kept as safe as the hub itself.

**Update** by running the installer again. It updates the hub in place and keeps your data and settings:

```sh
curl -fsSL https://switchboard.applabs.llc/install.sh | sh
```

**Useful commands** on the hub computer:

| To | Linux | Mac |
|---|---|---|
| Get a new setup code | `sudo switchboard-hub setup-code` | `~/.local/bin/switchboard-hub setup-code` |
| See what the hub is doing | `sudo journalctl -u switchboard-hub -f` | `tail -f ~/Library/Logs/switchboard-hub.log` |
| Restart it | `sudo systemctl restart switchboard-hub` | `launchctl kickstart -k gui/$(id -u)/llc.applabs.switchboard-hub` |
| Remove it (keeps your data) | `curl -fsSL https://switchboard.applabs.llc/install.sh \| sh -s -- --uninstall` | same |

**On a Mac,** the hub runs while you're logged in. For an always-on hub, turn on automatic login (System
Settings → Users & Groups) and stop the Mac from sleeping (System Settings → Energy).

## Windows

The hub doesn't run on Windows directly. These two ways should work, but we haven't tested them yet: if
you try one, please tell us how it went in an [issue](https://github.com/myapplabs/switchboard/issues).

### Option 1: WSL (Windows' built-in Linux)

1. Open **PowerShell as administrator** (right-click Start → Terminal (Admin)) and run:

   ```powershell
   wsl --install -d Ubuntu
   ```

   Restart the PC when asked. Then open **Ubuntu** from the Start menu, and choose a username and password.
2. In the Ubuntu window, run the installer, exactly as on Linux:

   ```sh
   curl -fsSL https://switchboard.applabs.llc/install.sh | sh
   ```

   It installs Tailscale inside Ubuntu, which shows up as its own device in your Tailscale network.
3. If it says it **couldn't start the service**, Ubuntu's background services (systemd) are off. Open
   its settings file with `sudo nano /etc/wsl.conf` and make sure it contains these two lines (keep any
   other lines that are there):

   ```
   [boot]
   systemd=true
   ```

   Save (`Ctrl` + `O`, `Enter`, `Ctrl` + `X`), run `wsl --shutdown` in PowerShell, reopen Ubuntu, and run
   the installer command again.
4. The hub runs while Ubuntu runs. Keep the Ubuntu window open (minimised is fine), because Windows may stop
   Ubuntu when nothing is using it.

### Option 2: Docker Desktop

1. Install [Docker Desktop](https://www.docker.com/products/docker-desktop/) and the
   [Tailscale app](https://tailscale.com/download/windows), and sign in to Tailscale. In the
   [admin console](https://login.tailscale.com/admin/dns), turn on **MagicDNS** and **HTTPS Certificates**.
2. Find this PC's Tailscale address: the Tailscale app shows the PC's name, and the full address is
   `https://<name>.<your-tailnet>.ts.net` (the admin console's **Machines** page shows it).
3. In **PowerShell**, start the hub, share it through Tailscale, and get your setup code (use your
   address in the first command):

   ```powershell
   docker run -d --name switchboard-hub --restart unless-stopped -p 127.0.0.1:8780:8780 -v switchboard-hub:/data -e PUBLIC_URL=https://office-pc.your-tailnet.ts.net -e HUB_NAME=Office ghcr.io/myapplabs/switchboard-hub
   tailscale serve --bg --https=443 http://127.0.0.1:8780
   docker exec switchboard-hub /opt/switchboard-hub/app/bin/switchboard-hub setup-code
   ```

4. Continue with [Step 2: Pair your phone](#step-2-pair-your-phone).
5. So the hub comes back after a restart: Docker Desktop → Settings → General → **Start Docker Desktop
   when you sign in**.

## Docker

For a NAS (Synology, Unraid, …), an Intel Mac, or any computer with Docker. The image works on Intel/AMD
and ARM.

```sh
docker run -d --name switchboard-hub --restart unless-stopped -p 127.0.0.1:8780:8780 \
  -v switchboard-hub:/data -e PUBLIC_URL=https://<the address phones use> -e HUB_NAME=Office \
  ghcr.io/myapplabs/switchboard-hub
```

- **Its address:** with Tailscale on the same machine, run
  `tailscale serve --bg --https=443 http://127.0.0.1:8780`, and use that machine's Tailscale address,
  `https://<name>.<your-tailnet>.ts.net`, as `PUBLIC_URL`.
- **The setup code:** `docker exec switchboard-hub /opt/switchboard-hub/app/bin/switchboard-hub setup-code`.
- **Backups:** back up the `switchboard-hub` volume; it holds everything.
- **Updates:** `docker pull ghcr.io/myapplabs/switchboard-hub`, then remove the container and run the same
  `docker run` again. Your data stays in the volume.

**From source** (developers): see [`hub/README.md`](../hub/README.md).

## Troubleshooting

**The installer stopped with an error.** It says what went wrong; fix that and run it again. It picks up
where it left off and never deletes your data.

**The app says it can't reach the hub.**
- Is Tailscale on, on the phone? Is the hub computer on and awake?
- On the hub computer, `curl http://127.0.0.1:8780/v1/hub` should print a line starting with
  `{"name":`. If not, see what the hub is doing (commands above).
- `tailscale serve status` should show your hub's address.

**Adding an agent fails.**
- "Key rejected": the API key doesn't match `API_SERVER_KEY` in that Hermes's `.env`.
- "Can't reach": on the agent's computer, `curl http://127.0.0.1:8642/health` should answer
  `{"status": "ok", …}`; from the hub computer, check its Tailscale address.

**The passkey step fails.** Use the official Switchboard app from AppLabs, and make sure the phone has a
screen lock (fingerprint, face or PIN) and is signed in to a Google account.

**Someone you invited can't connect.** They need Tailscale with your shared computer accepted (Step 4,
part 1) before the Switchboard invite works.

**Still stuck?** Open an issue on [GitHub](https://github.com/myapplabs/switchboard/issues). Leave out
keys, codes and addresses.
