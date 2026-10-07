#!/usr/bin/env python3
"""Minimal endpoint for trying out Saftladen: prints every report it receives.

    python3 tools/echo-server.py 8080

Then point the extension at http://<your-machine>:8080/ (the Karoo must be able to
reach it, so use the LAN address, not localhost).
"""

import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802 - name required by BaseHTTPRequestHandler
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        print(f"--- {self.path} from {self.client_address[0]}")
        for header, value in self.headers.items():
            print(f"{header}: {value}")
        try:
            print(json.dumps(json.loads(body), indent=2))
        except json.JSONDecodeError:
            print(body)
        print(flush=True)
        self.send_response(204)
        self.end_headers()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    print(f"listening on 0.0.0.0:{port}", flush=True)
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()
