#!/bin/sh
# First start: write /data/config.json listening on all interfaces (inside the container), with the address
# phones use (PUBLIC_URL) and the hub's name (HUB_NAME). Later starts keep the file as it is.
set -eu
umask 077
if [ ! -f /data/config.json ]; then
  url="null"; [ -n "${PUBLIC_URL:-}" ] && url="\"$PUBLIC_URL\""
  cat > /data/config.json <<CONF
{
    "host": "0.0.0.0",
    "port": 8780,
    "hubName": "${HUB_NAME:-Home}",
    "publicUrl": $url
}
CONF
fi
exec /opt/switchboard-hub/app/bin/switchboard-hub "$@"
