#!/usr/bin/env bash
# A token that will not decrypt must be recorded once and then left alone. Getting this wrong is
# invisible: the sweep retries forever, the status is never written, and no warning ever appears.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# scratch: logs, cookies and the fixtures a run mutates. kept out of the repo.
WORK="${SIFT_VERIFY_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
JAR="$WORK/u-cookies.txt"
LOG="$WORK/u-boot.log"
BASE=http://localhost:7779
FAKE=http://127.0.0.1:7788
PASS=0; FAIL=0

cleanup() {
  [ -n "${BOOT_PID:-}" ] && kill "$BOOT_PID" 2>/dev/null
  [ -n "${STUB_PID:-}" ] && kill "$STUB_PID" 2>/dev/null
  docker rm -f sift-u-db >/dev/null 2>&1
}
trap cleanup EXIT

check() {
  if [ "$3" = "$2" ]; then printf '  ok    %-56s %s\n' "$1" "$3"; PASS=$((PASS+1))
  else printf '  FAIL  %-56s expected %s, got %s\n' "$1" "$2" "$3"; FAIL=$((FAIL+1)); fi
}

rm -f "$JAR"
python3 "$HERE/make-todos.py" full "$WORK/u-todos.json" >/dev/null
PORT=7788 TODOS_FILE="$WORK/u-todos.json" python3 "$HERE/fake-gitlab.py" &
STUB_PID=$!
for _ in $(seq 1 30); do curl -sf -o /dev/null -H 'PRIVATE-TOKEN: good-token' "$FAKE/api/v4/user" && break; sleep 1; done

docker rm -f sift-u-db >/dev/null 2>&1
docker run -d --rm --name sift-u-db -e POSTGRES_DB=sift -e POSTGRES_USER=sift \
  -e POSTGRES_PASSWORD=sift -p 5439:5432 postgres:17-alpine >/dev/null
for _ in $(seq 1 60); do docker exec sift-u-db pg_isready -U sift -d sift >/dev/null 2>&1 && break; sleep 1; done

SIFT_DB_URL=jdbc:postgresql://localhost:5439/sift SIFT_DB_USER=sift SIFT_DB_PASSWORD=sift \
SIFT_ENCRYPTION_KEY="$(openssl rand -base64 32)" SIFT_PORT=7779 \
SIFT_SYNC_INITIAL_DELAY=PT2S SIFT_SYNC_INTERVAL=PT3S \
  "$ROOT/backend/gradlew" -p "$ROOT/backend" bootRun --console=plain >"$LOG" 2>&1 &
BOOT_PID=$!
for _ in $(seq 1 180); do
  grep -q "Started SiftApplication" "$LOG" 2>/dev/null && break
  grep -qE "APPLICATION FAILED TO START|FAILURE: " "$LOG" 2>/dev/null && {
    sed -n '/APPLICATION FAILED TO START/,/^$/p' "$LOG" | head -20; exit 1; }
  sleep 1
done
grep -q "Started SiftApplication" "$LOG" || { echo "backend never started"; exit 1; }
echo "backend up with a 3s sweep"; echo

csrf() { awk '$6=="XSRF-TOKEN" {print $7}' "$JAR" | tail -1; }
api() { curl -s -c "$JAR" -b "$JAR" "$@"; }
code() { curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" "$@"; }
post() { curl -s -c "$JAR" -b "$JAR" -X POST -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"; }
sql() { docker exec sift-u-db psql -U sift -d sift -qtAc "$1" | tr -d ' '; }

api "$BASE/actuator/health" >/dev/null
post -d '{"email":"a@b.co","displayName":"A","password":"correct-horse-battery"}' "$BASE/api/auth/register" >/dev/null
post -d '{"email":"a@b.co","password":"correct-horse-battery"}' "$BASE/api/auth/login" >/dev/null
post -d '{"instanceUrl":"'$FAKE'","token":"good-token"}' "$BASE/api/sources/gitlab/connect" >/dev/null
sleep 5
check "healthy to begin with" OK "$(sql 'select last_sync_status from source_credentials')"

echo
echo "--- checking now, instead of waiting for the sweep ---"
BEFORE=$(sql 'select last_sync_at from source_credentials')
sleep 1
check "check now succeeds" 200 "$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" -X POST -H "X-XSRF-TOKEN: $(csrf)" "$BASE/api/sources/gitlab/sync")"
check "and it really re-read" True "$(python3 -c "print('$(sql 'select last_sync_at from source_credentials')' > '$BEFORE')")"
check "unknown source is rejected" 400 "$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" -X POST -H "X-XSRF-TOKEN: $(csrf)" "$BASE/api/sources/bitbucket/sync")"

echo
echo "--- the stored token becomes unreadable, as if the encryption key changed ---"
sql "update source_credentials set access_token_enc = 'not-real-ciphertext'" >/dev/null
sleep 11

check "the failure is actually persisted" AUTH_FAILED "$(sql 'select last_sync_status from source_credentials')"
check "with a reason the user can act on" 1 "$(sql "select count(*) from source_credentials where last_error like '%cannot read the stored token%'")"
check "the ciphertext is left alone, not overwritten" 1 "$(sql "select count(*) from source_credentials where access_token_enc = 'not-real-ciphertext'")"
check "tried once, then stopped" 1 "$(grep -c 'sync failed for user' "$LOG")"
check "no not-null constraint violation anywhere" 0 "$(grep -ci 'not-null constraint' "$LOG")"
check "listing sources still answers" 200 "$(code "$BASE/api/sources")"
check "the UI is told it was rejected" '"AUTH_FAILED"' "$(api "$BASE/api/sources" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)[0]["status"]))')"
check "the items already collected are still there" 8 "$(api "$BASE/api/feed" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"

echo
echo "--- reconnecting recovers, and sweeps resume ---"
post -d '{"instanceUrl":"'$FAKE'","token":"good-token"}' "$BASE/api/sources/gitlab/connect" >/dev/null
sleep 8
check "back to OK" OK "$(sql 'select last_sync_status from source_credentials')"
check "and the reason is cleared" 1 "$(sql 'select count(*) from source_credentials where last_error is null')"
check "still only the one historical failure" 1 "$(grep -c 'sync failed for user' "$LOG")"

echo
echo "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
