#!/usr/bin/env bash
# drives Gmail end to end over real http against a stand-in Google: the whole authorization, every
# message becoming a row, what is left out, threads collapsing, the seeded read state, the watermark
# that bounds every sweep after the first, and a renewal the stub makes compulsory.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
WORK="${SIFT_VERIFY_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
LOG="$WORK/gmail-boot.log"
JAR="$WORK/gmail-cookies.txt"
MAIL="$WORK/gmail-messages.json"
BASE=http://localhost:7779
# 7790, clear of the gitlab stub on 7788 and of everything else this repo binds
FAKE=http://127.0.0.1:7790
CLIENT_ID=sift-gmail-verify
CLIENT_SECRET=sift-gmail-verify-secret
REDIRECT="$BASE/api/sources/gmail/oauth/callback"
PASS=0
FAIL=0

cleanup() {
  [ -n "${BOOT_PID:-}" ] && kill "$BOOT_PID" 2>/dev/null
  [ -n "${STUB_PID:-}" ] && kill "$STUB_PID" 2>/dev/null
  docker rm -f sift-gmail-db >/dev/null 2>&1
}
trap cleanup EXIT

check() {
  if [ "$3" = "$2" ]; then printf '  ok    %-54s %s\n' "$1" "$3"; PASS=$((PASS+1))
  else printf '  FAIL  %-54s expected %s, got %s\n' "$1" "$2" "$3"; FAIL=$((FAIL+1)); fi
}

contains() {
  if [[ "$2" == *"$3"* ]]; then printf '  ok    %-54s\n' "$1"; PASS=$((PASS+1))
  else printf '  FAIL  %-54s %s does not contain %s\n' "$1" "$2" "$3"; FAIL=$((FAIL+1)); fi
}

rm -f "$JAR"
KEY="$(openssl rand -base64 32)"
# one base for the whole run: regenerating the fixture must not move the messages already read,
# or a message the first sweep saw would look newer than the watermark and be read a second time
NOW_MS="$(python3 -c 'import time; print(int(time.time() * 1000))')"
python3 "$HERE/make-mail.py" base "$MAIL" "$NOW_MS"

# expires_in of one second, so every read has to renew first. the stub accepts only the newest
# access token, so a renewal that was not stored fails on the very next call.
PORT=7790 MESSAGES_FILE="$MAIL" OAUTH_CLIENT_ID="$CLIENT_ID" OAUTH_CLIENT_SECRET="$CLIENT_SECRET" \
OAUTH_EXPIRES_IN=1 python3 "$HERE/fake-google.py" &
STUB_PID=$!
for _ in $(seq 1 30); do curl -sf -o /dev/null "$FAKE/oauth/issued" && break; sleep 1; done
echo "stub google up"

docker rm -f sift-gmail-db >/dev/null 2>&1
docker run -d --rm --name sift-gmail-db -e POSTGRES_DB=sift -e POSTGRES_USER=sift \
  -e POSTGRES_PASSWORD=sift -p 5439:5432 postgres:17-alpine >/dev/null
for _ in $(seq 1 60); do docker exec sift-gmail-db pg_isready -U sift -d sift >/dev/null 2>&1 && break; sleep 1; done

SIFT_DB_URL=jdbc:postgresql://localhost:5439/sift SIFT_DB_USER=sift SIFT_DB_PASSWORD=sift \
SIFT_ENCRYPTION_KEY="$KEY" SIFT_ALLOWED_EMAIL_DOMAINS=uni.lu SIFT_PORT=7779 \
SIFT_SYNC_INITIAL_DELAY=PT1H \
SIFT_GMAIL_CLIENT_ID="$CLIENT_ID" SIFT_GMAIL_CLIENT_SECRET="$CLIENT_SECRET" \
SIFT_GMAIL_REDIRECT_URI="$REDIRECT" SIFT_GMAIL_BASE_URL="$FAKE" \
  "$ROOT/backend/gradlew" -p "$ROOT/backend" bootRun --console=plain >"$LOG" 2>&1 &
