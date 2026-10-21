#!/usr/bin/env bash
# Install or update the Switchboard hub on THIS Linux computer, without root:
#   hub/deploy/install.sh [public-url]
# public-url: the address phones use, e.g. https://mini-1.your-tailnet.ts.net (see docs/setup-guide.md).
# Needs Java 21 (a JDK, to build): sudo apt install openjdk-21-jdk
# Puts the hub in ~/.local/share/switchboard-hub/app, a `switchboard-hub` command in ~/.local/bin, and a
# systemd user service that starts the hub (and restarts it if it stops). Data in ~/.switchboard-hub is
# never touched by an update. (deploy.sh does the same for another computer over SSH.)
set -euo pipefail
PUBLIC_URL=${1:-}
HUB_DIR=$(cd "$(dirname "$0")/.." && pwd)
base="$HOME/.local/share/switchboard-hub"

fail() { echo "error: $*" >&2; exit 1; }

# Java 21: JAVA_HOME, or the JDK that `javac` belongs to.
java_home=${JAVA_HOME:-}
if [ -z "$java_home" ] && command -v javac >/dev/null; then
  java_home=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
fi
[ -n "$java_home" ] && "$java_home/bin/java" -version 2>&1 | grep -q 'version "21' \
  || fail "Java 21 is needed. On Ubuntu: sudo apt install openjdk-21-jdk"
case "$PUBLIC_URL" in ""|https://*|http://*) ;; *) fail "public-url must start with https:// (or http://)";; esac

echo "==> building the hub (the first time takes a few minutes)"
(cd "$HUB_DIR" && JAVA_HOME="$java_home" ./gradlew installDist --no-daemon -q)

echo "==> installing into $base"
mkdir -p "$base" "$HOME/.local/bin" "$HOME/.config/systemd/user"
rm -rf "$base/app.new"
cp -r "$HUB_DIR/build/install/switchboard-hub" "$base/app.new"
rm -rf "$base/app" && mv "$base/app.new" "$base/app"
# The service and the command run the hub with $base/jre: keep a runtime deploy.sh installed, else link this JDK.
if [ ! -d "$base/jre" ] || [ -L "$base/jre" ]; then ln -sfn "$java_home" "$base/jre"; fi
cat > "$HOME/.local/bin/switchboard-hub" <<'WRAP'
#!/bin/sh
export JAVA_HOME="$HOME/.local/share/switchboard-hub/jre"
exec "$HOME/.local/share/switchboard-hub/app/bin/switchboard-hub" "$@"
WRAP
chmod 755 "$HOME/.local/bin/switchboard-hub"
cp "$HUB_DIR/deploy/switchboard-hub.service" "$HOME/.config/systemd/user/switchboard-hub.service"

data="$HOME/.switchboard-hub"
if [ ! -f "$data/config.json" ]; then
  mkdir -p "$data" && chmod 700 "$data"
  # Behind HTTPS (Tailscale Serve) the hub only needs to listen on this computer.
  case "$PUBLIC_URL" in https://*) listen=127.0.0.1 ;; *) listen=0.0.0.0 ;; esac
  url=${PUBLIC_URL:+\"$PUBLIC_URL\"}
  cat > "$data/config.json" <<CONF
{
    "host": "$listen",
    "port": 8780,
    "hubName": "Home",
    "publicUrl": ${url:-null},
    "healthIntervalSeconds": 15
}
CONF
  echo "created $data/config.json"
else
  echo "kept your settings in $data/config.json"
fi

if [ -n "${SWITCHBOARD_NO_SERVICE:-}" ]; then echo "==> done (service not started: SWITCHBOARD_NO_SERVICE)"; exit 0; fi

echo "==> starting the hub"
systemctl --user daemon-reload
systemctl --user enable -q switchboard-hub
systemctl --user restart switchboard-hub
sleep 6
systemctl --user is-active --quiet switchboard-hub || fail "the hub didn't start; see: journalctl --user -u switchboard-hub -n 50"
if [ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" != "yes" ]; then
  echo "note: to start the hub when the computer starts (even before you log in), run once: sudo loginctl enable-linger $USER"
fi
echo
echo "The hub is running."
echo "Your setup code (for the app, until an owner exists):  ~/.local/bin/switchboard-hub setup-code"
