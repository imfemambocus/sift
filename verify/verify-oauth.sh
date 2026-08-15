#!/usr/bin/env bash
# drives the GitLab OAuth flow end to end over real http: availability, start, callback, the state
# rule, and a renewal the stand-in instance makes compulsory by issuing tokens that expire at once.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
WORK="${SIFT_VERIFY_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
LOG="$WORK/oauth-boot.log"
JAR="$WORK/oauth-cookies.txt"
TODOS="$WORK/oauth-todos.json"
# touch it and the stand-in instance answers slowly, which is how the callback and the read it
# starts can be told apart in time
SLOW="$WORK/oauth-slow"
BASE=http://localhost:7779
FAKE=http://127.0.0.1:7788
CLIENT_ID=sift-verify
CLIENT_SECRET=sift-verify-secret
REDIRECT="$BASE/api/sources/gitlab/oauth/callback"
PASS=0
FAIL=0
# shellcheck source=oauth-connect.sh
source "$HERE/oauth-connect.sh"

cleanup() {
  [ -n "${BOOT_PID:-}" ] && kill "$BOOT_PID" 2>/dev/null
  [ -n "${STUB_PID:-}" ] && kill "$STUB_PID" 2>/dev/null
  docker rm -f sift-oauth-db >/dev/null 2>&1
  rm -f "$SLOW"
}
trap cleanup EXIT

check() {
  if [ "$3" = "$2" ]; then printf '  ok    %-52s %s\n' "$1" "$3"; PASS=$((PASS+1))
  else printf '  FAIL  %-52s expected %s, got %s\n' "$1" "$2" "$3"; FAIL=$((FAIL+1)); fi
}

contains() {
  if [[ "$2" == *"$3"* ]]; then printf '  ok    %-52s\n' "$1"; PASS=$((PASS+1))
  else printf '  FAIL  %-52s %s does not contain %s\n' "$1" "$2" "$3"; FAIL=$((FAIL+1)); fi
}

rm -f "$JAR" "$SLOW"
KEY="$(openssl rand -base64 32)"
python3 "$HERE/make-todos.py" full "$TODOS" >/dev/null

# expires_in of one second: the very next read has to renew. that is the rule worth proving, and a
# two-hour token would let a broken refresh pass unnoticed
PORT=7788 TODOS_FILE="$TODOS" OAUTH_CLIENT_ID="$CLIENT_ID" OAUTH_CLIENT_SECRET="$CLIENT_SECRET" \
OAUTH_EXPIRES_IN=1 SLOW_FILE="$SLOW" python3 "$HERE/fake-gitlab.py" &
STUB_PID=$!
for _ in $(seq 1 30); do curl -sf -o /dev/null "$FAKE/oauth/issued" && break; sleep 1; done
echo "stub gitlab up"

docker rm -f sift-oauth-db >/dev/null 2>&1
docker run -d --rm --name sift-oauth-db -e POSTGRES_DB=sift -e POSTGRES_USER=sift \
  -e POSTGRES_PASSWORD=sift -p 5439:5432 postgres:17-alpine >/dev/null
for _ in $(seq 1 60); do docker exec sift-oauth-db pg_isready -U sift -d sift >/dev/null 2>&1 && break; sleep 1; done

SIFT_DB_URL=jdbc:postgresql://localhost:5439/sift SIFT_DB_USER=sift SIFT_DB_PASSWORD=sift \
SIFT_ENCRYPTION_KEY="$KEY" SIFT_ALLOWED_EMAIL_DOMAINS=uni.lu SIFT_PORT=7779 \
SIFT_SYNC_INITIAL_DELAY=PT1H \
SIFT_GITLAB_URL="$FAKE" SIFT_GITLAB_CLIENT_ID="$CLIENT_ID" SIFT_GITLAB_CLIENT_SECRET="$CLIENT_SECRET" \
SIFT_GITLAB_REDIRECT_URI="$REDIRECT" \
  "$ROOT/backend/gradlew" -p "$ROOT/backend" bootRun --console=plain >"$LOG" 2>&1 &
