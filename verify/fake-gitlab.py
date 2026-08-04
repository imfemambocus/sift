#!/usr/bin/env python3
"""A stand-in GitLab instance: /api/v4/user, a paginated /api/v4/todos, merge requests and threads.

The todo dataset is re-read from disk on every request, so the test can change what the instance
returns between phases without restarting anything.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(os.environ.get("PORT", "7788"))
TODOS_FILE = os.environ["TODOS_FILE"]
MRS_FILE = os.environ.get("MRS_FILE")
ISSUES_FILE = os.environ.get("ISSUES_FILE")
DISCUSSIONS_FILE = os.environ.get("DISCUSSIONS_FILE")
# touching this file makes the instance reject every token, standing in for a revoked PAT
REVOKE_FILE = os.environ.get("REVOKE_FILE", "/nonexistent")
GOOD_TOKEN = "good-token"

USER = {
    "id": 42,
    "username": "isfaaq",
    "name": "Isfaaq M. F. Emambocus",
    "avatar_url": "https://gitlab.example.org/avatar/42",
    "web_url": "https://gitlab.example.org/isfaaq",
}


def load(path):
    """Re-read on every request, so a test can change fixtures between sweeps."""
    if not path or not os.path.exists(path):
        return {}
    with open(path) as handle:
        return json.load(handle)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, status, payload, extra_headers=None):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for key, value in (extra_headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if os.path.exists(REVOKE_FILE) or self.headers.get("PRIVATE-TOKEN") != GOOD_TOKEN:
            self._send(401, {"message": "401 Unauthorized"})
            return

        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)

        if parsed.path == "/api/v4/user":
            self._send(200, USER)
            return

        # unconfigured routes answer with an empty list, not 404: the app now reads issues and
        # discussions on every sweep, and a 404 would fail syncs in tests that do not care
        if parsed.path == "/api/v4/issues":
            groups = load(ISSUES_FILE)
            self._send(200, groups.get(query.get("scope", [""])[0], []), {"X-Next-Page": ""})
            return

        if parsed.path.startswith("/api/v4/projects/") and parsed.path.endswith("/discussions"):
            parts = parsed.path.split("/")
            key = f"{parts[5]}:{parts[4]}:{parts[6]}"
            self._send(200, load(DISCUSSIONS_FILE).get(key, []), {"X-Next-Page": ""})
            return

        # one merge request by project and iid, which is how the app asks what became of something
        # that left the opened lists. absent from the fixture means 404, ie. gone or not visible.
        parts = parsed.path.split("/")
        if len(parts) == 7 and parts[3] == "projects" and parts[5] == "merge_requests":
            single = load(MRS_FILE).get("single", {}).get(f"{parts[4]}:{parts[6]}")
            if single is None:
                self._send(404, {"message": "404 Not Found"})
            else:
                self._send(200, single)
            return

        if parsed.path == "/api/v4/merge_requests":
            groups = load(MRS_FILE)
            # which list depends on how the caller asked, exactly as GitLab would
            scope = query.get("scope", [""])[0]
            if scope == "assigned_to_me":
                data = groups.get("assigned", [])
            elif scope == "created_by_me":
                data = groups.get("authored", [])
            elif "reviewer_id" in query:
                data = groups.get("review_requested", [])
            else:
                data = []
            self._send(200, data, {"X-Next-Page": ""})
            return

        if parsed.path == "/api/v4/todos":
            with open(TODOS_FILE) as handle:
                todos = json.load(handle)

            per_page = int(query.get("per_page", ["20"])[0])
            page = int(query.get("page", ["1"])[0])
            start = (page - 1) * per_page
            chunk = todos[start:start + per_page]

            headers = {"X-Total": str(len(todos)), "X-Page": str(page)}
            if start + per_page < len(todos):
                headers["X-Next-Page"] = str(page + 1)
            else:
                headers["X-Next-Page"] = ""
            self._send(200, chunk, headers)
            return

        self._send(404, {"message": "404 Not Found"})


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
