# The Switchboard hub as a container, built from a published release's Linux build (see .github/workflows/release.yml):
#   docker run -d --name switchboard-hub -p 8780:8780 -v switchboard-hub:/data \
#     -e PUBLIC_URL=https://hub.your-tailnet.ts.net ghcr.io/myapplabs/switchboard-hub
# Data (database, master key, config) lives in /data: back up that volume.
FROM debian:13-slim@sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad
ARG BUNDLE
ADD ${BUNDLE} /opt/
RUN set -eu; mv /opt/switchboard-hub-* /opt/switchboard-hub; \
    useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin switchboard; \
    mkdir -p /data && chown switchboard /data
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
ENV JAVA_HOME=/opt/switchboard-hub/runtime SWITCHBOARD_HUB_DATA=/data JAVA_OPTS="-Xmx256m -XX:+UseSerialGC"
USER switchboard
VOLUME /data
EXPOSE 8780
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["serve"]
