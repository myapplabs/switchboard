#!/bin/sh
# Switchboard hub installer: installs (or updates) the hub on this computer and connects it to Tailscale
# and Hermes Agent, asking before it changes anything outside Switchboard.
#
#   curl -fsSL https://switchboard.applabs.llc/install.sh | sh
#   curl -fsSL https://switchboard.applabs.llc/install.sh | sh -s -- --yes      # accept the defaults
#
# Options:
#   --yes            don't ask; accept the defaults
#   --user-mode      Linux: run the hub in your own account instead of its own (less safe; see SECURITY.md)
#   --no-tailscale   skip Tailscale (phones then need another way to reach the hub over HTTPS)
#   --no-hermes      don't connect Hermes Agent
#   --no-service     install only; don't start the hub as a service (containers, testing)
#   --version vX.Y.Z install that release instead of the latest
#   --from DIR       install an unpacked build from DIR (with app/ and runtime/) instead of downloading
#   --uninstall      remove the hub; its data is kept
#
# On Linux the hub gets its own locked-down account ("switchboard"): its data in /var/lib/switchboard-hub is
# readable only by that account, so no AI agent on this computer can read the hub's keys or conversations.
# That needs your administrator password once. On macOS the hub runs in your account (see SECURITY.md).
# Downloads come from the project's GitHub releases; their signed checksums are verified (ssh-keygen -Y verify,
# public key below) before anything is installed.
set -eu

REPO="myapplabs/switchboard"
SIGNER='releases@applabs.llc namespaces="switchboard-release" ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHKyXJKzsS5TjPgf3QVM0cSBMksI8yiHAjQrz9RulWe6'
PORT=8780
LABEL="llc.applabs.switchboard-hub"
SVC_USER="switchboard"

YES=0 TAILSCALE=1 HERMES=1 SERVICE=1 VERSION="" FROM="" UNINSTALL=0 USER_MODE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --yes|-y) YES=1 ;;
    --user-mode) USER_MODE=1 ;;
    --no-tailscale) TAILSCALE=0 ;;
    --no-hermes) HERMES=0 ;;
    --no-service) SERVICE=0 ;;
    --version) shift; VERSION="${1:-}" ;;
    --from) shift; FROM="${1:-}" ;;
    --uninstall) UNINSTALL=1 ;;
    -h|--help) sed -n '2,26p' "$0" 2>/dev/null || true; exit 0 ;;
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

# ── platform and mode ───────────────────────────────────────────────────────

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

# Linux: the hub's own account (system mode) unless --user-mode; macOS: the user's account for now.
if [ "$os" = linux ] && [ "$USER_MODE" = 0 ]; then MODE=system; else MODE=user; fi
if [ "$MODE" = system ]; then
  BASE=/opt/switchboard-hub; DATA=/var/lib/switchboard-hub; CLI=/usr/local/bin/switchboard-hub
  UNIT=/etc/systemd/system/switchboard-hub.service
else
  BASE="$HOME/.local/share/switchboard-hub"; DATA="${SWITCHBOARD_HUB_DATA:-$HOME/.switchboard-hub}"; CLI="$HOME/.local/bin/switchboard-hub"
  UNIT="$HOME/.config/systemd/user/switchboard-hub.service"
fi

# Administrator rights (system mode): sudo, or nothing when already root.
if [ "$(id -u)" = 0 ]; then SUDO=""; else SUDO="sudo"; fi
as_root() { if [ -n "$SUDO" ]; then sudo "$@"; else "$@"; fi; }
as_hub() { # run a command as the hub's account
  if [ "$MODE" = user ]; then "$@"
  elif have sudo; then as_root sudo -u "$SVC_USER" -- "$@"
  elif have runuser; then runuser -u "$SVC_USER" -- "$@"
  else su -s /bin/sh "$SVC_USER" -c "$*"; fi
}
need_root() {
  [ "$MODE" = system ] || return 0
  [ -z "$SUDO" ] && return 0
  have sudo || die "Installing the hub in its own account needs sudo (or run as root). Or use --user-mode (less safe; see SECURITY.md)."
  if ! sudo -n true 2>/dev/null; then
    say "The hub gets its own locked-down account, so no AI agent on this computer can read its keys or"
    say "conversations. That needs your administrator password once."
    sudo -v || die "Administrator rights are needed (or use --user-mode, which is less safe; see SECURITY.md)."
  fi
}
stop_service() {
  if [ "$MODE" = system ]; then as_root systemctl stop switchboard-hub >/dev/null 2>&1 || true
  elif [ "$os" = linux ]; then systemctl --user stop switchboard-hub >/dev/null 2>&1 || true
  else launchd_stop; fi
}

