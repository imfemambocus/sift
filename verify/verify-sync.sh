#!/usr/bin/env bash
# drives connect + sync + feed against a stand-in GitLab, over real http, on a real postgres
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# scratch: logs, cookies and the fixtures a run mutates. kept out of the repo.
WORK="${SIFT_VERIFY_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
LOG="$WORK/sync-boot.log"
JAR="$WORK/sync-cookies.txt"
TODOS="$WORK/todos.json"
BASE=http://localhost:7779
FAKE=http://127.0.0.1:7788
# shellcheck source=oauth-connect.sh
source "$HERE/oauth-connect.sh"
PASS=0
FAIL=0

cleanup() {
  [ -n "${BOOT_PID:-}" ] && kill "$BOOT_PID" 2>/dev/null
  [ -n "${STUB_PID:-}" ] && kill "$STUB_PID" 2>/dev/null
  docker rm -f sift-sync-db >/dev/null 2>&1
}
trap cleanup EXIT

check() {
  if [ "$3" = "$2" ]; then printf '  ok    %-52s %s\n' "$1" "$3"; PASS=$((PASS+1))
  else printf '  FAIL  %-52s expected %s, got %s\n' "$1" "$2" "$3"; FAIL=$((FAIL+1)); fi
}

rm -f "$JAR" "$WORK/revoked"
KEY="$(openssl rand -base64 32)"
python3 "$HERE/make-todos.py" full "$TODOS" >/dev/null

PORT=7788 TODOS_FILE="$TODOS" REVOKE_FILE="$WORK/revoked" python3 "$HERE/fake-gitlab.py" &
STUB_PID=$!
for _ in $(seq 1 30); do curl -sf -o /dev/null "$FAKE/oauth/issued" && break; sleep 1; done
echo "stub gitlab up"

docker rm -f sift-sync-db >/dev/null 2>&1
docker run -d --rm --name sift-sync-db -e POSTGRES_DB=sift -e POSTGRES_USER=sift \
  -e POSTGRES_PASSWORD=sift -p 5439:5432 postgres:17-alpine >/dev/null
for _ in $(seq 1 60); do docker exec sift-sync-db pg_isready -U sift -d sift >/dev/null 2>&1 && break; sleep 1; done

SIFT_DB_URL=jdbc:postgresql://localhost:5439/sift SIFT_DB_USER=sift SIFT_DB_PASSWORD=sift \
SIFT_ENCRYPTION_KEY="$KEY" SIFT_ALLOWED_EMAIL_DOMAINS=uni.lu SIFT_PORT=7779 \
SIFT_SYNC_INITIAL_DELAY=PT1H \
  env $(sift_oauth_env) "$ROOT/backend/gradlew" -p "$ROOT/backend" bootRun --console=plain >"$LOG" 2>&1 &
BOOT_PID=$!
for _ in $(seq 1 150); do
  grep -q "Started SiftApplication" "$LOG" 2>/dev/null && break
  grep -qE "APPLICATION FAILED TO START|FAILURE: " "$LOG" 2>/dev/null && {
    echo "backend failed:"; sed -n '/APPLICATION FAILED TO START/,/^$/p' "$LOG" | head -20; exit 1; }
  sleep 1
done
echo "backend up (scheduled sweep pushed out to an hour so only explicit syncs run)"
echo

