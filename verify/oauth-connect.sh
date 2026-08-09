# shellcheck shell=bash
# Connecting GitLab, for every suite that needs a connected source but is not about the flow itself.
#
# An approval is the only way to connect a source, so a suite has to walk the real authorization
# code flow. It is cheap, because the stand-in instance answers the token endpoint directly and no
# approval page is involved.
#
# Source this after $BASE, $JAR and a `post` helper are defined, and start the backend with the four
# SIFT_GITLAB_* values. `sift_oauth_env` prints them, so no suite has to remember all four.
# `sift_gmail_env` does the same for Gmail, for a suite that needs both sources.

SIFT_OAUTH_CLIENT_ID=sift-verify
SIFT_OAUTH_CLIENT_SECRET=sift-verify-secret

# the env every suite's backend needs, as one line to prefix the launch with
sift_oauth_env() {
  echo "SIFT_GITLAB_URL=${FAKE:-http://127.0.0.1:7788}" \
       "SIFT_GITLAB_CLIENT_ID=$SIFT_OAUTH_CLIENT_ID" \
       "SIFT_GITLAB_CLIENT_SECRET=$SIFT_OAUTH_CLIENT_SECRET" \
       "SIFT_GITLAB_REDIRECT_URI=$BASE/api/sources/gitlab/oauth/callback"
}

# Google's half. The stand-in serves all three of its hosts, which is what SIFT_GMAIL_BASE_URL is
# for, so one address covers the token endpoint and the API.
sift_gmail_env() {
  echo "SIFT_GMAIL_CLIENT_ID=$SIFT_OAUTH_CLIENT_ID" \
       "SIFT_GMAIL_CLIENT_SECRET=$SIFT_OAUTH_CLIENT_SECRET" \
       "SIFT_GMAIL_REDIRECT_URI=$BASE/api/sources/gmail/oauth/callback" \
       "SIFT_GMAIL_BASE_URL=${GOOGLE:-http://127.0.0.1:7790}"
}

# Waits for a source's read to finish. The first read after an approval runs in the background, so
# the callback comes back before a single row exists, and a suite that asserted straight away would
# be asserting against an empty feed. It needs the `api` helper, which every suite defines.
sift_await_sync() {
  local slug="$1"
  for _ in $(seq 1 240); do
    if api "$BASE/api/sources" | python3 -c '
import json, sys
match = [s for s in json.load(sys.stdin) if s["source"] == sys.argv[1]]
sys.exit(0 if not match or not match[0]["syncing"] else 1)' "$slug"; then
      return 0
    fi
    sleep 0.5
  done
  # to stderr: a caller reads what these helpers print, and it is the http status
  echo "the first $slug read never finished" >&2
  return 1
}

sift_oauth_state() {
  python3 -c 'import sys,urllib.parse as u; print(u.parse_qs(u.urlparse(sys.argv[1]).query)["state"][0])' "$1"
}

# Authorizes Gmail, waits for its first read, and leaves it connected. Echoes the callback's http
# status, as the GitLab one does.
sift_connect_gmail() {
  local authorize state code
  authorize=$(post "$BASE/api/sources/gmail/oauth/start" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["authorizeUrl"])')
  state=$(sift_oauth_state "$authorize")
  code=$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" \
    "$BASE/api/sources/gmail/oauth/callback?code=a-code&state=$state")
  sift_await_sync gmail
  echo "$code"
}

# Authorizes GitLab, waits for its first read, and leaves it connected. Echoes the callback's http
# status, so a caller can check it: 302 is success, since the callback answers a redirect and never JSON.
sift_connect_gitlab() {
  local authorize state code
  authorize=$(post "$BASE/api/sources/gitlab/oauth/start" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["authorizeUrl"])')
  state=$(sift_oauth_state "$authorize")
  code=$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" \
    "$BASE/api/sources/gitlab/oauth/callback?code=a-code&state=$state")
  sift_await_sync gitlab
  echo "$code"
}
