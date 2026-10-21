#!/bin/sh
# Switchboard hub installer: installs (or updates) the hub on this computer and connects it to Tailscale
# and Hermes Agent, asking before it changes anything outside Switchboard.
#
#   curl -fsSL https://switchboard.applabs.llc/install.sh | sh
#   curl -fsSL https://switchboard.applabs.llc/install.sh | sh -s -- --yes      # accept the defaults
#
# Options:
#   --yes            don't ask; accept the defaults
#   --no-tailscale   skip Tailscale (phones then need another way to reach the hub over HTTPS)
#   --no-hermes      don't connect Hermes Agent
#   --no-service     install only; don't start the hub as a service (containers, testing)
#   --version vX.Y.Z install that release instead of the latest
#   --from DIR       install an unpacked build from DIR (with app/ and runtime/) instead of downloading
#   --uninstall      remove the hub; your data in ~/.switchboard-hub is kept
#
# Downloads come from the project's GitHub releases. The installer checks their signed checksums with the
# public key below (ssh-keygen -Y verify) before installing anything. Linux (x86-64, ARM) and macOS (Apple
# silicon). No administrator rights are needed for the hub itself.
set -eu

REPO="myapplabs/switchboard"
SIGNER='releases@applabs.llc namespaces="switchboard-release" ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHKyXJKzsS5TjPgf3QVM0cSBMksI8yiHAjQrz9RulWe6'
BASE="$HOME/.local/share/switchboard-hub"
BIN="$HOME/.local/bin"
DATA="${SWITCHBOARD_HUB_DATA:-$HOME/.switchboard-hub}"
PORT=8780
LABEL="llc.applabs.switchboard-hub"

YES=0 TAILSCALE=1 HERMES=1 SERVICE=1 VERSION="" FROM="" UNINSTALL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --yes|-y) YES=1 ;;
    --no-tailscale) TAILSCALE=0 ;;
    --no-hermes) HERMES=0 ;;
    --no-service) SERVICE=0 ;;
    --version) shift; VERSION="${1:-}" ;;
    --from) shift; FROM="${1:-}" ;;
    --uninstall) UNINSTALL=1 ;;
    -h|--help) sed -n '2,22p' "$0" 2>/dev/null || true; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
  shift
done

# ── output and questions ────────────────────────────────────────────────────

if [ -t 1 ]; then B=$(printf '\033[1m'); A=$(printf '\033[33m'); G=$(printf '\033[32m'); R=$(printf '\033[31m'); N=$(printf '\033[0m'); else B= A= G= R= N=; fi
step() { printf '\n%s==>%s %s%s%s\n' "$A" "$N" "$B" "$*" "$N"; }
say() { printf '    %s\n' "$*"; }
ok() { printf '    %s✓%s %s\n' "$G" "$N" "$*"; }
warn() { printf '    %s!%s %s\n' "$A" "$N" "$*"; }
die() { printf '\n%sError:%s %s\n' "$R" "$N" "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }
TTY=0; if [ -r /dev/tty ] && (exec </dev/tty) 2>/dev/null; then TTY=1; fi

# ask "question" y|n → true for yes. Without a terminal (or with --yes) the default is used.
ask() {
  if [ "$YES" = 1 ] || [ "$TTY" = 0 ]; then [ "$2" = y ]; return; fi
  if [ "$2" = y ]; then hint="Y/n"; else hint="y/N"; fi
  printf '    %s [%s] ' "$1" "$hint" >/dev/tty
  read -r ans </dev/tty || ans=""
  case "$ans" in "") [ "$2" = y ] ;; [Yy]*) true ;; *) false ;; esac
}
# ask_text "question" "default" → the answer (or the default)
ask_text() {
  if [ "$YES" = 1 ] || [ "$TTY" = 0 ]; then printf '%s' "$2"; return; fi
  printf '    %s [%s] ' "$1" "$2" >/dev/tty
  read -r ans </dev/tty || ans=""
  printf '%s' "${ans:-$2}"
}
pause() { [ "$YES" = 1 ] || [ "$TTY" = 0 ] && return 0; printf '    %s' "$1" >/dev/tty; read -r _ </dev/tty || true; }

# launchd (macOS): bootout is asynchronous, and a bootstrap right after it fails ("5: Input/output error").
launchd_stop() {
  launchctl bootout "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || return 0
  i=0; while launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1 && [ $i -lt 20 ]; do sleep 0.5; i=$((i + 1)); done
}
launchd_start() { # launchd_start PLIST
  i=0; until launchctl bootstrap "gui/$(id -u)" "$1" 2>/dev/null; do
    i=$((i + 1)); [ $i -ge 5 ] && return 1; sleep 2
  done
}