BOOT_PID=$!
for _ in $(seq 1 150); do
  grep -q "Started SiftApplication" "$LOG" 2>/dev/null && break
  grep -qE "APPLICATION FAILED TO START|FAILURE: " "$LOG" 2>/dev/null && {
    echo "backend failed:"; sed -n '/APPLICATION FAILED TO START/,/^$/p' "$LOG" | head -20; exit 1; }
  sleep 1
done
echo "backend up with a Google client configured"
echo

csrf() { awk '$6=="XSRF-TOKEN" {print $7}' "$JAR" | tail -1; }
api() { curl -s -c "$JAR" -b "$JAR" "$@"; }
post() { curl -s -c "$JAR" -b "$JAR" -X POST -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"; }
# never follow the redirect: what the callback answers is the thing under test
location() { curl -s -o /dev/null -w '%{redirect_url}' -c "$JAR" -b "$JAR" "$@"; }
field() { python3 -c 'import json,sys; print(json.load(sys.stdin)['"$1"'])'; }
issued() { curl -s "$FAKE/oauth/issued" | python3 -c 'import json,sys; print(json.load(sys.stdin)["issued"])'; }
# the feed answers one page of groups, so ask for one big enough to hold every fixture and unwrap it
feed() { api "$BASE/api/feed?limit=500${1:+&$1}" | python3 -c 'import json,sys; json.dump(json.load(sys.stdin)["items"], sys.stdout)'; }
rows() { feed | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))'; }
# every value of a field, sorted, so an assertion does not depend on the order of the list
values() { feed | python3 -c "import json,sys; print(' '.join(sorted(str(i['$1']) for i in json.load(sys.stdin))))"; }
# one field of the row whose title matches
titled() { feed | python3 -c "import json,sys; print(next(str(i['$2']) for i in json.load(sys.stdin) if i['title']=='$1'))"; }

api "$BASE/actuator/health" >/dev/null
post -d '{"email":"isfaaq@uni.lu","displayName":"Isfaaq","password":"correct-horse-battery"}' "$BASE/api/auth/register" >/dev/null
post -d '{"email":"isfaaq@uni.lu","password":"correct-horse-battery"}' "$BASE/api/auth/login" >/dev/null

echo "--- availability, and what Home is offered ---"
AVAIL=$(api "$BASE/api/sources/gmail/oauth")
check "configured"        "True"                      "$(echo "$AVAIL" | field '"configured"')"
# the mailbox, never the API host: it is what a person recognises on the settings card
check "target is the mailbox" "https://mail.google.com" "$(echo "$AVAIL" | field '"target"')"
CONNECTORS=$(api "$BASE/api/sources/connectors")
check "gmail is offered"  "True" \
  "$(echo "$CONNECTORS" | python3 -c 'import json,sys; print(next(c["configured"] for c in json.load(sys.stdin) if c["source"]=="gmail"))')"
check "and not connected yet" "False" \
  "$(echo "$CONNECTORS" | python3 -c 'import json,sys; print(next(c["connected"] for c in json.load(sys.stdin) if c["source"]=="gmail"))')"
# gitlab has no application here, so the same list proves the two sets of values are independent
check "gitlab is offered but unconfigured" "False" \
  "$(echo "$CONNECTORS" | python3 -c 'import json,sys; print(next(c["configured"] for c in json.load(sys.stdin) if c["source"]=="gitlab"))')"

