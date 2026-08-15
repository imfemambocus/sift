#!/usr/bin/env bash
# stub gitlab + stub google + postgres + backend + vite, then drive the real UI through connecting
# both sources and reading them
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# scratch: logs, cookies and the fixtures a run mutates. kept out of the repo.
WORK="${SIFT_VERIFY_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
TODOS="$WORK/todos.json"
MAIL="$WORK/feed-mail.json"
FAKE=http://127.0.0.1:7788
GOOGLE=http://127.0.0.1:7790
# the browser lives on the vite origin. the callback has to come back there and not to the
# backend directly. vite proxies /api to 7779, and the redirect to home then stays on 5174.
BASE=http://localhost:5174
# shellcheck source=oauth-connect.sh
source "$HERE/oauth-connect.sh"

cleanup() {
  [ -n "${VITE_PID:-}" ] && kill "$VITE_PID" 2>/dev/null
  [ -n "${BOOT_PID:-}" ] && kill "$BOOT_PID" 2>/dev/null
  [ -n "${STUB_PID:-}" ] && kill "$STUB_PID" 2>/dev/null
  [ -n "${GOOGLE_PID:-}" ] && kill "$GOOGLE_PID" 2>/dev/null
  docker rm -f sift-ui2-db >/dev/null 2>&1
  rm -f "$WORK/revoked" "$WORK/feed-slow"
}
trap cleanup EXIT

rm -f "$WORK/revoked" "$WORK/feed-slow"
python3 "$HERE/make-todos.py" full "$TODOS" >/dev/null
python3 "$HERE/make-mrs.py" "$WORK/feed-mrs.json" >/dev/null
# a thread the user is already in: a later reply produces a real participation row
cat > "$WORK/feed-disc.json" <<'JSON'
{"merge_requests:5:12": [{"id": "t1", "notes": [
  {"id": 5001, "body": "Can we avoid the extra round trip here?", "system": false,
   "created_at": "2026-08-03T08:00:00.000Z", "author": {"id": 42, "username": "sam", "name": "Sam"}}]}]}
JSON
# SLOW_FILE holds a read open long enough to photograph a card while it is being read
PORT=7788 TODOS_FILE="$TODOS" MRS_FILE="$WORK/feed-mrs.json" DISCUSSIONS_FILE="$WORK/feed-disc.json" REVOKE_FILE="$WORK/revoked" SLOW_FILE="$WORK/feed-slow" SLOW_SECONDS=4 python3 "$HERE/fake-gitlab.py" &
STUB_PID=$!
for _ in $(seq 1 30); do curl -sf -o /dev/null http://127.0.0.1:7788/oauth/issued && break; sleep 1; done
echo "stub gitlab up"

# one base time for every rewrite of the mailbox. a message already read cannot then look new
NOW_MS=$(python3 -c 'import time; print(int(time.time() * 1000))')
python3 "$HERE/make-mail.py" base "$MAIL" "$NOW_MS" >/dev/null
PORT=7790 MESSAGES_FILE="$MAIL" OAUTH_CLIENT_ID="$SIFT_OAUTH_CLIENT_ID" \
  OAUTH_CLIENT_SECRET="$SIFT_OAUTH_CLIENT_SECRET" python3 "$HERE/fake-google.py" &
GOOGLE_PID=$!
for _ in $(seq 1 30); do curl -sf -o /dev/null http://127.0.0.1:7790/oauth/issued && break; sleep 1; done
echo "stub google up"

docker rm -f sift-ui2-db >/dev/null 2>&1
docker run -d --rm --name sift-ui2-db -e POSTGRES_DB=sift -e POSTGRES_USER=sift \
  -e POSTGRES_PASSWORD=sift -p 5439:5432 postgres:17-alpine >/dev/null
for _ in $(seq 1 60); do docker exec sift-ui2-db pg_isready -U sift -d sift >/dev/null 2>&1 && break; sleep 1; done

# a fast sweep so the revoked-token alert can be captured without waiting five minutes
SIFT_DB_URL=jdbc:postgresql://localhost:5439/sift SIFT_DB_USER=sift SIFT_DB_PASSWORD=sift \
SIFT_ENCRYPTION_KEY="$(openssl rand -base64 32)" SIFT_ALLOWED_EMAIL_DOMAINS=uni.lu \
SIFT_SYNC_INITIAL_DELAY=PT3S SIFT_SYNC_INTERVAL=PT4S SIFT_PORT=7779 \
  env $(sift_oauth_env) $(sift_gmail_env) "$ROOT/backend/gradlew" -p "$ROOT/backend" bootRun --console=plain >"$WORK/feed-boot.log" 2>&1 &
BOOT_PID=$!
for _ in $(seq 1 150); do
  grep -q "Started SiftApplication" "$WORK/feed-boot.log" 2>/dev/null && break
  grep -qE "APPLICATION FAILED TO START|FAILURE: " "$WORK/feed-boot.log" 2>/dev/null && {
    echo "backend failed:"; sed -n '/APPLICATION FAILED TO START/,/^$/p' "$WORK/feed-boot.log" | head -20; exit 1; }
  sleep 1
done
echo "backend up"

# refuse to run rather than kill whatever holds 5174: a dev server someone else started would
# otherwise be driven instead of ours, proxying the whole suite at their backend
if curl -sf -o /dev/null http://localhost:5174/; then
  echo "something is already serving http://localhost:5174 - stop it and run this again"; exit 1
fi
# exec vite itself rather than `npm run dev`: VITE_PID is then the server and not a wrapper whose
# death leaves it listening on 5174 for the next run to drive by mistake
(cd "$ROOT/frontend" && SIFT_BACKEND_URL=http://localhost:7779 exec ./node_modules/.bin/vite >"$WORK/feed-vite.log" 2>&1) &
VITE_PID=$!
for _ in $(seq 1 60); do curl -sf -o /dev/null http://localhost:5174/ && break; sleep 1; done
echo "vite up"
echo

SIFT_VERIFY_WORK="$WORK" SIFT_VERIFY_SHOTS="${SIFT_VERIFY_SHOTS:-$WORK/shots}" node "$HERE/shoot-feed.mjs"
