"""
Throwaway stand-in for the three downstream peers the orchestrator saga calls
(Provider, Billing, Notification). In U1 these are not real services yet, so this
tiny server returns the success responses the saga expects, letting a provisioned
resource reach ACTIVE end to end. Not part of the app; a local demo aid only.

Run: python scripts/peer-stub.py   (listens on :8090)
"""
import json
import random
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8090
SIMULATE_HEADER = "X-Simulate-Failure"


class Handler(BaseHTTPRequestHandler):
    def _simulate(self):
        """
        Honours the X-Simulate-Failure contract the real Provider and Billing services must
        implement (FR-42, FR-45). The orchestrator strips any 'provider:'/'billing:' prefix
        before sending, so the value arriving here is the bare mode.

        Returns True if this call was answered as a simulated failure (caller should stop).
        """
        mode = (self.headers.get(SIMULATE_HEADER) or "").strip().lower()
        if mode == "always-fail":
            self._send(503, {"error": "simulated failure via " + SIMULATE_HEADER})
            return True
        if mode == "slow":
            time.sleep(4)
        return False

    def _send(self, status, body=None):
        payload = b"" if body is None else json.dumps(body).encode()
        self.send_response(status)
        if payload:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if payload:
            self.wfile.write(payload)

    def do_POST(self):
        path = self.path.split("?")[0]
        # Drain any request body so the socket stays healthy.
        length = int(self.headers.get("Content-Length", 0))
        if length:
            self.rfile.read(length)

        if path == "/api/v1/provider/instances":
            if self._simulate():
                return
            self._send(201, {"instanceId": f"i-mock-{random.randint(10**7, 10**8):x}",
                             "status": "ACTIVE"})
        elif re.fullmatch(r"/api/v1/billing/meters/[^/]+/stop", path):
            # Compensation / teardown call: never simulated, so an undo always completes.
            self._send(200)
        elif path == "/api/v1/billing/meters":
            if self._simulate():
                return
            self._send(201)
        elif path == "/api/v1/notifications":
            self._send(202)
        else:
            self._send(404, {"error": "no stub for " + path})

    def do_DELETE(self):
        path = self.path.split("?")[0]
        if re.fullmatch(r"/api/v1/provider/instances/[^/]+", path):
            self._send(200)
        else:
            self._send(404, {"error": "no stub for " + path})

    def log_message(self, fmt, *args):
        # One concise line per call so you can watch the saga's peer traffic.
        print("peer-stub " + (fmt % args))


if __name__ == "__main__":
    print(f"peer-stub listening on http://localhost:{PORT}")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