echo
echo "--- the authorize url ---"
AUTHORIZE=$(post "$BASE/api/sources/gmail/oauth/start" | field '"authorizeUrl"')
echo "  $AUTHORIZE"
contains "points at Google"            "$AUTHORIZE" "$FAKE/o/oauth2/v2/auth"
contains "carries the client id"       "$AUTHORIZE" "client_id=$CLIENT_ID"
contains "asks for gmail.readonly"     "$AUTHORIZE" "gmail.readonly"
contains "carries the S256 challenge"  "$AUTHORIZE" "code_challenge_method=S256"
# without both of these Google issues an access token and no way ever to renew it
contains "asks for offline access"     "$AUTHORIZE" "access_type=offline"
contains "forces the consent screen"   "$AUTHORIZE" "prompt=consent"
# the secret authorizes the exchange, and it must never travel through the browser
if [[ "$AUTHORIZE" != *"$CLIENT_SECRET"* ]]; then
  printf '  ok    %-54s\n' "the secret stays server-side"; PASS=$((PASS+1))
else
  printf '  FAIL  %-54s the secret is in the authorize url\n' "the secret stays server-side"; FAIL=$((FAIL+1))
fi

echo
echo "--- a callback that does not match the session ---"
check "wrong state is refused" "$BASE/settings?gmail=denied" \
  "$(location "$BASE/api/sources/gmail/oauth/callback?code=a-code&state=not-the-state")"
check "nothing was connected" 0 "$(api "$BASE/api/sources" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
check "no token was granted"  0 "$(issued)"

echo
echo "--- the real thing ---"
STATE=$(python3 -c 'import sys,urllib.parse as u; print(u.parse_qs(u.urlparse(sys.argv[1]).query)["state"][0])' \
  "$(post "$BASE/api/sources/gmail/oauth/start" | field '"authorizeUrl"')")
# home, not settings: home is where the source has a card, and where the offer to connect sits
check "the callback sends the browser to home" "$BASE/" \
  "$(location "$BASE/api/sources/gmail/oauth/callback?code=a-real-code&state=$STATE")"
STATUS=$(api "$BASE/api/sources" | python3 -c 'import json,sys; json.dump(json.load(sys.stdin)[0], sys.stdout)')
echo "  $STATUS"
check "connected as OAuth"        '"OAUTH"' "$(echo "$STATUS" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["credentialType"]))')"
check "the first read succeeded"  '"OK"'    "$(echo "$STATUS" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["status"]))')"
check "connectors says connected" "True" \
  "$(api "$BASE/api/sources/connectors" | python3 -c 'import json,sys; print(next(c["connected"] for c in json.load(sys.stdin) if c["source"]=="gmail"))')"

echo
echo "--- every message becomes a row, and only a message does ---"
# five of the seven: the other two are your own draft and spam. mail you wrote is a row.
check "five rows from seven messages" 5 "$(rows)"
check "every row is mail" "mail_received mail_received mail_received mail_received mail_sent" \
  "$(values kind)"
absent() { feed | python3 -c "import json,sys; print(sum(1 for i in json.load(sys.stdin) if i['title']=='$1'))"; }
check "your own draft raised nothing" 0 "$(absent 'Half written')"
check "spam raised nothing"           0 "$(absent 'You have won')"

echo
echo "--- mail you sent is a row about whoever received it ---"
# the search is the reason mail is in Sift, so an archive that could not find what you wrote
# would miss one of the most common reasons to search a mailbox at all
check "sent mail is a row"        1                "$(feed | python3 -c 'import json,sys; print(sum(1 for i in json.load(sys.stdin) if i["kind"]=="mail_sent"))')"
check "and it is named for its recipient" "Ada Lovelace" "$(titled 'My own reply' actorName)"
check "and carries their address"        "ada@uni.lu"   "$(titled 'My own reply' contextLabel)"

echo
echo "--- what a row carries ---"
check "the subject is the title"    "Chart V2 review"  "$(titled 'Chart V2 review' title)"
check "the sender's display name"   "Ada Lovelace"     "$(titled 'Chart V2 review' actorName)"
# the address goes where a project path goes: it reads as context, and the search then finds it
check "the sender's address"        "ada@uni.lu"       "$(titled 'Chart V2 review' contextLabel)"
check "a sender with no name falls back to the address" "grete@uni.lu" "$(titled 'Seminar on Thursday' actorName)"
check "the row opens that message"  "https://mail.google.com/mail/u/0/#all/m1" "$(titled 'Chart V2 review' url)"
# a message happened once, so a later sweep not listing it must never mark it done
check "nothing is resolved by absence" "False False False False False" "$(values resolved)"