# ── uninstall ───────────────────────────────────────────────────────────────

if [ "$UNINSTALL" = 1 ]; then
  step "Removing the Switchboard hub"
  if [ -f /etc/systemd/system/switchboard-hub.service ] || [ -d /opt/switchboard-hub ]; then
    MODE=system; need_root
    as_root systemctl disable --now switchboard-hub >/dev/null 2>&1 || true
    as_root rm -f /etc/systemd/system/switchboard-hub.service /usr/local/bin/switchboard-hub
    as_root systemctl daemon-reload >/dev/null 2>&1 || true
    as_root rm -rf /opt/switchboard-hub
    ok "Removed. The hub's data is still in /var/lib/switchboard-hub (and its account \"$SVC_USER\");"
    say "remove them with: sudo rm -rf /var/lib/switchboard-hub && sudo userdel $SVC_USER"
  fi
  if [ -d "$HOME/.local/share/switchboard-hub" ] || [ -f "$HOME/.config/systemd/user/switchboard-hub.service" ] || [ -f "$HOME/Library/LaunchAgents/$LABEL.plist" ]; then
    if [ "$os" = linux ] && have systemctl; then
      systemctl --user disable --now switchboard-hub >/dev/null 2>&1 || true
      rm -f "$HOME/.config/systemd/user/switchboard-hub.service"; systemctl --user daemon-reload >/dev/null 2>&1 || true
    elif [ "$os" = macos ]; then
      launchd_stop; rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
    fi
    rm -rf "$HOME/.local/share/switchboard-hub" "$HOME/.local/bin/switchboard-hub"
    ok "Removed. Your data is still in $HOME/.switchboard-hub (delete it yourself if you don't need it)."
  fi
  if have tailscale && tailscale serve status 2>/dev/null | grep -q "127.0.0.1:$PORT"; then
    if ask "Stop sharing the hub over Tailscale (tailscale serve)?" y; then tailscale serve --https=443 off >/dev/null 2>&1 && ok "Stopped." || warn "Run: tailscale serve --https=443 off"; fi
  fi
  exit 0
fi

printf '\n%sSwitchboard hub installer%s (%s)\n' "$B" "$N" "$TARGET"
if [ "$MODE" = user ] && [ "$os" = linux ]; then
  warn "User mode: the hub runs in your account, so AI agents running as you could read its keys and"
  warn "conversations. Only use this if no agent runs in your account (see SECURITY.md)."
elif [ "$os" = macos ]; then
  warn "On a Mac the hub runs in your account, so AI agents running as you could read its keys. For the"
  warn "best protection run your agents on another computer, or under another macOS user (see SECURITY.md)."
fi
need_root

# An earlier install in your own account (before the hub had its own): move it, so its data leaves your home.
MIGRATE=0
if [ "$MODE" = system ] && ! as_root test -f "$DATA/config.json" && [ -f "$HOME/.switchboard-hub/config.json" ]; then
  say "Found a hub in your own account ($HOME/.switchboard-hub)."
  if ask "Move it into its own locked-down account? Its data (conversations, agents, members) moves along." y; then MIGRATE=1; else
    die "Not moved. Run with --user-mode to keep updating it where it is."
  fi
fi

UPDATE=0
if [ "$MODE" = system ]; then
  if [ -d "$BASE/app" ] && as_root test -f "$DATA/config.json"; then UPDATE=1; fi
