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

sift_oauth_state() {
  python3 -c 'import sys,urllib.parse as u; print(u.parse_qs(u.urlparse(sys.argv[1]).query)["state"][0])' "$1"
}

# Authorizes Gmail and leaves it connected. Echoes the callback's http status, as the GitLab one does.
sift_connect_gmail() {
  local authorize state
  authorize=$(post "$BASE/api/sources/gmail/oauth/start" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["authorizeUrl"])')
  state=$(sift_oauth_state "$authorize")
  curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" \
    "$BASE/api/sources/gmail/oauth/callback?code=a-code&state=$state"
}

# Authorizes GitLab and leaves it connected. Echoes the callback's http status, so a caller can
# check it: 302 is success, since the callback answers a redirect and never JSON.
sift_connect_gitlab() {
  local authorize state
  authorize=$(post "$BASE/api/sources/gitlab/oauth/start" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["authorizeUrl"])')
  state=$(sift_oauth_state "$authorize")
  curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" \
    "$BASE/api/sources/gitlab/oauth/callback?code=a-code&state=$state"
}