echo
echo "--- a conversation is one entry, not one per message ---"
# the thread id, never the url: every message lives at the same path and only the fragment differs,
# so the rule that strips the fragment would make one group of the whole mailbox
check "the two replies share a group" 1 \
  "$(feed | python3 -c 'import json,sys; f=json.load(sys.stdin); print(len({i["groupKey"] for i in f if i["title"].endswith("Chart V2 review")}))')"
check "and it is keyed on the thread" "gmail:thread:t1" "$(titled 'Chart V2 review' groupKey)"
check "a different thread is a different group" "gmail:thread:t2" "$(titled 'Seminar on Thursday' groupKey)"

echo
echo "--- the mailbox's own read state seeds the row, once ---"
check "read in Gmail arrives read"    "True"  "$(titled 'Seminar on Thursday' read)"
check "unread in Gmail arrives unread" "False" "$(titled 'Chart V2 review' read)"
ID=$(titled 'Seminar on Thursday' id)
curl -s -o /dev/null -c "$JAR" -b "$JAR" -X PATCH -H 'Content-Type: application/json' \
  -H "X-XSRF-TOKEN: $(csrf)" -d '{"read":false}' "$BASE/api/feed/$ID"
check "marked unread here" "False" "$(titled 'Seminar on Thursday' read)"

echo
echo "--- the watermark: every sweep after the first reads only what is newer ---"
python3 "$HERE/make-mail.py" plus-old "$MAIL" "$NOW_MS"
post "$BASE/api/sources/gmail/sync" >/dev/null
check "the new message arrived"    6 "$(rows)"
check "it is the new one"          "One more thing" "$(titled 'One more thing' title)"
# the walk back reached the beginning of this mailbox on the first read, so nothing looks below
# the floor again and a message that appears down there afterwards stays out
check "a message below a finished floor stays out" 0 "$(absent 'Ancient history')"
# and a sweep must not undo a decision made here
check "Sift still owns the read state" "False" "$(titled 'Seminar on Thursday' read)"

echo
echo "--- renewal, which the stub makes compulsory ---"
BEFORE=$(issued)
post "$BASE/api/sources/gmail/sync" >/dev/null
AFTER=$(issued)
check "it renewed again"  "$((BEFORE + 1))" "$AFTER"
# the stub accepts only its newest access token, so a read succeeding is the proof it was stored
check "the read still succeeded" '"OK"' \
  "$(api "$BASE/api/sources" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)[0]["status"]))')"
# and Google sends no refresh_token on a renewal, so keeping the stored one is what makes this work
check "the feed did not lose anything" 6 "$(rows)"

echo
echo "--- disconnecting, and what survives it ---"
check "disconnect" 204 "$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" -X DELETE \
  -H "X-XSRF-TOKEN: $(csrf)" "$BASE/api/sources/gmail")"
check "its items went with it" 0 "$(rows)"
check "connectors offers it again" "False" \
  "$(api "$BASE/api/sources/connectors" | python3 -c 'import json,sys; print(next(c["connected"] for c in json.load(sys.stdin) if c["source"]=="gmail"))')"

# the watermark is keyed on the user and not on the credential, so authorizing again picks up where
# it stopped rather than announcing the whole window a second time
STATE2=$(python3 -c 'import sys,urllib.parse as u; print(u.parse_qs(u.urlparse(sys.argv[1]).query)["state"][0])' \
  "$(post "$BASE/api/sources/gmail/oauth/start" | field '"authorizeUrl"')")
location "$BASE/api/sources/gmail/oauth/callback?code=another-code&state=$STATE2" >/dev/null
check "reconnecting re-announces nothing" 0 "$(rows)"

echo
echo "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