json_get() { # json_get KEY < json → the first string value of "KEY" (enough for tailscale's status output)
  sed -n "s/.*\"$1\": *\"\([^\"]*\)\".*/\1/p" | head -n 1
}

# ── platform ────────────────────────────────────────────────────────────────

OS=$(uname -s); ARCH=$(uname -m)
case "$OS" in
  Linux) os=linux ;;
  Darwin) os=macos ;;
  *) die "Switchboard's hub runs on Linux or macOS. On other systems, use the Docker image (see the README)." ;;
esac
case "$ARCH" in
  x86_64|amd64) arch=x64 ;;
  aarch64|arm64) arch=arm64 ;;
  *) die "This processor ($ARCH) isn't supported yet. The Docker image may work." ;;
esac
TARGET="$os-$arch"
[ "$TARGET" = macos-x64 ] && die "Intel Macs aren't supported by the installer. Use the Docker image instead (see the README)."
# The agent's name in the app: this computer's short name (agent names are at most 40 characters).
HOSTNAME_SHORT=$(hostname 2>/dev/null | cut -d. -f1 | cut -c1-24); HOSTNAME_SHORT=${HOSTNAME_SHORT:-hermes}

# ── uninstall ───────────────────────────────────────────────────────────────

if [ "$UNINSTALL" = 1 ]; then
  step "Removing the Switchboard hub"
  if [ "$os" = linux ] && have systemctl; then
    systemctl --user disable --now switchboard-hub >/dev/null 2>&1 || true
    rm -f "$HOME/.config/systemd/user/switchboard-hub.service"; systemctl --user daemon-reload >/dev/null 2>&1 || true
  elif [ "$os" = macos ]; then
    launchd_stop
    rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
  fi
  rm -rf "$BASE" "$BIN/switchboard-hub"
  ok "Removed. Your data is still in $DATA (delete it yourself if you don't need it)."
  if have tailscale && tailscale serve status 2>/dev/null | grep -q "127.0.0.1:$PORT"; then
    if ask "Stop sharing the hub over Tailscale (tailscale serve)?" y; then tailscale serve --https=443 off >/dev/null 2>&1 && ok "Stopped." || warn "Run: tailscale serve --https=443 off"; fi
  fi
  exit 0
fi

UPDATE=0; [ -f "$DATA/config.json" ] && [ -d "$BASE/app" ] && UPDATE=1
printf '\n%sSwitchboard hub installer%s (%s)\n' "$B" "$N" "$TARGET"
[ "$UPDATE" = 1 ] && say "Updating the hub already on this computer. Your data and settings are kept."

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT INT TERM

# ── download and verify ─────────────────────────────────────────────────────

if [ -n "$FROM" ]; then
  step "Using the build in $FROM"
  [ -d "$FROM/app" ] && [ -d "$FROM/runtime" ] || die "$FROM needs app/ and runtime/ folders."
  SRC="$FROM"
else
  step "Downloading the hub"
  have curl || die "curl is needed."
  have ssh-keygen || die "ssh-keygen (OpenSSH) is needed to check the download's signature."
  if [ -n "$VERSION" ]; then URL="https://github.com/$REPO/releases/download/$VERSION"; else URL="https://github.com/$REPO/releases/latest/download"; fi
  FILE="switchboard-hub-$TARGET.tar.gz"
  for f in "$FILE" SHA256SUMS SHA256SUMS.sig; do
    curl -fsSL --retry 3 -o "$TMP/$f" "$URL/$f" || die "Couldn't download $f from $URL. Check the internet connection, or the version."
  done
  printf '%s\n' "$SIGNER" > "$TMP/allowed_signers"
  ssh-keygen -Y verify -f "$TMP/allowed_signers" -I releases@applabs.llc -n switchboard-release -s "$TMP/SHA256SUMS.sig" <"$TMP/SHA256SUMS" >/dev/null 2>&1 \
    || die "The download's signature doesn't match AppLabs' release key. Nothing was installed."
  want=$(grep " $FILE\$" "$TMP/SHA256SUMS" | cut -d' ' -f1)
  if have sha256sum; then got=$(sha256sum "$TMP/$FILE" | cut -d' ' -f1); else got=$(shasum -a 256 "$TMP/$FILE" | cut -d' ' -f1); fi
  [ -n "$want" ] && [ "$want" = "$got" ] || die "The download is damaged (checksum mismatch). Nothing was installed."
  ok "Downloaded and verified (signed by AppLabs)"
  tar -xzf "$TMP/$FILE" -C "$TMP"
  SRC="$TMP/switchboard-hub-$TARGET"