csrf() { awk '$6=="XSRF-TOKEN" {print $7}' "$JAR" | tail -1; }
api() { curl -s -c "$JAR" -b "$JAR" "$@"; }
# the feed is paged over groups. a suite asks for one page large enough to hold every fixture
# and unwraps the items. `limit` counts groups; 500 is the server's own ceiling.
feed() { api "$BASE/api/feed?limit=500${1:+&$1}" | python3 -c 'import json,sys; json.dump(json.load(sys.stdin)["items"], sys.stdout)'; }
code() { curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" "$@"; }
post() { curl -s -c "$JAR" -b "$JAR" -X POST -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"; }
postcode() { curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" -X POST -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"; }

api "$BASE/actuator/health" >/dev/null
post -d '{"email":"sam@uni.lu","displayName":"Sam","password":"correct-horse-battery"}' "$BASE/api/auth/register" >/dev/null
post -d '{"email":"sam@uni.lu","password":"correct-horse-battery"}' "$BASE/api/auth/login" >/dev/null

# the connect endpoint is gone: authorizing is the only way in, and verify-oauth.sh is where the
# flow itself is checked. an unknown source name still has its own path and keeps its check.
echo "--- an unknown source is still refused ---"
check "unknown source name" 400 "$(postcode "$BASE/api/sources/bitbucket/sync")"

echo
echo "--- authorize, and the first sync it runs ---"
check "the callback redirects" 302 "$(sift_connect_gitlab)"
CONNECT=$(api "$BASE/api/sources" | python3 -c 'import json,sys; json.dump(json.load(sys.stdin)[0], sys.stdout)')
echo "  $CONNECT" | head -c 400; echo
check "status OK"            '"OK"'    "$(echo "$CONNECT" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["status"]))')"
check "item count"           8         "$(echo "$CONNECT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["itemCount"])')"
check "instance from config" "\"$FAKE\"" "$(echo "$CONNECT" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["instanceUrl"]))')"
check "credential type"      '"OAUTH"' "$(echo "$CONNECT" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["credentialType"]))')"

echo
echo "--- feed shape, and every action arriving under its own name ---"
FEED=$(feed)
python3 - "$FEED" <<'PY'
import json, sys
feed = json.loads(sys.argv[1])
by_kind = {item["kind"]: item for item in feed}
# the last one is deliberately not a GitLab action: an action added later must still reach the feed
expected = [
    "assigned", "review_requested", "approval_required", "directly_addressed",
    "mentioned", "build_failed", "marked", "an_action_gitlab_added_later",
]
ok = fail = 0
for kind in expected:
    if kind in by_kind:
        print(f"  ok    {kind:<32} arrived"); ok += 1
    else:
        print(f"  FAIL  {kind:<32} missing from the feed"); fail += 1
group = by_kind.get("approval_required", {})
if group.get("contextLabel") == "lcsb/platform":
    print("  ok    group todo falls back to group path"); ok += 1
else:
    print(f"  FAIL  group context was {group.get('contextLabel')!r}"); fail += 1
untargeted = by_kind.get("directly_addressed", {})
if untargeted.get("title") == "Can you look at this today?":
    print("  ok    missing target falls back to the body for a title"); ok += 1
else:
    print(f"  FAIL  fallback title was {untargeted.get('title')!r}"); fail += 1
newest = [i["kind"] for i in feed][:1]
if feed == sorted(feed, key=lambda i: i["createdAt"], reverse=True):
    print("  ok    ordered newest first"); ok += 1
else:
    print("  FAIL  feed is not ordered newest first"); fail += 1
print(f"SUBTOTAL {ok} {fail}")
PY

echo
echo "--- filtering by source ---"
check "feed?source=gitlab count" 8 "$(feed 'source=gitlab' | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
check "feed?source=nope" 400 "$(code "$BASE/api/feed?source=nope")"

echo
echo "--- items that disappear upstream are resolved, and stay in the feed as history ---"
python3 "$HERE/make-todos.py" shrunk "$TODOS" >/dev/null
sift_connect_gitlab >/dev/null
check "the feed still holds all 8" 8 "$(feed | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
check "two of them read as resolved" 2 "$(feed | python3 -c 'import json,sys; print(sum(1 for i in json.load(sys.stdin) if i["resolved"]))')"
# nothing is waiting on a finished item. it must not sit in the unread count for ever
check "resolving read them" 2 "$(feed | python3 -c 'import json,sys; print(sum(1 for i in json.load(sys.stdin) if i["resolved"] and i["read"]))')"
check "and left the others unread" 6 "$(feed | python3 -c 'import json,sys; print(sum(1 for i in json.load(sys.stdin) if not i["read"]))')"
check "rows kept in the table" 8 "$(docker exec sift-sync-db psql -U sift -d sift -qtAc 'select count(*) from feed_items' | tr -d ' ')"
check "two rows marked resolved" 2 "$(docker exec sift-sync-db psql -U sift -d sift -qtAc 'select count(*) from feed_items where resolved_at is not null' | tr -d ' ')"
check "first_seen_at preserved on survivors" 6 "$(docker exec sift-sync-db psql -U sift -d sift -qtAc 'select count(*) from feed_items where resolved_at is null and first_seen_at < last_seen_at' | tr -d ' ')"

echo
echo "--- narrowing, ordering and searching, all of which the server does ---"
len() { python3 -c 'import json,sys; print(len(json.load(sys.stdin)))'; }
check "filter=unread"                 6 "$(feed 'filter=unread' | len)"
check "filter=read"                   2 "$(feed 'filter=read' | len)"
check "filter=all is all of it"       8 "$(feed 'filter=all' | len)"
check "an unknown filter is a 400"    400 "$(code "$BASE/api/feed?filter=everything")"
check "an unknown order is a 400"     400 "$(code "$BASE/api/feed?order=loudest")"
check "a forged cursor is a 400"      400 "$(code "$BASE/api/feed?cursor=not-a-cursor")"
FIRST_LATEST="$(feed 'order=latest' | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])')"
LAST_WAITING="$(feed 'order=waiting' | python3 -c 'import json,sys; print(json.load(sys.stdin)[-1]["id"])')"
check "longest waiting is the reverse" "$FIRST_LATEST" "$LAST_WAITING"
check "a word finds its row"          1 "$(feed 'q=rate' | len)"
check "a missing letter is forgiven"  1 "$(feed 'q=limting' | len)"
# a transposition, which is the typo people actually make, and the one uFuzzy needed four modes for
check "two swapped letters too"       1 "$(feed 'q=limitnig' | len)"
check "words may be in any order"     1 "$(feed 'q=sweep%20rate' | len)"
check "every word has to match"       0 "$(feed 'q=rate%20nowhere' | len)"
check "project: narrows"              1 "$(feed 'q=project:frontend' | len)"
check "from: narrows"                 8 "$(feed 'q=from:colleague' | len)"
check "is:mr reads the url"           8 "$(feed 'q=is:mr' | len)"
check "is:unread agrees with filter"  6 "$(feed 'q=is:unread' | len)"
check "is:read and is:unread at once" 0 "$(feed 'q=is:read%20is:unread' | len)"

echo
echo "--- pagination past one page of 100 ---"
python3 "$HERE/make-todos.py" many:150 "$TODOS" >/dev/null
sift_connect_gitlab >/dev/null
# the eight from the first fixture are still in the feed as resolved history: count the live rows
check "all 150 read across 2 pages" 150 "$(feed | python3 -c 'import json,sys; print(sum(1 for i in json.load(sys.stdin) if not i["resolved"]))')"

echo
echo "--- the page bound, and the cursor that walks past it ---"
# a response carries one page of groups, never the whole history
check "a page defaults to 50 groups" 50 "$(api "$BASE/api/feed" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["items"]))')"
check "and says where the next starts" True "$(api "$BASE/api/feed" | python3 -c 'import json,sys; print(json.load(sys.stdin)["nextCursor"] is not None)')"
check "the last page says it is last" None "$(api "$BASE/api/feed?limit=500" | python3 -c 'import json,sys; print(json.load(sys.stdin)["nextCursor"])')"
walk_ids() {
  local cursor="" page
  while :; do
    page="$(api "$BASE/api/feed?limit=20${cursor:+&cursor=$cursor}")"
    echo "$page" | python3 -c 'import json,sys; [print(i["id"]) for i in json.load(sys.stdin)["items"]]'
    cursor="$(echo "$page" | python3 -c 'import json,sys; print(json.load(sys.stdin)["nextCursor"] or "")')"
    [ -z "$cursor" ] && break
  done
}
IDS="$(walk_ids)"
check "the cursor reaches every row" 158 "$(echo "$IDS" | grep -c .)"
check "and never hands one out twice" 158 "$(echo "$IDS" | sort -u | grep -c .)"

echo
echo "--- the counts, which no single page could work out ---"
summary() { api "$BASE/api/feed/summary" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['$1'])"; }
check "total is the whole history"    158 "$(summary total)"
check "waiting is what is still sent" 150 "$(summary waiting)"
check "unread"                        150 "$(summary unread)"
check "the source it belongs to"      gitlab "$(summary source)"
check "counted by kind for the card"  150 "$(api "$BASE/api/feed/summary" | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["waitingByKind"]["assigned"])')"

echo
echo "--- sources listing and disconnect ---"
check "GET /api/sources count" 1 "$(api "$BASE/api/sources" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
check "disconnect" 204 "$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" -X DELETE -H "X-XSRF-TOKEN: $(csrf)" "$BASE/api/sources/gitlab")"
check "feed emptied" 0 "$(feed | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
check "items gone from the table" 0 "$(docker exec sift-sync-db psql -U sift -d sift -qtAc 'select count(*) from feed_items' | tr -d ' ')"
check "disconnect again is 404" 404 "$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" -X DELETE -H "X-XSRF-TOKEN: $(csrf)" "$BASE/api/sources/gitlab")"

