#!/usr/bin/env bash
# drives Gmail end to end over real http against a stand-in Google: the whole authorization, every
# message becoming a row, what is left out, threads collapsing, the seeded read state, the watermark
# that bounds every sweep after the first, a renewal the stub makes compulsory, read state travelling
# back from the mailbox, and a reconnection that reads the whole mailbox again.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
WORK="${SIFT_VERIFY_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
LOG="$WORK/gmail-boot.log"
JAR="$WORK/gmail-cookies.txt"
MAIL="$WORK/gmail-messages.json"
HIST="$WORK/gmail-history.json"
GONE="$WORK/gmail-history-gone"
BASE=http://localhost:7779
# 7790, clear of the gitlab stub on 7788 and of everything else this repo binds
FAKE=http://127.0.0.1:7790
CLIENT_ID=sift-gmail-verify
CLIENT_SECRET=sift-gmail-verify-secret
REDIRECT="$BASE/api/sources/gmail/oauth/callback"
PASS=0
FAIL=0
# for sift_await_sync: the first read after an approval runs in the background
# shellcheck source=oauth-connect.sh
source "$HERE/oauth-connect.sh"

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

rm -f "$JAR" "$HIST" "$GONE"
KEY="$(openssl rand -base64 32)"
# one base for the whole run: regenerating the fixture must not move the messages already read,
# or a message the first sweep saw would look newer than the watermark and be read a second time
NOW_MS="$(python3 -c 'import time; print(int(time.time() * 1000))')"
python3 "$HERE/make-mail.py" base "$MAIL" "$NOW_MS"

# expires_in of one second, so every read has to renew first. the stub accepts only the newest
# access token, so a renewal that was not stored fails on the very next call.
PORT=7790 MESSAGES_FILE="$MAIL" HISTORY_FILE="$HIST" HISTORY_GONE_FILE="$GONE" \
OAUTH_CLIENT_ID="$CLIENT_ID" OAUTH_CLIENT_SECRET="$CLIENT_SECRET" \
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
revoked() { curl -s "$FAKE/oauth/issued" | python3 -c 'import json,sys; print(json.load(sys.stdin)["revoked"])'; }
# the feed answers one page of groups, so ask for one big enough to hold every fixture and unwrap it
feed() { api "$BASE/api/feed?limit=500${1:+&$1}" | python3 -c 'import json,sys; json.dump(json.load(sys.stdin)["items"], sys.stdout)'; }
rows() { feed | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))'; }
len() { python3 -c 'import json,sys; print(len(json.load(sys.stdin)))'; }
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
# modify, not readonly: Sift writes the unread label back when you read a row here
contains "asks for gmail.modify"       "$AUTHORIZE" "gmail.modify"
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
# the browser was handed back before any of this existed, so the mailbox has to be read first
sift_await_sync gmail
STATUS=$(api "$BASE/api/sources" | python3 -c 'import json,sys; json.dump(json.load(sys.stdin)[0], sys.stdout)')
echo "  $STATUS"
check "connected as OAuth"        '"OAUTH"' "$(echo "$STATUS" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["credentialType"]))')"
check "the first read succeeded"  '"OK"'    "$(echo "$STATUS" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["status"]))')"
# this mailbox is smaller than one sweep, so the walk back reached its beginning straight away and
# the page has nothing to explain
check "the mailbox was read whole"    "True"    "$(echo "$STATUS" | field '"historyComplete"')"
check "and it says how far back that is" "True" \
  "$(echo "$STATUS" | python3 -c 'import json,sys; print(json.load(sys.stdin)["historyFrom"] is not None)')"
check "every read reached older mail"  "False"   "$(echo "$STATUS" | field '"historyStalled"')"
check "a mailbox can be read again"    "True"    "$(echo "$STATUS" | field '"canReread"')"
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
echo "--- what came with a message, which is half of why a mailbox is searched ---"
files() { titled "$1" attachments | tr -d "[]'"; }
check "the file is named on the row"   "grant report.pdf" "$(files 'Grant report draft')"
# an inline signature image is not something somebody attached, and naming it would say it was
check "the inline image is not one"    "grant report.pdf" "$(files 'Grant report draft')"
check "a message with no files has none" ""              "$(files 'Chart V2 review')"
check "has:attachment narrows to it"   1 "$(feed 'q=has:attachment' | len)"
# the name is in the haystack, so the file finds the message that carried it
check "the file name is searchable"    1 "$(feed 'q=pdf' | len)"
check "and a typo in it is forgiven"   1 "$(feed 'q=graant' | len)"

echo
echo "--- what a message says is searchable, past the snippet the row shows ---"
# only in the text part of one message, so nothing but the body of it can answer this
check "a word from the body finds it"  1 "$(feed 'q=projector' | len)"
check "and it is that message"         "Seminar on Thursday" "$(feed 'q=projector' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["title"])')"
check "a typo in it is forgiven too"   1 "$(feed 'q=projecter' | len)"
# the row still says what it always said: the rest of the text exists for the search alone
check "the row still shows the snippet" "Room B on the first floor." "$(titled 'Seminar on Thursday' body)"