fi

# ── install ─────────────────────────────────────────────────────────────────

step "Installing into $BASE"
mkdir -p "$BASE" "$BIN"
rm -rf "$BASE/app.new" "$BASE/jre.new"
cp -R "$SRC/app" "$BASE/app.new"; cp -R "$SRC/runtime" "$BASE/jre.new"
if [ "$UPDATE" = 1 ] && [ "$SERVICE" = 1 ]; then
  if [ "$os" = linux ]; then systemctl --user stop switchboard-hub >/dev/null 2>&1 || true; else launchd_stop; fi
fi
rm -rf "$BASE/app" "$BASE/jre"; mv "$BASE/app.new" "$BASE/app"; mv "$BASE/jre.new" "$BASE/jre"
cat > "$BIN/switchboard-hub" <<'WRAP'
#!/bin/sh
export JAVA_HOME="$HOME/.local/share/switchboard-hub/jre"
export SWITCHBOARD_HUB_DATA="${SWITCHBOARD_HUB_DATA:-$HOME/.switchboard-hub}"
exec "$HOME/.local/share/switchboard-hub/app/bin/switchboard-hub" "$@"
WRAP
chmod 755 "$BIN/switchboard-hub"
HUB="$BIN/switchboard-hub"
ok "$("$HUB" version)"
case ":$PATH:" in *":$BIN:"*) ;; *) warn "$BIN isn't on your PATH; use the full path: $HUB" ;; esac

# ── Tailscale ───────────────────────────────────────────────────────────────

PUBLIC_URL=""
if [ "$UPDATE" = 0 ] && [ "$TAILSCALE" = 1 ]; then
  step "Tailscale (so phones reach the hub privately, from anywhere)"
  if ! have tailscale; then
    if [ "$os" = linux ]; then
      if ask "Tailscale isn't installed. Install it now? (needs your administrator password)" y; then
        curl -fsSL https://tailscale.com/install.sh | sh || die "Installing Tailscale failed."
      fi
    else
      say "Install the Tailscale app from https://tailscale.com/download/mac, open it and sign in."
      pause "Press Enter when Tailscale is running (or Ctrl+C to stop here)… "
    fi
  fi
  if have tailscale; then
    state=$(tailscale status --json 2>/dev/null | json_get BackendState)
    if [ "$state" != Running ]; then
      if [ "$os" = linux ]; then
        say "Connecting this computer to Tailscale. Open the link it prints and sign in (needs your administrator password)."
        sudo tailscale up --operator="$USER" || die "Tailscale didn't connect."
      else
        say "Open the Tailscale app and sign in."
        pause "Press Enter when it says Connected… "
      fi
    elif [ "$os" = linux ]; then
      tailscale serve status >/dev/null 2>&1 || { say "Letting your user manage Tailscale's sharing (needs your administrator password)."; sudo tailscale set --operator="$USER" || true; }
    fi
    status_json=$(tailscale status --json 2>/dev/null || true)
    name=$(printf '%s' "$status_json" | json_get DNSName); name=${name%.}
    certs=$(printf '%s' "$status_json" | tr -d '\n ' | sed -n 's/.*"CertDomains":\[\([^]]*\)\].*/\1/p')
    tries=0
    while [ -n "$name" ] && [ -z "$certs" ] && [ "$tries" -lt 3 ]; do
      warn "HTTPS isn't on for your tailnet yet. In https://login.tailscale.com/admin/dns turn on MagicDNS and HTTPS Certificates."
      pause "Press Enter when you've turned them on… "
      [ "$YES" = 1 ] || [ "$TTY" = 0 ] && break
      status_json=$(tailscale status --json 2>/dev/null || true)
      certs=$(printf '%s' "$status_json" | tr -d '\n ' | sed -n 's/.*"CertDomains":\[\([^]]*\)\].*/\1/p')
      tries=$((tries + 1))
    done
    if [ -n "$name" ] && [ -n "$certs" ]; then
      if tailscale serve --bg --https=443 "http://127.0.0.1:$PORT" >/dev/null 2>&1 \
         || { [ "$os" = linux ] && sudo tailscale serve --bg --https=443 "http://127.0.0.1:$PORT" >/dev/null 2>&1; }; then
        PUBLIC_URL="https://$name"
        ok "The hub's address: $PUBLIC_URL (only on your tailnet)"
      else
        warn "Couldn't share the hub over Tailscale. Run later: tailscale serve --bg --https=443 http://127.0.0.1:$PORT"
      fi
    else
      warn "Skipping Tailscale for now; see the setup guide to finish it."
    fi
  fi