echo
echo "--- an unreadable stored token degrades, it does not 500 ---"
python3 "$HERE/make-todos.py" full "$TODOS" >/dev/null
sift_connect_gitlab >/dev/null
docker exec sift-sync-db psql -U sift -d sift -qtAc "update source_credentials set access_token_enc = 'not-real-ciphertext'" >/dev/null
check "GET /api/sources still answers" 200 "$(code "$BASE/api/sources")"
check "credential still listed" 1 "$(api "$BASE/api/sources" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
# put a usable token back for the sweep phase
sift_connect_gitlab >/dev/null

echo
echo "--- the scheduled sweep: a revoked token becomes AUTH_FAILED, then is skipped ---"
touch "$WORK/revoked"
kill "$BOOT_PID" 2>/dev/null; wait "$BOOT_PID" 2>/dev/null
SIFT_DB_URL=jdbc:postgresql://localhost:5439/sift SIFT_DB_USER=sift SIFT_DB_PASSWORD=sift \
SIFT_ENCRYPTION_KEY="$KEY" SIFT_ALLOWED_EMAIL_DOMAINS=uni.lu SIFT_PORT=7779 \
SIFT_SYNC_INITIAL_DELAY=PT2S SIFT_SYNC_INTERVAL=PT3S \
  env $(sift_oauth_env) "$ROOT/backend/gradlew" -p "$ROOT/backend" bootRun --console=plain >"$WORK/sweep-boot.log" 2>&1 &
BOOT_PID=$!
for _ in $(seq 1 150); do grep -q "Started SiftApplication" "$WORK/sweep-boot.log" 2>/dev/null && break; sleep 1; done
echo "  backend restarted with a 3s sweep"
sleep 14

check "credential marked AUTH_FAILED" "AUTH_FAILED" "$(docker exec sift-sync-db psql -U sift -d sift -qtAc 'select last_sync_status from source_credentials' | tr -d ' ')"
check "reason recorded for the user" 1 "$(docker exec sift-sync-db psql -U sift -d sift -qtAc "select count(*) from source_credentials where last_error like '%rejected the token%'" | tr -d ' ')"
check "sweep tried it exactly once, then skipped it" 1 "$(grep -c 'sync failed for user' "$WORK/sweep-boot.log")"
check "items left intact, not wiped by the failure" 8 "$(docker exec sift-sync-db psql -U sift -d sift -qtAc 'select count(*) from feed_items' | tr -d ' ')"
rm -f "$WORK/revoked"


echo
echo "RESULT: $PASS passed, $FAIL failed (plus the feed subtotal above)"
[ "$FAIL" -eq 0 ] || exit 1
