#!/usr/bin/env python3
"""A stand-in GitLab instance: /api/v4/user, a paginated /api/v4/todos, merge requests, issues,
threads, the caller's own activity feed, and the OAuth token endpoint.

Every dataset is re-read from disk on every request, so a test can change what the instance returns
between phases without restarting anything.
"""
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, quote

PORT = int(os.environ.get("PORT", "7788"))
TODOS_FILE = os.environ["TODOS_FILE"]
MRS_FILE = os.environ.get("MRS_FILE")
ISSUES_FILE = os.environ.get("ISSUES_FILE")
DISCUSSIONS_FILE = os.environ.get("DISCUSSIONS_FILE")
EVENTS_FILE = os.environ.get("EVENTS_FILE")
# touching this file makes the instance reject every token, standing in for an approval withdrawn
REVOKE_FILE = os.environ.get("REVOKE_FILE", "/nonexistent")

OAUTH_CLIENT_ID = os.environ.get("OAUTH_CLIENT_ID", "sift-verify")
OAUTH_CLIENT_SECRET = os.environ.get("OAUTH_CLIENT_SECRET", "sift-verify-secret")
# seconds. set it to 1 and every sweep has to renew, which is how the refresh is exercised.
OAUTH_EXPIRES_IN = int(os.environ.get("OAUTH_EXPIRES_IN", "7200"))

# One chain per authorization, exactly as a real server keeps them. Renewing advances that chain
# and kills its own previous pair; it leaves every other chain alone. Both halves matter: a spent
# token must stop working, and one user authorizing must not sign another user out.
OAUTH = {
    "issued": 0,
    "revoked": 0,   # how many grants were withdrawn, which only a disconnect does
    "valid_access": set(),      # every access token still usable, across all chains
    "refresh_chain": {},        # refresh token -> the chain it belongs to
    "chain_access": {},         # chain -> the access token currently issued on it
}
OAUTH_LOCK = threading.Lock()


def _issue(chain):
    """Puts a fresh pair on a chain and retires whatever that chain held before."""
    OAUTH["issued"] += 1
    serial = OAUTH["issued"]
    OAUTH["valid_access"].discard(OAUTH["chain_access"].get(chain))
    access = f"oauth-access-{serial}"
    refresh = f"oauth-refresh-{serial}"
    OAUTH["valid_access"].add(access)
    OAUTH["chain_access"][chain] = access
    OAUTH["refresh_chain"][refresh] = chain
    return access, refresh

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

    def _authorized(self):
        """Only the newest OAuth access token, as a bearer. A real GitLab is this strict too."""
        if os.path.exists(REVOKE_FILE):
            return False
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return False
        with OAUTH_LOCK:
            return header[len("Bearer "):] in OAUTH["valid_access"]

    def do_POST(self):
        parsed = urlparse(self.path)

        # withdrawing the grant, which only a disconnect does. gitlab answers 200 either way.
        if parsed.path == "/oauth/revoke":
            length = int(self.headers.get("Content-Length", "0"))
            self.rfile.read(length)
            with OAUTH_LOCK:
                OAUTH["revoked"] += 1
            self._send(200, {})
            return

        if parsed.path != "/oauth/token":
            self._send(404, {"message": "404 Not Found"})
            return

        length = int(self.headers.get("Content-Length", "0"))
        form = parse_qs(self.rfile.read(length).decode())

        def field(name):
            return form.get(name, [""])[0]

        if field("client_id") != OAUTH_CLIENT_ID or field("client_secret") != OAUTH_CLIENT_SECRET:
            self._send(401, {"error": "invalid_client"})
            return

        grant = field("grant_type")
        with OAUTH_LOCK:
            if grant == "authorization_code":
                # PKCE is not optional here: a flow that forgets the verifier must fail loudly
                if not field("code") or not field("code_verifier") or not field("redirect_uri"):
                    self._send(400, {"error": "invalid_request"})
                    return
                # every approval starts a chain of its own, so two users do not share one token
                chain = OAUTH["issued"] + 1
            elif grant == "refresh_token":
                # popped, so presenting the same refresh token twice is refused the second time
                chain = OAUTH["refresh_chain"].pop(field("refresh_token"), None)
                if chain is None:
                    self._send(400, {"error": "invalid_grant"})
                    return
            else:
                self._send(400, {"error": "unsupported_grant_type"})
                return

            access, refresh = _issue(chain)

        self._send(200, {
            "access_token": access,
            "refresh_token": refresh,
            "token_type": "bearer",
            "expires_in": OAUTH_EXPIRES_IN,
            "scope": "read_api",
        })

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)

        # test-only introspection: how many times a token has been granted, which is the only way a
        # suite can see that a renewal actually happened rather than assuming it did
        if parsed.path == "/oauth/issued":
            with OAUTH_LOCK:
                self._send(200, {"issued": OAUTH["issued"], "revoked": OAUTH["revoked"]})
            return

        # the approval page, which approves at once and sends the browser straight back. it is what
        # lets the browser suite click the real button, and what makes the whole flow work locally
        # with no application registered anywhere.
        if parsed.path == "/oauth/authorize":
            redirect = query.get("redirect_uri", [""])[0]
            state = query.get("state", [""])[0]
            back = f"{redirect}?code=a-code&state={quote(state)}"
            self.send_response(302)
            self.send_header("Location", back)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if not self._authorized():
            self._send(401, {"message": "401 Unauthorized"})
            return

        if parsed.path == "/api/v4/user":
            self._send(200, USER)
            return

        # unconfigured routes answer with an empty list, not 404: the app reads issues and
        # discussions on every sweep, and a 404 would fail syncs in tests that do not care
        if parsed.path == "/api/v4/issues":
            groups = load(ISSUES_FILE)
            self._send(200, groups.get(query.get("scope", [""])[0], []), {"X-Next-Page": ""})
            return

        # the caller's own activity, which is the only way to find something they only commented on
        if parsed.path == "/api/v4/events":
            events = load(EVENTS_FILE) or []
            action = query.get("action", [""])[0]
            # GitLab spells it back as "commented on", so the filter is a contains, as it were
            if action:
                events = [e for e in events if action in (e.get("action_name") or "")]
            self._send(200, events, {"X-Next-Page": ""})
            return

        if parsed.path.startswith("/api/v4/projects/") and parsed.path.endswith("/discussions"):
            parts = parsed.path.split("/")
            key = f"{parts[5]}:{parts[4]}:{parts[6]}"
            self._send(200, load(DISCUSSIONS_FILE).get(key, []), {"X-Next-Page": ""})
            return

        parts = parsed.path.split("/")

        # a project's merge requests or issues narrowed to iids[], which is how the app turns the
        # resources found in the activity feed into one request per project. answered from the same
        # "single" fixture as the lookup below, keyed project:iid.
        if len(parts) == 6 and parts[3] == "projects" and parts[5] in ("merge_requests", "issues"):
            fixture = load(MRS_FILE if parts[5] == "merge_requests" else ISSUES_FILE)
            wanted_state = query.get("state", [""])[0]
            found = []
            for iid in query.get("iids[]", []):
                record = fixture.get("single", {}).get(f"{parts[4]}:{iid}")
                if record is None or (wanted_state and record.get("state") != wanted_state):
                    continue
                found.append(record)
            self._send(200, found, {"X-Next-Page": ""})
            return

        # one merge request by project and iid, which is how the app asks what became of something
        # that left the opened lists. absent from the fixture means 404, ie. gone or not visible.
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