elif [ -d "$BASE/app" ] && [ -f "$DATA/config.json" ]; then UPDATE=1; fi
[ "$UPDATE" = 1 ] && say "Updating the hub already on this computer. Its data and settings are kept."

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT INT TERM
chmod 755 "$TMP"

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
if [ "$MODE" = system ]; then
  if ! id "$SVC_USER" >/dev/null 2>&1; then
    as_root useradd --system --home-dir "$DATA" --no-create-home --shell /usr/sbin/nologin "$SVC_USER" 2>/dev/null \
      || as_root useradd -r -d "$DATA" -M -s /sbin/nologin "$SVC_USER" || die "Couldn't create the hub's account."
    ok "Created the hub's own account \"$SVC_USER\""
  fi
  [ "$UPDATE" = 1 ] && [ "$SERVICE" = 1 ] && stop_service
  as_root mkdir -p "$BASE"
  as_root rm -rf "$BASE/app.new" "$BASE/jre.new"
  as_root cp -R "$SRC/app" "$BASE/app.new"; as_root cp -R "$SRC/runtime" "$BASE/jre.new"
  as_root rm -rf "$BASE/app" "$BASE/jre"; as_root mv "$BASE/app.new" "$BASE/app"; as_root mv "$BASE/jre.new" "$BASE/jre"
  as_root chown -R root:root "$BASE"; as_root chmod -R go-w,a+rX "$BASE"   # the hub's account can't change its own program
  if [ "$MIGRATE" = 1 ]; then
    if have systemctl; then systemctl --user disable --now switchboard-hub >/dev/null 2>&1 || true; fi
    as_root mkdir -p "$DATA"
    as_root cp -Rp "$HOME/.switchboard-hub/." "$DATA/"
    as_root rm -f "$DATA/dev-token"
    [ "$(as_root cksum < "$HOME/.switchboard-hub/hub.db")" = "$(as_root sh -c "cksum < '$DATA/hub.db'")" ] \
      || die "Copying the hub's data didn't check out; nothing was removed from $HOME/.switchboard-hub."
    rm -rf "$HOME/.switchboard-hub" "$HOME/.local/share/switchboard-hub" "$HOME/.local/bin/switchboard-hub" "$HOME/.config/systemd/user/switchboard-hub.service"
    systemctl --user daemon-reload >/dev/null 2>&1 || true
    ok "Moved the hub's data to $DATA (and removed it from your home folder)"
  fi
  as_root mkdir -p "$DATA"
  as_root chown -R "$SVC_USER:$SVC_USER" "$DATA"; as_root chmod 700 "$DATA"
  as_root find "$DATA" -type f -exec chmod 600 {} +
  cat > "$TMP/cli" <<'WRAP'
#!/bin/sh
# switchboard-hub commands run as the hub's own account, whose data only it can read (administrators only).
if [ "$(id -un)" != switchboard ]; then
  if [ "$(id -u)" = 0 ] && ! command -v sudo >/dev/null 2>&1; then exec runuser -u switchboard -- "$0" "$@"; fi
  exec sudo -u switchboard -- "$0" "$@"
fi
umask 077
export JAVA_HOME=/opt/switchboard-hub/jre SWITCHBOARD_HUB_DATA=/var/lib/switchboard-hub
cd /var/lib/switchboard-hub || exit 1
exec /opt/switchboard-hub/app/bin/switchboard-hub "$@"
WRAP
  as_root install -m 755 "$TMP/cli" "$CLI"
else
  mkdir -p "$BASE" "$(dirname "$CLI")"
  rm -rf "$BASE/app.new" "$BASE/jre.new"
  cp -R "$SRC/app" "$BASE/app.new"; cp -R "$SRC/runtime" "$BASE/jre.new"
  [ "$UPDATE" = 1 ] && [ "$SERVICE" = 1 ] && stop_service
  rm -rf "$BASE/app" "$BASE/jre"; mv "$BASE/app.new" "$BASE/app"; mv "$BASE/jre.new" "$BASE/jre"
  cat > "$CLI" <<'WRAP'