BOOT_PID=$!
for _ in $(seq 1 150); do
  grep -q "Started SiftApplication" "$LOG" 2>/dev/null && break
  grep -qE "APPLICATION FAILED TO START|FAILURE: " "$LOG" 2>/dev/null && {
    echo "backend failed:"; sed -n '/APPLICATION FAILED TO START/,/^$/p' "$LOG" | head -20; exit 1; }
  sleep 1
done
echo "backend up with an OAuth application configured"
echo

csrf() { awk '$6=="XSRF-TOKEN" {print $7}' "$JAR" | tail -1; }
api() { curl -s -c "$JAR" -b "$JAR" "$@"; }
post() { curl -s -c "$JAR" -b "$JAR" -X POST -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"; }
postcode() { curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" -X POST -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"; }
# never follow the redirect: what the callback answers is the thing under test
location() { curl -s -o /dev/null -w '%{redirect_url}' -c "$JAR" -b "$JAR" "$@"; }
statusof() { curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" "$@"; }
field() { python3 -c 'import json,sys; print(json.load(sys.stdin)['"$1"'])'; }
issued() { curl -s "$FAKE/oauth/issued" | python3 -c 'import json,sys; print(json.load(sys.stdin)["issued"])'; }

api "$BASE/actuator/health" >/dev/null
post -d '{"email":"sam@uni.lu","displayName":"Sam","password":"correct-horse-battery"}' "$BASE/api/auth/register" >/dev/null
post -d '{"email":"sam@uni.lu","password":"correct-horse-battery"}' "$BASE/api/auth/login" >/dev/null

echo "--- availability ---"
AVAIL=$(api "$BASE/api/sources/gitlab/oauth")
check "configured"   "True"    "$(echo "$AVAIL" | field '"configured"')"
# "target" rather than "instanceUrl" since the flow became a seam two sources share: gmail has no
# instance. the field names what the offer says, not what only gitlab has
check "target"       "$FAKE"   "$(echo "$AVAIL" | field '"target"')"

CONNECTORS=$(api "$BASE/api/sources/connectors")
check "both connectors offered" "['gitlab', 'gmail']" \
  "$(echo "$CONNECTORS" | python3 -c 'import json,sys; print([c["source"] for c in json.load(sys.stdin)])')"
check "gitlab not connected yet" "False" \
  "$(echo "$CONNECTORS" | python3 -c 'import json,sys; print(next(c["connected"] for c in json.load(sys.stdin) if c["source"]=="gitlab"))')"
check "gmail is not configured here" "False" \
  "$(echo "$CONNECTORS" | python3 -c 'import json,sys; print(next(c["configured"] for c in json.load(sys.stdin) if c["source"]=="gmail"))')"
check "start needs the csrf header" 403 \
  "$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" -X POST "$BASE/api/sources/gitlab/oauth/start")"

echo
echo "--- the authorize url ---"
AUTHORIZE=$(post "$BASE/api/sources/gitlab/oauth/start" | field '"authorizeUrl"')
echo "  $AUTHORIZE"
contains "points at the instance"  "$AUTHORIZE" "$FAKE/oauth/authorize"
contains "carries the client id"   "$AUTHORIZE" "client_id=$CLIENT_ID"
contains "asks for read_api only"  "$AUTHORIZE" "scope=read_api"
contains "asks for a code"         "$AUTHORIZE" "response_type=code"
contains "carries the S256 challenge" "$AUTHORIZE" "code_challenge_method=S256"
if [[ "$AUTHORIZE" == *"$CLIENT_SECRET"* ]]; then
  printf '  FAIL  %-52s the secret is in a url the browser follows\n' "the secret stays server-side"; FAIL=$((FAIL+1))
else
  printf '  ok    %-52s\n' "the secret stays server-side"; PASS=$((PASS+1))
fi
STATE=$(python3 -c 'import sys,urllib.parse as u; print(u.parse_qs(u.urlparse(sys.argv[1]).query)["state"][0])' "$AUTHORIZE")

echo
echo "--- a callback that does not match the session ---"
check "wrong state is refused" "$BASE/settings?gitlab=denied" \
  "$(location "$BASE/api/sources/gitlab/oauth/callback?code=a-code&state=not-the-state")"
check "nothing was connected" "0" "$(api "$BASE/api/sources" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
check "no token was granted"  "0" "$(issued)"

echo
echo "--- a refused approval ---"
# the state is single use. this needs a fresh start of its own
STATE2=$(python3 -c 'import sys,urllib.parse as u; print(u.parse_qs(u.urlparse(sys.argv[1]).query)["state"][0])' \
  "$(post "$BASE/api/sources/gitlab/oauth/start" | field '"authorizeUrl"')")
check "access_denied is refused" "$BASE/settings?gitlab=denied" \
  "$(location "$BASE/api/sources/gitlab/oauth/callback?error=access_denied&state=$STATE2")"

echo
echo "--- the real thing ---"
STATE3=$(python3 -c 'import sys,urllib.parse as u; print(u.parse_qs(u.urlparse(sys.argv[1]).query)["state"][0])' \
  "$(post "$BASE/api/sources/gitlab/oauth/start" | field '"authorizeUrl"')")
# from here the stand-in instance takes three seconds to answer the first call of a read. the next
# two checks are about the order of the two, never about which of them was quicker
touch "$SLOW"
CALLBACK=$(curl -s -o /dev/null -w '%{redirect_url} %{time_total}' -c "$JAR" -b "$JAR" \
  "$BASE/api/sources/gitlab/oauth/callback?code=a-real-code&state=$STATE3")
# home, not settings: home is where the source has a card, and where the offer to connect sits
check "the callback sends the browser to home" "$BASE/" "${CALLBACK% *}"
# the browser is handed back while the reading goes on. a mailbox is minutes of requests: a
# callback that read first would leave somebody watching a blank page for all of it.
check "and hands it back before the read finishes" "True" \
  "$(python3 -c 'import sys; print(float(sys.argv[1]) < 2)' "${CALLBACK##* }")"
check "the source says it is syncing"  "True" "$(api "$BASE/api/sources" | field '0]["syncing"')"
rm -f "$SLOW"
sift_await_sync gitlab
check "and stops saying so when it ends" "False" "$(api "$BASE/api/sources" | field '0]["syncing"')"
# two grants, not one: the exchange, then the first read renewing a token the stub issued with one
# second of life. with a real two-hour token that second grant would not happen.
check "the code was exchanged and then renewed" "2" "$(issued)"

SOURCE=$(api "$BASE/api/sources" | python3 -c 'import json,sys; json.dump(json.load(sys.stdin)[0], sys.stdout)')
echo "  $SOURCE" | head -c 300; echo
check "connected as OAuth" "OAUTH" "$(echo "$SOURCE" | field '"credentialType"')"
check "the first read succeeded" "OK" "$(echo "$SOURCE" | field '"status"')"
check "the feed was populated"   "8"  "$(echo "$SOURCE" | field '"itemCount"')"

echo
echo "--- replaying a spent state ---"
check "the same callback again is refused" "$BASE/settings?gitlab=denied" \
  "$(location "$BASE/api/sources/gitlab/oauth/callback?code=a-real-code&state=$STATE3")"
check "no further token was granted" "2" "$(issued)"

echo
echo "--- renewal, which the stub makes compulsory ---"
# the stub issues tokens that expire a second later and rejects every bearer but the newest. so a
# read that did not renew, or renewed and did not store the pair, cannot answer OK here.
check "check now succeeds" 200 "$(postcode "$BASE/api/sources/gitlab/sync")"
check "it renewed once more" "3" "$(issued)"
check "and again on the next read" "4" \
  "$(postcode "$BASE/api/sources/gitlab/sync" >/dev/null; issued)"
check "the source is still healthy" "OK" \
  "$(api "$BASE/api/sources" | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["status"])')"
check "the feed did not lose anything" "8" \
  "$(api "$BASE/api/sources" | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["itemCount"])')"

echo
echo "--- disconnecting ---"
check "disconnect" 204 "$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" -X DELETE \
  -H "X-XSRF-TOKEN: $(csrf)" "$BASE/api/sources/gitlab")"
check "nothing is connected" "0" "$(api "$BASE/api/sources" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
