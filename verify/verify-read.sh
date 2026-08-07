#!/usr/bin/env bash
# drives marking items read: the endpoint, tenancy, and what a later sync does to a read row
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
WORK="${SIFT_VERIFY_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
LOG="$WORK/read-boot.log"
JAR_A="$WORK/read-cookies-a.txt"
JAR_B="$WORK/read-cookies-b.txt"
JAR_NONE="$WORK/read-cookies-none.txt"
TODOS="$WORK/read-todos.json"
BASE=http://localhost:7779
FAKE=http://127.0.0.1:7788
# shellcheck source=oauth-connect.sh
source "$HERE/oauth-connect.sh"
JAR="$JAR_A"
PASS=0
FAIL=0

cleanup() {
  [ -n "${BOOT_PID:-}" ] && kill "$BOOT_PID" 2>/dev/null
  [ -n "${STUB_PID:-}" ] && kill "$STUB_PID" 2>/dev/null
  docker rm -f sift-read-db >/dev/null 2>&1
}
trap cleanup EXIT

check() {
  if [ "$3" = "$2" ]; then printf '  ok    %-52s %s\n' "$1" "$3"; PASS=$((PASS+1))
  else printf '  FAIL  %-52s expected %s, got %s\n' "$1" "$2" "$3"; FAIL=$((FAIL+1)); fi
}

rm -f "$JAR_A" "$JAR_B" "$JAR_NONE"
KEY="$(openssl rand -base64 32)"
python3 "$HERE/make-todos.py" full "$TODOS" >/dev/null

PORT=7788 TODOS_FILE="$TODOS" python3 "$HERE/fake-gitlab.py" &
STUB_PID=$!
for _ in $(seq 1 30); do curl -sf -o /dev/null "$FAKE/oauth/issued" && break; sleep 1; done
echo "stub gitlab up"

docker rm -f sift-read-db >/dev/null 2>&1
docker run -d --rm --name sift-read-db -e POSTGRES_DB=sift -e POSTGRES_USER=sift \
  -e POSTGRES_PASSWORD=sift -p 5439:5432 postgres:17-alpine >/dev/null