#!/bin/sh
umask 077
export JAVA_HOME="$HOME/.local/share/switchboard-hub/jre"
export SWITCHBOARD_HUB_DATA="${SWITCHBOARD_HUB_DATA:-$HOME/.switchboard-hub}"
exec "$HOME/.local/share/switchboard-hub/app/bin/switchboard-hub" "$@"
WRAP
  chmod 755 "$CLI"
fi
ok "$("$CLI" version)"
if [ "$MODE" = user ]; then case ":$PATH:" in *":$(dirname "$CLI"):"*) ;; *) warn "$(dirname "$CLI") isn't on your PATH; use the full path: $CLI" ;; esac; fi

# ── Tailscale ───────────────────────────────────────────────────────────────

PUBLIC_URL=""
if [ "$UPDATE" = 0 ] && [ "$MIGRATE" = 0 ] && [ "$TAILSCALE" = 1 ]; then
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
        say "Connecting this computer to Tailscale. Open the link it prints and sign in."
        as_root tailscale up --operator="$(id -un)" || die "Tailscale didn't connect."
      else
        say "Open the Tailscale app and sign in."
        pause "Press Enter when it says Connected… "
      fi
    elif [ "$os" = linux ]; then
      tailscale serve status >/dev/null 2>&1 || as_root tailscale set --operator="$(id -un)" || true
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
         || { [ "$os" = linux ] && as_root tailscale serve --bg --https=443 "http://127.0.0.1:$PORT" >/dev/null 2>&1; }; then
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

has_config() { if [ "$MODE" = system ]; then as_root test -f "$DATA/config.json"; else [ -f "$DATA/config.json" ]; fi; }
if ! has_config; then
  step "Settings"
  hub_name=$(ask_text "What should the hub be called? (shown in the app)" "Home")
  if [ -n "$PUBLIC_URL" ]; then listen=127.0.0.1; url="\"$PUBLIC_URL\""; else listen=0.0.0.0; url=null; fi
  hub_name_json=$(printf '%s' "$hub_name" | sed 's/\\/\\\\/g; s/"/\\"/g')
  cat > "$TMP/config.json" <<CONF
{
    "host": "$listen",
    "port": $PORT,
    "hubName": "$hub_name_json",
    "publicUrl": $url,
    "pushRelayUrl": "https://switchboard-push-relay.vercel.app",
    "healthIntervalSeconds": 15
}
CONF
  if [ "$MODE" = system ]; then
    as_root install -o "$SVC_USER" -g "$SVC_USER" -m 600 "$TMP/config.json" "$DATA/config.json"
  else
    mkdir -p "$DATA"; chmod 700 "$DATA"; cp "$TMP/config.json" "$DATA/config.json"; chmod 600 "$DATA/config.json"
  fi
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
  if printf '%s\n' "$key" | "$CLI" add-agent --name "$agent_name" --url "http://127.0.0.1:$port" >"$TMP/add.log" 2>&1; then
    ok "Added the agent \"$agent_name\" to the hub"; ADDED=1
  else
    warn "Couldn't add Hermes${prof:+ profile $pname} yet: $(tail -n 3 "$TMP/add.log" | tr '\n' ' ')"
    say "You can add it later in the app: Agents → Add agent, address 127.0.0.1:$port."
  fi
}

