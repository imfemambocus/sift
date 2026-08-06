#!/usr/bin/env bash
# the four cases that matter now that merge requests are read alongside to-dos
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# scratch: logs, cookies and the fixtures a run mutates. kept out of the repo.
WORK="${SIFT_VERIFY_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
JAR="$WORK/mr-cookies.txt"
TODOS="$WORK/mr-todos.json"
MRS="$WORK/mr-mrs.json"
BASE=http://localhost:7779
FAKE=http://127.0.0.1:7788
PASS=0; FAIL=0

cleanup() {
  [ -n "${BOOT_PID:-}" ] && kill "$BOOT_PID" 2>/dev/null
  [ -n "${STUB_PID:-}" ] && kill "$STUB_PID" 2>/dev/null
  docker rm -f sift-mr-db >/dev/null 2>&1
}
trap cleanup EXIT

check() {
  if [ "$3" = "$2" ]; then printf '  ok    %-54s %s\n' "$1" "$3"; PASS=$((PASS+1))
  else printf '  FAIL  %-54s expected %s, got %s\n' "$1" "$2" "$3"; FAIL=$((FAIL+1)); fi
}

rm -f "$JAR"
python3 "$HERE/make-mrs.py" "$MRS" >/dev/null
# one to-do, pointing at merge request 11, so that one is already covered
cat > "$TODOS" <<'JSON'
[{"id": 900, "action_name": "review_requested", "target_type": "MergeRequest",
  "target_url": "https://gitlab.example.org/sift/backend/-/merge_requests/11",
  "body": "Already has a to-do", "state": "pending", "created_at": "2026-08-03T09:00:00.000Z",
  "author": {"id": 9, "username": "colleague", "name": "A Colleague"},
  "project": {"id": 7, "name": "backend", "path_with_namespace": "sift/backend",
              "web_url": "https://gitlab.example.org/sift/backend"},
  "target": {"title": "Already has a to-do", "iid": 11}}]
JSON

PORT=7788 TODOS_FILE="$TODOS" MRS_FILE="$MRS" python3 "$HERE/fake-gitlab.py" &
STUB_PID=$!
for _ in $(seq 1 30); do curl -sf -o /dev/null -H 'PRIVATE-TOKEN: good-token' "$FAKE/api/v4/user" && break; sleep 1; done

docker run -d --rm --name sift-mr-db -e POSTGRES_DB=sift -e POSTGRES_USER=sift \
  -e POSTGRES_PASSWORD=sift -p 5439:5432 postgres:17-alpine >/dev/null
for _ in $(seq 1 60); do docker exec sift-mr-db pg_isready -U sift -d sift >/dev/null 2>&1 && break; sleep 1; done

SIFT_DB_URL=jdbc:postgresql://localhost:5439/sift SIFT_DB_USER=sift SIFT_DB_PASSWORD=sift \
SIFT_ENCRYPTION_KEY="$(openssl rand -base64 32)" SIFT_SYNC_INITIAL_DELAY=PT1H SIFT_PORT=7779 \
  "$ROOT/backend/gradlew" -p "$ROOT/backend" bootRun --console=plain >"$WORK/mr-boot.log" 2>&1 &
BOOT_PID=$!
for _ in $(seq 1 150); do
  grep -q "Started SiftApplication" "$WORK/mr-boot.log" 2>/dev/null && break
  grep -qE "APPLICATION FAILED TO START|FAILURE: " "$WORK/mr-boot.log" 2>/dev/null && {
    sed -n '/APPLICATION FAILED TO START/,/^$/p' "$WORK/mr-boot.log" | head -20; exit 1; }
  sleep 1
done
echo "backend up"; echo

csrf() { awk '$6=="XSRF-TOKEN" {print $7}' "$JAR" | tail -1; }
api() { curl -s -c "$JAR" -b "$JAR" "$@"; }
post() { curl -s -c "$JAR" -b "$JAR" -X POST -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"; }

api "$BASE/actuator/health" >/dev/null
post -d '{"email":"a@b.co","displayName":"A","password":"correct-horse-battery"}' "$BASE/api/auth/register" >/dev/null
post -d '{"email":"a@b.co","password":"correct-horse-battery"}' "$BASE/api/auth/login" >/dev/null
CONNECT=$(post -d '{"instanceUrl":"'$FAKE'","token":"good-token"}' "$BASE/api/sources/gitlab/connect")

FEED=$(api "$BASE/api/feed")
echo "$FEED" | python3 -c '
import json, sys
for i in json.load(sys.stdin):
    kind, title, ctx = i["kind"], i["title"][:44], i["contextLabel"]
    print("    %-22s %-46s %s" % (kind, title, ctx))
'
echo

ids() { echo "$FEED" | python3 -c 'import json,sys; print(" ".join(sorted(i["kind"] for i in json.load(sys.stdin))))'; }

check "total rows in the feed" 3 "$(echo "$FEED" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
check "kinds present" "mr_assigned mr_review_requested review_requested" "$(ids)"
check "the uncovered review request is there" 1 "$(echo "$FEED" | python3 -c 'import json,sys; print(sum(1 for i in json.load(sys.stdin) if i["title"]=="Review requested with no to-do"))')"
check "exactly one row per merge request awaiting review" 1 "$(echo "$FEED" | python3 -c 'import json,sys; print(sum(1 for i in json.load(sys.stdin) if i["kind"]=="mr_review_requested"))')"
check "the to-do covered MR contributes no mr: row" 0 "$(echo "$FEED" | python3 -c 'import json,sys; print(sum(1 for i in json.load(sys.stdin) if i["url"].endswith("/merge_requests/11") and i["kind"].startswith("mr_")))')"
check "the draft is skipped" 0 "$(echo "$FEED" | python3 -c 'import json,sys; print(sum(1 for i in json.load(sys.stdin) if "Draft" in i["title"]))')"
check "project path came from references.full" '"sift/frontend"' "$(echo "$FEED" | python3 -c 'import json,sys; print(json.dumps(next(i["contextLabel"] for i in json.load(sys.stdin) if i["kind"]=="mr_review_requested")))')"
check "sync reported no failure" '"OK"' "$(echo "$CONNECT" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["status"]))')"
check "no unique key violation in the log" 0 "$(grep -ci 'constraint\|duplicate key' "$WORK/mr-boot.log")"

echo
echo "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