for _ in $(seq 1 60); do docker exec sift-read-db pg_isready -U sift -d sift >/dev/null 2>&1 && break; sleep 1; done

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
# the feed is paged over groups now, so a suite asks for one page large enough to hold every fixture
# and unwraps the items. `limit` counts groups; 500 is the server's own ceiling.
feed() { api "$BASE/api/feed?limit=500${1:+&$1}" | python3 -c 'import json,sys; json.dump(json.load(sys.stdin)["items"], sys.stdout)'; }
post() { curl -s -c "$JAR" -b "$JAR" -X POST -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"; }
patchcode() { curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" -X PATCH -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"; }

# the kind is the stable handle on a fixture item; the id is a uuid the run only learns at runtime
item_id() {
  feed | python3 -c 'import json,sys; print(next(i["id"] for i in json.load(sys.stdin) if i["kind"]==sys.argv[1]))' "$1"
}
read_flag() {
  feed | python3 -c 'import json,sys; print(json.dumps(next(i["read"] for i in json.load(sys.stdin) if i["kind"]==sys.argv[1])))' "$1"
}
unread_total() {
  feed | python3 -c 'import json,sys; print(sum(1 for i in json.load(sys.stdin) if not i["read"]))'
}
mark_all_read() {
  for id in $(feed | python3 -c 'import json,sys; print(" ".join(i["id"] for i in json.load(sys.stdin)))'); do
    patchcode -d '{"read":true}' "$BASE/api/feed/$id" >/dev/null
  done
}
sql() { docker exec sift-read-db psql -U sift -d sift -qtAc "$1" | tr -d ' '; }

api "$BASE/actuator/health" >/dev/null
post -d '{"email":"isfaaq@uni.lu","displayName":"Isfaaq","password":"correct-horse-battery"}' "$BASE/api/auth/register" >/dev/null
post -d '{"email":"isfaaq@uni.lu","password":"correct-horse-battery"}' "$BASE/api/auth/login" >/dev/null
sift_connect_gitlab >/dev/null

echo "--- everything arrives unread ---"
check "feed size" 8 "$(feed | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
check "all unread" 8 "$(unread_total)"
check "no read_at in the table" 8 "$(sql 'select count(*) from feed_items where read_at is null')"

echo
echo "--- marking one item, and putting it back ---"
TARGET="$(item_id review_requested)"
check "patch read"            204     "$(patchcode -d '{"read":true}' "$BASE/api/feed/$TARGET")"
check "the item reads read"   true    "$(read_flag review_requested)"
check "only that one changed" 7       "$(unread_total)"
check "read_at written"       1       "$(sql 'select count(*) from feed_items where read_at is not null')"
check "patch unread"          204     "$(patchcode -d '{"read":false}' "$BASE/api/feed/$TARGET")"
check "the item reads unread" false   "$(read_flag review_requested)"
check "read_at cleared"       0       "$(sql 'select count(*) from feed_items where read_at is not null')"

echo
echo "--- a request that cannot be honoured says so ---"
check "no read field"    400 "$(patchcode -d '{}' "$BASE/api/feed/$TARGET")"
check "null read field"  400 "$(patchcode -d '{"read":null}' "$BASE/api/feed/$TARGET")"
check "id is not a uuid" 400 "$(patchcode -d '{"read":true}' "$BASE/api/feed/not-a-uuid")"
check "unknown item"     404 "$(patchcode -d '{"read":true}' "$BASE/api/feed/2f1c8b64-0000-4000-8000-000000000000")"

echo
echo "--- another tenant's item does not exist for you ---"
JAR="$JAR_B"
api "$BASE/actuator/health" >/dev/null
post -d '{"email":"maxime@uni.lu","displayName":"Maxime","password":"correct-horse-battery"}' "$BASE/api/auth/register" >/dev/null
post -d '{"email":"maxime@uni.lu","password":"correct-horse-battery"}' "$BASE/api/auth/login" >/dev/null
sift_connect_gitlab >/dev/null
check "the second user has their own rows" 16 "$(sql 'select count(*) from feed_items')"
check "patching the first user's item" 404 "$(patchcode -d '{"read":true}' "$BASE/api/feed/$TARGET")"
check "and it stayed unread" 0 "$(sql 'select count(*) from feed_items where read_at is not null')"

JAR="$JAR_NONE"
curl -s -o /dev/null -c "$JAR" "$BASE/actuator/health"
check "no session at all" 401 "$(patchcode -d '{"read":true}' "$BASE/api/feed/$TARGET")"

echo
echo "--- a later sync only un-reads what actually moved ---"
JAR="$JAR_A"
mark_all_read
check "all read" 0 "$(unread_total)"

# the stub re-reads its fixture per request, so this is what "the todo was replied to" looks like
python3 - "$TODOS" <<'PY'
import json, sys
from datetime import datetime, timezone
path = sys.argv[1]
todos = json.load(open(path))
stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
for todo in todos:
    if todo["action_name"] == "review_requested":
        todo["updated_at"] = stamp
with open(path, "w") as handle:
    json.dump(todos, handle)
PY

post "$BASE/api/sources/gitlab/sync" >/dev/null
check "the moved item is unread again" false "$(read_flag review_requested)"
check "nothing else was disturbed"     1     "$(unread_total)"

post "$BASE/api/sources/gitlab/sync" >/dev/null
check "an unchanged sweep un-reads nothing" 1 "$(unread_total)"

patchcode -d '{"read":true}' "$BASE/api/feed/$(item_id review_requested)" >/dev/null
post "$BASE/api/sources/gitlab/sync" >/dev/null
check "and it stays read once you have seen it" 0 "$(unread_total)"

echo
echo "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