ADDED=0
if [ "$UPDATE" = 0 ] && [ "$MIGRATE" = 0 ] && [ "$HERMES" = 1 ] && [ -d "$HOME/.hermes" ]; then
  step "Hermes Agent"
  connect_profile default "$HOME/.hermes/.env"
  for dir in "$HOME"/.hermes/profiles/*/; do
    [ -d "$dir" ] || continue
    pname=$(basename "$dir")
    if [ -n "$(env_value "$dir.env" API_SERVER_KEY)" ] || ask "Also connect the Hermes profile \"$pname\"?" n; then connect_profile "$pname" "$dir.env"; fi
  done
  say "Tip: an agent you share with other people should use a sandboxed Hermes profile (see SECURITY.md)."
elif [ "$UPDATE" = 0 ] && [ "$MIGRATE" = 0 ] && [ "$HERMES" = 1 ]; then
  step "Hermes Agent"
  say "Hermes isn't on this computer. Add your agents later in the app (Agents → Add agent)."
fi

# ── start ───────────────────────────────────────────────────────────────────

if [ "$SERVICE" = 1 ]; then
  step "Starting the hub"
  if [ "$MODE" = system ]; then
    have systemctl || die "systemd is needed to run the hub as a service (or use --no-service and the Docker image)."
    cat > "$TMP/unit" <<'UNIT'
# Switchboard hub (installed by install.sh): its own account, its data private to it, the rest of the system
# read-only, no access to home folders.
[Unit]
Description=Switchboard hub
After=network-online.target
Wants=network-online.target

[Service]
User=switchboard
Group=switchboard
Environment=JAVA_HOME=/opt/switchboard-hub/jre
Environment=SWITCHBOARD_HUB_DATA=/var/lib/switchboard-hub
Environment="JAVA_OPTS=-Xmx256m -XX:+UseSerialGC"
WorkingDirectory=/var/lib/switchboard-hub
ExecStart=/opt/switchboard-hub/app/bin/switchboard-hub serve
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=yes
ProtectSystem=strict
ReadWritePaths=/var/lib/switchboard-hub
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictSUIDSGID=yes
LockPersonality=yes
CapabilityBoundingSet=

[Install]
WantedBy=multi-user.target
UNIT
    as_root install -m 644 "$TMP/unit" "$UNIT"
    as_root systemctl daemon-reload && as_root systemctl enable -q switchboard-hub && as_root systemctl restart switchboard-hub \
      || die "Couldn't start the service. See: sudo journalctl -u switchboard-hub -n 50"
  elif [ "$os" = linux ]; then
    have systemctl || die "systemd is needed to run the hub as a service (or use --no-service and the Docker image)."
    mkdir -p "$(dirname "$UNIT")"
    cat > "$UNIT" <<'UNIT'
# Switchboard hub (installed by install.sh in user mode). Starts at boot when lingering is on.
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
UMask=0077

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
  <key>Umask</key><integer>63</integer>
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
  if ! curl -fsS --max-time 2 "http://127.0.0.1:$PORT/v1/hub" >/dev/null 2>&1; then
    case "$MODE-$os" in
      system-*) die "The hub didn't start. Its log: sudo journalctl -u switchboard-hub -n 50" ;;
      user-linux) die "The hub didn't start. Its log: journalctl --user -u switchboard-hub -n 50" ;;
      *) die "The hub didn't start. Its log: $HOME/Library/Logs/switchboard-hub.log" ;;
    esac
  fi
  ok "The hub is running"
fi

# ── done ────────────────────────────────────────────────────────────────────

if [ "$UPDATE" = 1 ] || [ "$MIGRATE" = 1 ]; then
  if [ "$MIGRATE" = 1 ]; then what="moved into its own account"; else what="updated"; fi
  printf '\n%sDone.%s The hub is %s.\n' "$G" "$N" "$what"
  exit 0
fi
printf '\n%sDone.%s Now pair your phone:\n' "$G" "$N"
say "1. Install Tailscale and the Switchboard app on your phone, and sign in to Tailscale."
say "2. Scan this code with the phone's camera (or in the app: Scan a QR code), add your name,"
say "   and create your passkey. You become the hub's owner."
if [ "$MODE" = system ]; then cmd="sudo switchboard-hub"; else cmd="switchboard-hub"; fi
"$CLI" setup-code 2>/dev/null || warn "Get a setup code: $cmd setup-code"
[ "$ADDED" = 1 ] && say "Your Hermes agent is already on the hub; it shows in the app's Agents tab."
say "A new code any time: $cmd setup-code   ·   Uninstall: … | sh -s -- --uninstall"
