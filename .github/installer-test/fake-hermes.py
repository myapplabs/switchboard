"""A stand-in for Hermes Agent's API server, enough for the installer test: /health without a key; with the
key from ~/.hermes/.env, /health/detailed, /v1/capabilities (a real 0.22.1 answer) and /api/jobs."""
import http.server, os, sys

HOME = sys.argv[1]
CAPS = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "hermes-capabilities.json")).read()


def key():
    for line in open(os.path.join(HOME, ".hermes", ".env")):
        if line.startswith("API_SERVER_KEY="):
            return line.split("=", 1)[1].strip()


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, code, body):
        self.send_response(code)
        self.send_header("content-type", "application/json")
        self.end_headers()
        self.wfile.write(body.encode())

    def do_GET(self):
        if self.path == "/health":
            return self.send(200, '{"status": "ok", "platform": "hermes-agent", "version": "0.22.1"}')
        if self.headers.get("Authorization") != f"Bearer {key()}":
            return self.send(401, '{"error": {"code": "gateway_auth_failed"}}')
        if self.path == "/health/detailed":
            return self.send(200, '{"gateway_state": "running", "active_agents": 0}')
        if self.path == "/v1/capabilities":
            return self.send(200, CAPS)
        if self.path.startswith("/api/jobs"):
            return self.send(200, '{"jobs": []}')
        self.send(404, "{}")


http.server.ThreadingHTTPServer(("127.0.0.1", 8642), Handler).serve_forever()
