#!/usr/bin/env python3
"""A stand-in Google: the OAuth token endpoint, and the three Gmail calls Sift makes.

One server answers all three of Google's hosts, because `sift.gmail.base-url` overrides all three at
once. So `/token` is here, and so is `/gmail/v1/users/me/...`.

It is deliberately strict, in the ways that catch a real mistake:

  * it demands the client id and the client secret on every grant
  * it refuses an authorization_code grant with no code_verifier, so a flow that drops PKCE fails
  * it refuses a refresh_token grant that is not the newest one it issued
  * it accepts only the newest access token as a bearer, so a renewal that was not stored fails on
    the very next call rather than passing quietly
  * it never sends refresh_token on a renewal, which is what Google does, so a client that stores
    what the response carried loses the connection an hour later

It also honours `after:` in the search itself. The watermark is the part of the mail adapter most
worth proving, and a stub that answered a fixed list would pass a test that only checked the rows.

MESSAGES_FILE is re-read on every request, so a test can deliver mail between sweeps.
"""
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(os.environ.get("PORT", "7790"))
MESSAGES_FILE = os.environ["MESSAGES_FILE"]
MAILBOX = os.environ.get("MAILBOX", "isfaaq@uni.lu")
# touching this file makes Google reject every token, standing in for a withdrawn approval
REVOKE_FILE = os.environ.get("REVOKE_FILE", "/nonexistent")

OAUTH_CLIENT_ID = os.environ.get("OAUTH_CLIENT_ID", "sift-gmail-verify")
OAUTH_CLIENT_SECRET = os.environ.get("OAUTH_CLIENT_SECRET", "sift-gmail-verify-secret")
# seconds. set it to 1 and every sweep has to renew, which is how the refresh is exercised.
OAUTH_EXPIRES_IN = int(os.environ.get("OAUTH_EXPIRES_IN", "3600"))

OAUTH = {
    "issued": 0,
    "access": None,     # only the newest is accepted, so a stale token fails loudly
    "refresh": None,    # long-lived: a renewal does not rotate it, which is Google's behaviour
}
OAUTH_LOCK = threading.Lock()


def _issue(mint_refresh):
    """A fresh access token. The refresh token is minted only by a first consent.

    Google does not rotate it and does not resend it, so a client keeps the one it already has for
    the life of the grant. A stub that rotated it here would fail a correct client on its second
    renewal, which is the opposite of what this suite is for.
    """
    OAUTH["issued"] += 1
    serial = OAUTH["issued"]
    OAUTH["access"] = f"gmail-access-{serial}"
    if mint_refresh:
        OAUTH["refresh"] = f"gmail-refresh-{serial}"
    return OAUTH["access"], OAUTH["refresh"]


def load():
    """Re-read on every request, so a test can deliver mail between sweeps."""
    if not os.path.exists(MESSAGES_FILE):
        return []
    with open(MESSAGES_FILE) as handle:
        return json.load(handle)


def visible(messages):
    """What the list endpoint may return. Gmail leaves spam and trash out unless asked for them."""
    return [m for m in messages if not ({"SPAM", "TRASH"} & set(m.get("labelIds", [])))]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        if os.path.exists(REVOKE_FILE):
            return False
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return False
        with OAUTH_LOCK:
            return header[len("Bearer "):] == OAUTH["access"]

    def do_POST(self):
        if urlparse(self.path).path != "/token":
            self._send(404, {"error": "not_found"})
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
                access, refresh = _issue(True)
                # only the first consent carries one, which is exactly Google's behaviour
                self._send(200, {
                    "access_token": access,
                    "refresh_token": refresh,
                    "token_type": "Bearer",
                    "expires_in": OAUTH_EXPIRES_IN,
                    "scope": "https://www.googleapis.com/auth/gmail.readonly",
                })
                return

            if grant != "refresh_token":
                self._send(400, {"error": "unsupported_grant_type"})
                return

            if field("refresh_token") != OAUTH["refresh"]:
                self._send(400, {"error": "invalid_grant"})
                return

            access, _ = _issue(False)

        # no refresh_token in a renewal. a client that stores what this carried has stored nothing.
        self._send(200, {
            "access_token": access,
            "token_type": "Bearer",
            "expires_in": OAUTH_EXPIRES_IN,
            "scope": "https://www.googleapis.com/auth/gmail.readonly",
        })

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)

        # test-only introspection: how many times a token has been granted, which is the only way a
        # suite can see that a renewal actually happened rather than assuming it did
        if parsed.path == "/oauth/issued":
            with OAUTH_LOCK:
                self._send(200, {"issued": OAUTH["issued"]})
            return

        if not self._authorized():
            self._send(401, {"error": {"code": 401, "message": "Invalid Credentials"}})
            return

        if parsed.path == "/gmail/v1/users/me/profile":
            self._send(200, {"emailAddress": MAILBOX, "messagesTotal": len(load())})
            return

        if parsed.path == "/gmail/v1/users/me/messages":
            self._send(200, self._list(query))
            return

        if parsed.path.startswith("/gmail/v1/users/me/messages/"):
            wanted = parsed.path.rsplit("/", 1)[-1]
            for message in load():
                if message["id"] == wanted:
                    self._send(200, message)
                    return
            self._send(404, {"error": {"code": 404, "message": "Not Found"}})
            return

        self._send(404, {"error": {"code": 404, "message": "Not Found"}})

    def _list(self, query):
        """Newest first, honouring `after:` and the page size, with a real page token."""
        after = 0
        for term in query.get("q", [""])[0].split():
            if term.startswith("after:"):
                after = int(term[len("after:"):])

        matching = sorted(
            (m for m in visible(load()) if int(m["internalDate"]) // 1000 > after),
            key=lambda m: int(m["internalDate"]),
            reverse=True,
        )

        size = int(query.get("maxResults", ["100"])[0])
        start = int(query.get("pageToken", ["0"])[0])
        chunk = matching[start:start + size]

        body = {"messages": [{"id": m["id"], "threadId": m["threadId"]} for m in chunk],
                "resultSizeEstimate": len(matching)}
        if start + size < len(matching):
            body["nextPageToken"] = str(start + size)
        return body


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
