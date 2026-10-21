#!/usr/bin/env bash
# Build the hub here and install or update it on a Linux machine over SSH, without root:
#   hub/deploy/deploy.sh <ssh-host> [public-url]
# - Java 21 runtime (Eclipse Temurin, checksum-verified) in ~/.local/share/switchboard-hub/jre (once)
# - the hub in ~/.local/share/switchboard-hub/app (replaced on each deploy; data is untouched)
# - `switchboard-hub` command in ~/.local/bin (sets JAVA_HOME), e.g. `switchboard-hub setup-code`
# - systemd user service, enabled and (re)started; needs lingering for start at boot
# - first install only: ~/.switchboard-hub/config.json with public-url; it listens only on this machine when
#   public-url is HTTPS (Tailscale Serve in front), otherwise on all addresses
#   (what phones use, e.g. https://mini-1.your-tailnet.ts.net, or http://192.168.1.20:8780 on a home network)
set -euo pipefail
HOST=${1:?usage: deploy.sh <ssh-host> [public-url]}
PUBLIC_URL=${2:-}
SSH_OPTS=${SSH_OPTS:-}
HUB_DIR=$(cd "$(dirname "$0")/.." && pwd)

JRE_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_x64_linux_hotspot_21.0.12.1_1.tar.gz"
JRE_SHA256="2413149700df0f7d440500a84a8f764c535f21e5a5e87d38328b64eec2c5b500"

echo "==> build"
(cd "$HUB_DIR" && ./gradlew installDist --no-daemon -q)

echo "==> Java runtime on $HOST"
ssh $SSH_OPTS "$HOST" bash -s -- "$JRE_URL" "$JRE_SHA256" <<'REMOTE'
set -euo pipefail
base=~/.local/share/switchboard-hub; mkdir -p "$base" ~/.local/bin ~/.config/systemd/user
if [ -x "$base/jre/bin/java" ]; then echo "already installed: $("$base/jre/bin/java" -version 2>&1 | head -1)"; exit 0; fi
[ "$(uname -m)" = x86_64 ] || { echo "this script pins the x86_64 runtime; machine is $(uname -m)"; exit 1; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
curl -fsSL -o "$tmp/jre.tgz" "$1"
echo "$2  $tmp/jre.tgz" | sha256sum -c --quiet
mkdir "$tmp/x" && tar -xzf "$tmp/jre.tgz" -C "$tmp/x" --strip-components=1
mv "$tmp/x" "$base/jre"
echo "installed: $("$base/jre/bin/java" -version 2>&1 | head -1)"
REMOTE

echo "==> copy the hub"
rsync -a --delete -e "ssh $SSH_OPTS" "$HUB_DIR/build/install/switchboard-hub/" "$HOST:.local/share/switchboard-hub/app/"
rsync -a -e "ssh $SSH_OPTS" "$HUB_DIR/deploy/switchboard-hub.service" "$HOST:.config/systemd/user/switchboard-hub.service"

echo "==> configure and start"
ssh $SSH_OPTS "$HOST" bash -s -- "$PUBLIC_URL" <<'REMOTE'
set -euo pipefail
cat > ~/.local/bin/switchboard-hub <<'WRAP'
#!/bin/sh
export JAVA_HOME="$HOME/.local/share/switchboard-hub/jre"
export SWITCHBOARD_HUB_DATA="${SWITCHBOARD_HUB_DATA:-$HOME/.switchboard-hub}"
exec "$HOME/.local/share/switchboard-hub/app/bin/switchboard-hub" "$@"
WRAP
chmod 755 ~/.local/bin/switchboard-hub
data=~/.switchboard-hub
if [ ! -f "$data/config.json" ]; then
  mkdir -p "$data" && chmod 700 "$data"
  url=${1:+\"$1\"}
  # Behind HTTPS (Tailscale Serve) the hub only needs to listen on this machine; a plain-HTTP address
  # means phones connect directly on the home network.
  case "$1" in https://*) listen=127.0.0.1 ;; *) listen=0.0.0.0 ;; esac
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
fi
systemctl --user daemon-reload
systemctl --user enable -q switchboard-hub
systemctl --user restart switchboard-hub
sleep 8
systemctl --user is-active switchboard-hub
echo "linger: $(loginctl show-user "$USER" -p Linger --value)"
REMOTE

echo "==> done. Pairing code (first run): ssh $HOST journalctl --user -u switchboard-hub -n 40   or   ssh $HOST .local/bin/switchboard-hub setup-code"