echo
echo "--- a date scope narrows on the activity the list already shows ---"
# spans rather than dates, because the fixture is placed in hours back from the run and a calendar
# day would say something different depending on the hour the suite is started at
check "a day back leaves the older one out" 4 "$(feed 'q=after:1d' | len)"
check "and before: keeps only that one"     1 "$(feed 'q=before:1d' | len)"
check "a week back holds the whole mailbox" 5 "$(feed 'q=after:7d' | len)"
check "the two together are one window"     2 "$(feed 'q=after:7d%20before:2h' | len)"
check "a calendar date is read as well"     5 "$(feed 'q=after:2020-01-01' | len)"
check "an unreadable date finds nothing"    0 "$(feed 'q=after:2026-13-40' | len)"

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
echo "--- read state comes back from Gmail, not only out to it ---"
relabel() { python3 "$HERE/relabel-mail.py" "$MAIL" "$HIST" "$1" "$2" >/dev/null; }
check "the row is unread here to begin with" "False" "$(titled 'Chart V2 review' read)"
relabel m1 read
post "$BASE/api/sources/gmail/sync" >/dev/null
check "read in Gmail becomes read here"      "True"  "$(titled 'Chart V2 review' read)"
# only what the mailbox says changed: a sweep that swept every row read would pass the check above
check "and nothing else was touched"         "False" "$(titled 'Grant report draft' read)"

relabel m1 unread
post "$BASE/api/sources/gmail/sync" >/dev/null
check "unread in Gmail becomes unread here"  "False" "$(titled 'Chart V2 review' read)"

# google keeps its history for about a week. an instance off for longer is told to start again, and
# what is left is the mailbox itself: every message it still calls unread names the rest as read.
touch "$GONE"
relabel m4 read
post "$BASE/api/sources/gmail/sync" >/dev/null
check "a forgotten history does not fail the sweep" '"OK"' \
  "$(api "$BASE/api/sources" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)[0]["status"]))')"
check "the mailbox answers instead"          "True"  "$(titled 'Grant report draft' read)"
check "and one still unread there stays unread" "False" "$(titled 'Chart V2 review' read)"
rm -f "$GONE"

echo
echo "--- a message that leaves the mailbox loses its row, and gets it back ---"
# sift never reads the bin, so a row for a message in it disagrees with the mailbox only because of
# when it was thrown away
relabel m1 trash
post "$BASE/api/sources/gmail/sync" >/dev/null
check "the trashed message lost its row"     0 "$(absent 'Chart V2 review')"
check "and nothing else went with it"        5 "$(rows)"

relabel m1 restore
post "$BASE/api/sources/gmail/sync" >/dev/null
# nothing else would bring it back: it is older than the forward edge and under a finished floor
check "taking it back out restores the row"  1 "$(absent 'Chart V2 review')"
check "and the feed is whole again"          6 "$(rows)"

relabel m8 delete
post "$BASE/api/sources/gmail/sync" >/dev/null
check "a message deleted outright loses its row too" 0 "$(absent 'One more thing')"

echo
echo "--- reading the whole mailbox again, without disconnecting it ---"
# a message that appeared below a finished floor is never looked for again, so it is the proof that
# the reading really started over rather than carrying on from the edges it had
check "the old message is still out of the feed" 0 "$(absent 'Ancient history')"
check "the re-read was accepted" 200 "$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" \
  -X POST -H "X-XSRF-TOKEN: $(csrf)" "$BASE/api/sources/gmail/reread")"
sift_await_sync gmail
check "the message under the old floor arrived" 1 "$(absent 'Ancient history')"
check "and every row that was there is still there" 6 "$(rows)"
# the rows are keyed on the message id, so reading again fills them in rather than copying them
check "what Sift had read stays read"     "True"  "$(titled 'Grant report draft' read)"
check "and what was unread stays unread"  "False" "$(titled 'Chart V2 review' read)"
check "the mailbox is whole again"        "True" \
  "$(api "$BASE/api/sources" | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["historyComplete"])')"

echo
echo "--- disconnecting, and what survives it ---"
check "nothing was revoked before disconnecting" 0 "$(revoked)"
check "disconnect" 204 "$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" -X DELETE \
  -H "X-XSRF-TOKEN: $(csrf)" "$BASE/api/sources/gmail")"
# withdrawing the grant is the point: a deleted credential with a live token upstream is not
# disconnected, it is only forgotten
check "the grant was withdrawn at Google" 1 "$(revoked)"
check "its items went with it" 0 "$(rows)"
check "connectors offers it again" "False" \
  "$(api "$BASE/api/sources/connectors" | python3 -c 'import json,sys; print(next(c["connected"] for c in json.load(sys.stdin) if c["source"]=="gmail"))')"

# how far a mailbox has been read belongs to the connection. disconnecting deleted every row, so
# state that outlived it would claim a mailbox had been read whose rows are gone, and reconnecting
# would read only what had arrived since.
STATE2=$(python3 -c 'import sys,urllib.parse as u; print(u.parse_qs(u.urlparse(sys.argv[1]).query)["state"][0])' \
  "$(post "$BASE/api/sources/gmail/oauth/start" | field '"authorizeUrl"')")
location "$BASE/api/sources/gmail/oauth/callback?code=another-code&state=$STATE2" >/dev/null
sift_await_sync gmail
check "reconnecting reads the mailbox again" 6 "$(rows)"
# including what was under the floor of the connection that has gone, which is the whole mailbox
check "right back to its beginning"          1 "$(absent 'Ancient history')"

echo
echo "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