fi

# ── settings ────────────────────────────────────────────────────────────────

if [ ! -f "$DATA/config.json" ]; then
  step "Settings"
  hub_name=$(ask_text "What should the hub be called? (shown in the app)" "Home")
  mkdir -p "$DATA"; chmod 700 "$DATA"
  if [ -n "$PUBLIC_URL" ]; then listen=127.0.0.1; url="\"$PUBLIC_URL\""; else listen=0.0.0.0; url=null; fi
  hub_name_json=$(printf '%s' "$hub_name" | sed 's/\\/\\\\/g; s/"/\\"/g')
  cat > "$DATA/config.json" <<CONF
{
    "host": "$listen",
    "port": $PORT,
    "hubName": "$hub_name_json",
    "publicUrl": $url,
    "pushRelayUrl": "https://switchboard-push-relay.vercel.app",
    "healthIntervalSeconds": 15
}
CONF
  ok "Saved $DATA/config.json"
  [ -z "$PUBLIC_URL" ] && warn "No HTTPS address yet: the app needs one to connect (see the setup guide)."
fi

# ── Hermes Agent ────────────────────────────────────────────────────────────

hermes_cmd() { if have hermes; then hermes "$@"; elif [ -x "$HOME/.local/bin/hermes" ]; then "$HOME/.local/bin/hermes" "$@"; else return 127; fi; }
random_key() { if have openssl; then openssl rand -hex 32; else od -An -tx1 -N32 /dev/urandom | tr -d ' \n'; fi; }
env_value() { sed -n "s/^$2=//p" "$1" 2>/dev/null | tail -n 1 | tr -d '"'"'"; }

connect_profile() { # connect_profile NAME ENV_FILE
  pname=$1; envf=$2
  if [ "$pname" = default ]; then agent_name=$HOSTNAME_SHORT; prof=""; else agent_name="$HOSTNAME_SHORT-$pname"; prof="--profile $pname"; fi
  key=$(env_value "$envf" API_SERVER_KEY); port=$(env_value "$envf" API_SERVER_PORT); port=${port:-8642}
  if [ -z "$key" ]; then
    ask "Connect Hermes${prof:+ profile $pname} to Switchboard? This adds an API key to $envf (a backup is kept) and restarts Hermes." y || return 0
    cp -p "$envf" "$envf.before-switchboard.$(date +%Y%m%d%H%M%S)" 2>/dev/null || touch "$envf"
    key=$(random_key)
    if [ "$pname" != default ]; then   # a named profile needs its own port
      port=8643; while grep -qs "^API_SERVER_PORT=$port\$" "$HOME"/.hermes/.env "$HOME"/.hermes/profiles/*/.env; do port=$((port + 1)); done
    fi
    { printf '\n# Switchboard hub (added by its installer, %s)\n' "$(date +%Y-%m-%d)"
      printf 'API_SERVER_KEY=%s\n' "$key"
      [ "$pname" != default ] && printf 'API_SERVER_PORT=%s\n' "$port"
      true; } >> "$envf"
    chmod 600 "$envf"
    # shellcheck disable=SC2086
    hermes_cmd $prof gateway restart >/dev/null 2>&1 || warn "Restart Hermes yourself: hermes $prof gateway restart"
    ok "API key added for Hermes${prof:+ profile $pname}"
  else
    ok "Hermes${prof:+ profile $pname} already has an API key; using it"
  fi
  i=0; until curl -fsS --max-time 2 "http://127.0.0.1:$port/health" >/dev/null 2>&1 || [ $i -ge 30 ]; do sleep 1; i=$((i + 1)); done
  if printf '%s\n' "$key" | "$HUB" add-agent --name "$agent_name" --url "http://127.0.0.1:$port" >"$TMP/add.log" 2>&1; then
    ok "Added the agent \"$agent_name\" to the hub"; ADDED=1
  else
    warn "Couldn't add Hermes${prof:+ profile $pname} yet: $(tail -n 3 "$TMP/add.log" | tr '\n' ' ')"
    say "You can add it later in the app: Agents → Add agent, address 127.0.0.1:$port."
  fi
}

ADDED=0
if [ "$UPDATE" = 0 ] && [ "$HERMES" = 1 ] && [ -d "$HOME/.hermes" ]; then
  step "Hermes Agent"
  connect_profile default "$HOME/.hermes/.env"
  for dir in "$HOME"/.hermes/profiles/*/; do
    [ -d "$dir" ] || continue
    pname=$(basename "$dir")
    if [ -n "$(env_value "$dir.env" API_SERVER_KEY)" ] || ask "Also connect the Hermes profile \"$pname\"?" n; then connect_profile "$pname" "$dir.env"; fi
  done
elif [ "$UPDATE" = 0 ] && [ "$HERMES" = 1 ]; then
  step "Hermes Agent"
  say "Hermes isn't on this computer. Add your agents later in the app (Agents → Add agent)."
fi

# ── start ───────────────────────────────────────────────────────────────────

if [ "$SERVICE" = 1 ]; then
  step "Starting the hub"
  if [ "$os" = linux ]; then
    have systemctl || die "systemd is needed to run the hub as a service (or use --no-service and the Docker image)."
    mkdir -p "$HOME/.config/systemd/user"
    cat > "$HOME/.config/systemd/user/switchboard-hub.service" <<'UNIT'
# Switchboard hub (installed by install.sh). Starts at boot when lingering is on (loginctl enable-linger).
[Unit]
Description=Switchboard hub
After=network-online.target
Wants=network-online.target

[Service]
Environment=JAVA_HOME=%h/.local/share/switchboard-hub/jre
Environment=SWITCHBOARD_HUB_DATA=%h/.switchboard-hub
Environment="JAVA_OPTS=-Xmx256m -XX:+UseSerialGC"
ExecStart=%h/.local/share/switchboard-hub/app/bin/switchboard-hub serve
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
UNIT
    systemctl --user daemon-reload && systemctl --user enable -q switchboard-hub && systemctl --user restart switchboard-hub \
      || die "Couldn't start the service. Are you logged in to this computer's desktop or over SSH as yourself?"
    if [ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" != yes ]; then
      if ask "Start the hub whenever the computer starts, even before anyone logs in? (needs your administrator password)" y; then
        sudo loginctl enable-linger "$USER" && ok "The hub starts with the computer" || warn "Run later: sudo loginctl enable-linger $USER"
      fi
    fi
  else
    plist="$HOME/Library/LaunchAgents/$LABEL.plist"; mkdir -p "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
    cat > "$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>$BASE/app/bin/switchboard-hub</string><string>serve</string></array>
  <key>EnvironmentVariables</key><dict>
    <key>JAVA_HOME</key><string>$BASE/jre</string>
    <key>SWITCHBOARD_HUB_DATA</key><string>$DATA</string>
    <key>JAVA_OPTS</key><string>-Xmx256m -XX:+UseSerialGC</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$HOME/Library/Logs/switchboard-hub.log</string>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/switchboard-hub.log</string>
</dict></plist>
PLIST
    launchd_stop
    launchd_start "$plist" || die "Couldn't start the hub with launchd. Try: launchctl bootstrap gui/$(id -u) $plist"
    say "On a Mac the hub runs while you're logged in. For an always-on hub, turn on automatic login"
    say "(System Settings → Users & Groups) and keep the Mac from sleeping (System Settings → Energy)."
  fi
  i=0; until curl -fsS --max-time 2 "http://127.0.0.1:$PORT/v1/hub" >/dev/null 2>&1 || [ $i -ge 60 ]; do sleep 1; i=$((i + 1)); done
  curl -fsS --max-time 2 "http://127.0.0.1:$PORT/v1/hub" >/dev/null 2>&1 || die "The hub didn't start. Its log: $( [ "$os" = linux ] && echo 'journalctl --user -u switchboard-hub -n 50' || echo "$HOME/Library/Logs/switchboard-hub.log")"
  ok "The hub is running"
fi

# ── done ────────────────────────────────────────────────────────────────────

if [ "$UPDATE" = 1 ]; then
  printf '\n%sDone.%s The hub is updated.\n' "$G" "$N"
  exit 0
fi
printf '\n%sDone.%s Now pair your phone:\n' "$G" "$N"
say "1. Install Tailscale and the Switchboard app on your phone, and sign in to Tailscale."
say "2. Scan this code with the phone's camera (or in the app: Scan a QR code), add your name,"
say "   and create your passkey. You become the hub's owner."
"$HUB" setup-code 2>/dev/null || warn "Get a setup code: $HUB setup-code"
[ "$ADDED" = 1 ] && say "Your Hermes agent is already on the hub; it shows in the app's Agents tab."
say "A new code any time: switchboard-hub setup-code   ·   Uninstall: … | sh -s -- --uninstall"
