#!/usr/bin/env bash
# the participation rules, which are all about what must NOT be emitted as much as what must
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# scratch: logs, cookies and the fixtures a run mutates. kept out of the repo.
WORK="${SIFT_VERIFY_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
JAR="$WORK/p-cookies.txt"
TODOS="$WORK/p-todos.json"
MRS="$WORK/p-mrs.json"
ISSUES="$WORK/p-issues.json"
DISC="$WORK/p-disc.json"
EVENTS="$WORK/p-events.json"
BASE=http://localhost:7779
FAKE=http://127.0.0.1:7788
# shellcheck source=oauth-connect.sh
source "$HERE/oauth-connect.sh"
PASS=0; FAIL=0

cleanup() {
  [ -n "${BOOT_PID:-}" ] && kill "$BOOT_PID" 2>/dev/null
  [ -n "${STUB_PID:-}" ] && kill "$STUB_PID" 2>/dev/null
  docker rm -f sift-p-db >/dev/null 2>&1
}
trap cleanup EXIT

check() {
  if [ "$3" = "$2" ]; then printf '  ok    %-56s %s\n' "$1" "$3"; PASS=$((PASS+1))
  else printf '  FAIL  %-56s expected %s, got %s\n' "$1" "$2" "$3"; FAIL=$((FAIL+1)); fi
}

# self is user 42 in the stub; 9 is a colleague
echo '[]' > "$TODOS"
echo '[]' > "$EVENTS"
echo '{"assigned_to_me": [], "created_by_me": []}' > "$ISSUES"
cat > "$MRS" <<'JSON'
{"review_requested": [{"id": 700, "iid": 20, "title": "Chart V2: Line chart color encoding",
   "state": "opened", "draft": false, "sha": "aaa111", "project_id": 5, "user_notes_count": 3,
   "web_url": "https://gl.example.org/team/web/-/merge_requests/20",
   "created_at": "2026-08-01T09:00:00.000Z", "updated_at": "2026-08-03T09:00:00.000Z",
   "author": {"id": 9, "username": "maxime", "name": "Maxime"},
   "references": {"full": "team/web!20"}}],
 "assigned": [],
 "authored": [{"id": 800, "iid": 30, "title": "My own branch, nobody else on it",
   "state": "opened", "draft": false, "sha": "ccc111", "project_id": 5, "user_notes_count": 0,
   "web_url": "https://gl.example.org/team/web/-/merge_requests/30",
   "created_at": "2026-08-01T09:00:00.000Z", "updated_at": "2026-08-03T09:00:00.000Z",
   "author": {"id": 42, "username": "isfaaq", "name": "Isfaaq"},
   "references": {"full": "team/web!30"}}]}
JSON
# one thread the user is in, already containing their own comment
cat > "$DISC" <<'JSON'
{"merge_requests:5:20": [
  {"id": "d1", "notes": [
    {"id": 1001, "body": "This colour ramp is not colourblind safe.", "system": false,
     "created_at": "2026-08-02T10:00:00.000Z", "author": {"id": 42, "username": "isfaaq", "name": "Isfaaq"}}]}]}
JSON

PORT=7788 TODOS_FILE="$TODOS" MRS_FILE="$MRS" ISSUES_FILE="$ISSUES" DISCUSSIONS_FILE="$DISC" \
  EVENTS_FILE="$EVENTS" python3 "$HERE/fake-gitlab.py" &
STUB_PID=$!
for _ in $(seq 1 30); do curl -sf -o /dev/null "$FAKE/oauth/issued" && break; sleep 1; done

docker rm -f sift-p-db >/dev/null 2>&1
docker run -d --rm --name sift-p-db -e POSTGRES_DB=sift -e POSTGRES_USER=sift \
  -e POSTGRES_PASSWORD=sift -p 5439:5432 postgres:17-alpine >/dev/null
for _ in $(seq 1 60); do docker exec sift-p-db pg_isready -U sift -d sift >/dev/null 2>&1 && break; sleep 1; done

SIFT_DB_URL=jdbc:postgresql://localhost:5439/sift SIFT_DB_USER=sift SIFT_DB_PASSWORD=sift \
SIFT_ENCRYPTION_KEY="$(openssl rand -base64 32)" SIFT_SYNC_INITIAL_DELAY=PT1H SIFT_PORT=7779 \
  env $(sift_oauth_env) "$ROOT/backend/gradlew" -p "$ROOT/backend" bootRun --console=plain >"$WORK/p-boot.log" 2>&1 &
BOOT_PID=$!
for _ in $(seq 1 150); do
  grep -q "Started SiftApplication" "$WORK/p-boot.log" 2>/dev/null && break
  grep -qE "APPLICATION FAILED TO START|FAILURE: " "$WORK/p-boot.log" 2>/dev/null && {
    sed -n '/APPLICATION FAILED TO START/,/^$/p' "$WORK/p-boot.log" | head -20; exit 1; }
  sleep 1
done
echo "backend up"; echo

csrf() { awk '$6=="XSRF-TOKEN" {print $7}' "$JAR" | tail -1; }
api() { curl -s -c "$JAR" -b "$JAR" "$@"; }
# the feed is paged over groups, so a suite asks for one page large enough to hold every fixture
# and unwraps the items. `limit` counts groups; 500 is the server's own ceiling.
feed() { api "$BASE/api/feed?limit=500${1:+&$1}" | python3 -c 'import json,sys; json.dump(json.load(sys.stdin)["items"], sys.stdout)'; }
post() { curl -s -c "$JAR" -b "$JAR" -X POST -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"; }
connect() { sift_connect_gitlab >/dev/null; }
kinds() { feed | python3 -c 'import json,sys; print(" ".join(sorted(i["kind"] for i in json.load(sys.stdin))))'; }
count() { feed | python3 -c "import json,sys; print(sum(1 for i in json.load(sys.stdin) if i['kind']=='$1'))"; }
settled() { feed | python3 -c "import json,sys; print(sum(1 for i in json.load(sys.stdin) if i['kind']=='$1' and i['resolved']))"; }
titled() { feed | python3 -c "import json,sys; print(sum(1 for i in json.load(sys.stdin) if i['title']=='$1'))"; }
watched() { docker exec sift-p-db psql -U sift -d sift -qtAc 'select count(*) from gitlab_watched_resources' | tr -d ' '; }

api "$BASE/actuator/health" >/dev/null
post -d '{"email":"a@b.co","displayName":"A","password":"correct-horse-battery"}' "$BASE/api/auth/register" >/dev/null
post -d '{"email":"a@b.co","password":"correct-horse-battery"}' "$BASE/api/auth/login" >/dev/null

echo "--- first sync only baselines: an existing thread must not be announced ---"
connect
check "kinds after first sync" "mr_review_requested" "$(kinds)"
check "no thread rows from history" 0 "$(count new_comment)"
check "no new_thread rows either" 0 "$(count new_thread)"
check "threads recorded as baseline" 1 "$(docker exec sift-p-db psql -U sift -d sift -qtAc 'select count(*) from gitlab_watched_discussions' | tr -d ' ')"

echo
echo "--- Maxime replies and pushes commits: one row per thread, plus the commits ---"
python3 - "$DISC" <<'PY'
import json, sys
path = sys.argv[1]
data = json.load(open(path))
data["merge_requests:5:20"][0]["notes"].append(
    {"id": 1002, "body": "Fixed, switched to the Okabe-Ito ramp and pushed.", "system": False,
     "created_at": "2026-08-03T11:00:00.000Z", "author": {"id": 9, "username": "maxime", "name": "Maxime"}})
data["merge_requests:5:20"][0]["notes"].append(
    {"id": 1003, "body": "added 2 commits", "system": True,
     "created_at": "2026-08-03T11:01:00.000Z", "author": {"id": 9, "username": "maxime", "name": "Maxime"}})
json.dump(data, open(path, "w"))
PY
python3 - "$MRS" <<'PY'
import json, sys
path = sys.argv[1]
data = json.load(open(path))
data["review_requested"][0]["sha"] = "bbb222"
data["review_requested"][0]["updated_at"] = "2026-08-03T11:01:00.000Z"
json.dump(data, open(path, "w"))
PY
connect
check "kinds now" "changes_pushed mr_review_requested new_comment" "$(kinds)"
check "one row for the thread, not one per reply" 1 "$(count new_comment)"
check "commits noticed via sha" 1 "$(count changes_pushed)"
check "the commits row names whose branch moved" '"Maxime"' "$(feed | python3 -c 'import json,sys; print(json.dumps(next(i["actorName"] for i in json.load(sys.stdin) if i["kind"]=="changes_pushed")))')"
check "snippet is the reply, not the system note" '"Fixed, switched to the Okabe-Ito ramp and pushed."' "$(feed | python3 -c 'import json,sys; print(json.dumps(next(i["body"] for i in json.load(sys.stdin) if i["kind"]=="new_comment")))')"
check "deep links to the note" '"https://gl.example.org/team/web/-/merge_requests/20#note_1002"' "$(feed | python3 -c 'import json,sys; print(json.dumps(next(i["url"] for i in json.load(sys.stdin) if i["kind"]=="new_comment")))')"

echo
echo "--- an old merge request with fresh activity reads as fresh ---"
MRROW='import json,sys; print(json.dumps(next(i for i in json.load(sys.stdin) if i["kind"]=="mr_review_requested")))'
check "created stays the day it was opened" '"2026-08-01T09:00:00Z"' "$(feed | python3 -c "$MRROW" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["createdAt"]))')"
check "activity follows the merge request updated_at" '"2026-08-03T11:01:00Z"' "$(feed | python3 -c "$MRROW" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["activityAt"]))')"
check "feed is ordered newest activity first" True "$(feed | python3 -c 'import json,sys
feed = json.load(sys.stdin)
print(feed == sorted(feed, key=lambda i: i["activityAt"], reverse=True))')"
check "the merge request row has a detail line" '"3 comments"' "$(feed | python3 -c "$MRROW" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["body"]))')"

echo
echo "--- syncing again with nothing new must not re-announce ---"
connect
check "still one thread row" 1 "$(count new_comment)"
check "still one commits row" 1 "$(count changes_pushed)"

echo
echo "--- my own reply is not news ---"
python3 - "$DISC" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["merge_requests:5:20"][0]["notes"].append(
    {"id": 1004, "body": "Great, thanks.", "system": False,
     "created_at": "2026-08-03T12:00:00.000Z", "author": {"id": 42, "username": "isfaaq", "name": "Isfaaq"}})
json.dump(data, open(sys.argv[1], "w"))
PY
python3 - "$MRS" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["review_requested"][0]["updated_at"] = "2026-08-03T12:00:00.000Z"
json.dump(data, open(sys.argv[1], "w"))
PY
connect
check "own reply did not create a row" 1 "$(count new_comment)"
check "own reply still advanced the watermark" 1004 "$(docker exec sift-p-db psql -U sift -d sift -qtAc 'select last_note_id from gitlab_watched_discussions' | tr -d ' ')"

echo
echo "--- a brand new thread is a new_thread, not a new_comment ---"
python3 - "$DISC" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["merge_requests:5:20"].append(
    {"id": "d2", "notes": [
        {"id": 2001, "body": "Separate question about the tooltip.", "system": False,
         "created_at": "2026-08-03T13:00:00.000Z", "author": {"id": 9, "username": "maxime", "name": "Maxime"}}]})
json.dump(data, open(sys.argv[1], "w"))
PY
python3 - "$MRS" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["review_requested"][0]["updated_at"] = "2026-08-03T13:00:00.000Z"
json.dump(data, open(sys.argv[1], "w"))
PY
connect
check "one new_thread appeared" 1 "$(count new_thread)"
check "the older thread row is untouched" 1 "$(count new_comment)"

echo
echo "--- pushing to my own merge request is announced as well ---"
python3 - "$MRS" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["authored"][0]["sha"] = "ddd222"
data["authored"][0]["updated_at"] = "2026-08-03T14:00:00.000Z"
json.dump(data, open(sys.argv[1], "w"))
PY
connect
check "my own push raised a commits row" 2 "$(count changes_pushed)"
check "on my own merge request" 1 "$(titled "My own branch, nobody else on it")"

echo
echo "--- several events on one merge request collapse into one entry ---"
check "every row about MR 20 shares its group key" 4 "$(feed | python3 -c 'import json,sys
feed = json.load(sys.stdin)
key = next(i["groupKey"] for i in feed if i["kind"] == "mr_review_requested")
print(sum(1 for i in feed if i["groupKey"] == key))')"
check "the note anchor is not part of the key" '"gitlab:https://gl.example.org/team/web/-/merge_requests/20"' "$(feed | python3 -c 'import json,sys; print(json.dumps(next(i["groupKey"] for i in json.load(sys.stdin) if i["kind"]=="new_comment")))')"

echo
echo "--- an approval is a row, and it comes out of a system note ---"
python3 - "$DISC" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["merge_requests:5:20"].append(
    {"id": "d4", "notes": [
        {"id": 2100, "body": "approved this merge request", "system": True,
         "created_at": "2026-08-03T14:30:00.000Z", "author": {"id": 9, "username": "maxime", "name": "Maxime"}}]})
json.dump(data, open(sys.argv[1], "w"))
PY
python3 - "$MRS" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["review_requested"][0]["updated_at"] = "2026-08-03T14:30:00.000Z"
json.dump(data, open(sys.argv[1], "w"))
PY
connect
check "the approval is a row" 1 "$(count mr_approved)"
check "named after whoever approved" '"Maxime"' "$(feed | python3 -c 'import json,sys; print(json.dumps(next(i["actorName"] for i in json.load(sys.stdin) if i["kind"]=="mr_approved")))')"
check "activity is when they approved" '"2026-08-03T14:30:00Z"' "$(feed | python3 -c 'import json,sys; print(json.dumps(next(i["activityAt"] for i in json.load(sys.stdin) if i["kind"]=="mr_approved")))')"
check "it sits in the merge request's group" '"gitlab:https://gl.example.org/team/web/-/merge_requests/20"' "$(feed | python3 -c 'import json,sys; print(json.dumps(next(i["groupKey"] for i in json.load(sys.stdin) if i["kind"]=="mr_approved")))')"
check "a system note is not a thread row" 1 "$(count new_thread)"

# my own approval is my own action, so it is not news to me
python3 - "$DISC" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["merge_requests:5:20"].append(
    {"id": "d5", "notes": [
        {"id": 2101, "body": "approved this merge request", "system": True,
         "created_at": "2026-08-03T14:35:00.000Z", "author": {"id": 42, "username": "isfaaq", "name": "Isfaaq"}}]})
json.dump(data, open(sys.argv[1], "w"))
PY
python3 - "$MRS" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["review_requested"][0]["updated_at"] = "2026-08-03T14:35:00.000Z"
json.dump(data, open(sys.argv[1], "w"))
PY
connect
check "my own approval raised nothing" 1 "$(count mr_approved)"

# an unapproval is a system note whose body contains an approval's, so it must not match
python3 - "$DISC" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["merge_requests:5:20"].append(
    {"id": "d6", "notes": [
        {"id": 2102, "body": "unapproved this merge request", "system": True,
         "created_at": "2026-08-03T14:40:00.000Z", "author": {"id": 9, "username": "maxime", "name": "Maxime"}}]})
json.dump(data, open(sys.argv[1], "w"))
PY
python3 - "$MRS" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["review_requested"][0]["updated_at"] = "2026-08-03T14:40:00.000Z"
json.dump(data, open(sys.argv[1], "w"))
PY
connect
check "no unapproval row, and no second approval row" 1 "$(count mr_approved)"

echo
echo "--- merged: announced once, then there is nothing left to watch ---"
python3 - "$MRS" <<'PY'
import json, sys
path = sys.argv[1]
data = json.load(open(path))
merged = dict(data["review_requested"][0])
merged.update({
    "state": "merged",
    "merged_at": "2026-08-03T15:00:00.000Z",
    # a third person merged it, so reading merge_user rather than the author is what is under test
    "merge_user": {"id": 11, "username": "david", "name": "David"},
})
# it leaves every opened list, which is the only thing the sweep sees directly
data["review_requested"] = []
data["single"] = {"5:20": merged}
json.dump(data, open(path, "w"))
PY
connect
check "a merged row appeared"                1        "$(count mr_merged)"
check "named after whoever merged it"        '"David"' "$(feed | python3 -c 'import json,sys; print(json.dumps(next(i["actorName"] for i in json.load(sys.stdin) if i["kind"]=="mr_merged")))')"
check "activity is when it was merged"       '"2026-08-03T15:00:00Z"' "$(feed | python3 -c 'import json,sys; print(json.dumps(next(i["activityAt"] for i in json.load(sys.stdin) if i["kind"]=="mr_merged")))')"
check "the project path survived"            '"team/web"' "$(feed | python3 -c 'import json,sys; print(json.dumps(next(i["contextLabel"] for i in json.load(sys.stdin) if i["kind"]=="mr_merged")))')"
# the feed keeps its history: the row stays, marked as settled rather than disappearing
check "the waiting-for-review row stayed"    1        "$(count mr_review_requested)"
check "and it reads as settled"              1        "$(settled mr_review_requested)"
check "the thread rows stayed"               1        "$(count new_comment)"
check "it is no longer watched"              1        "$(watched)"

connect
check "merged is not announced twice"        1        "$(count mr_merged)"
check "and an event is not resolved away"    1        "$(docker exec sift-p-db psql -U sift -d sift -qtAc "select count(*) from feed_items where kind = 'mr_merged' and resolved_at is null" | tr -d ' ')"

echo
echo "--- an unchanged resource costs no discussion request ---"
BEFORE=$(grep -c "discussions" "$WORK/p-boot.log")
connect
check "no extra discussion reads when updated_at is unchanged" "$BEFORE" "$(grep -c 'discussions' "$WORK/p-boot.log")"

echo
echo "--- stage two: something I am part of only because I commented on it ---"
TITLE="Rewrite the ingest retry loop"
# nobody put the user on this one: not the author, not a reviewer, not assigned, and no to-do
python3 - "$MRS" <<'PY'
import json, sys
path = sys.argv[1]
data = json.load(open(path))
data.setdefault("single", {})["5:40"] = {
    "id": 900, "iid": 40, "title": "Rewrite the ingest retry loop",
    "state": "opened", "draft": False, "sha": "eee111", "project_id": 5, "user_notes_count": 4,
    "web_url": "https://gl.example.org/team/api/-/merge_requests/40",
    "created_at": "2026-08-01T09:00:00.000Z", "updated_at": "2026-08-03T16:00:00.000Z",
    "author": {"id": 11, "username": "david", "name": "David"},
    "references": {"full": "team/api!40"},
}
json.dump(data, open(path, "w"))
PY
cat > "$EVENTS" <<'JSON'
[{"project_id": 5, "action_name": "commented on", "created_at": "2026-08-03T16:00:00.000Z",
  "note": {"id": 3001, "body": "Does this retry on a 500 as well?", "system": false,
           "noteable_type": "MergeRequest", "noteable_iid": 40,
           "created_at": "2026-08-03T16:00:00.000Z", "author": {"id": 42, "name": "Isfaaq"}}},
 {"project_id": 5, "action_name": "commented on", "created_at": "2026-08-03T16:05:00.000Z",
  "note": {"id": 3002, "body": "Nice cleanup.", "system": false,
           "noteable_type": "Commit", "noteable_iid": null,
           "created_at": "2026-08-03T16:05:00.000Z", "author": {"id": 42, "name": "Isfaaq"}}}]
JSON
python3 - "$DISC" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["merge_requests:5:40"] = [
  {"id": "d3", "notes": [
    {"id": 3001, "body": "Does this retry on a 500 as well?", "system": False,
     "created_at": "2026-08-03T16:00:00.000Z", "author": {"id": 42, "username": "isfaaq", "name": "Isfaaq"}}]}]
json.dump(data, open(sys.argv[1], "w"))
PY
connect
check "the commented-on merge request is watched"  2 "$(watched)"
check "first sight of it only baselines"           0 "$(titled "$TITLE")"

python3 - "$DISC" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["merge_requests:5:40"][0]["notes"].append(
    {"id": 3003, "body": "Only on 5xx, yes.", "system": False,
     "created_at": "2026-08-03T17:00:00.000Z", "author": {"id": 11, "username": "david", "name": "David"}})
json.dump(data, open(sys.argv[1], "w"))
PY
python3 - "$MRS" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["single"]["5:40"]["updated_at"] = "2026-08-03T17:00:00.000Z"
json.dump(data, open(sys.argv[1], "w"))
PY
connect
check "the reply to my comment reaches me"         1 "$(titled "$TITLE")"
check "as a discussion row"                        '"new_comment"' "$(feed | python3 -c "import json,sys; print(json.dumps(next(i['kind'] for i in json.load(sys.stdin) if i['title']=='$TITLE')))")"
check "named after whoever replied"                '"David"' "$(feed | python3 -c "import json,sys; print(json.dumps(next(i['actorName'] for i in json.load(sys.stdin) if i['title']=='$TITLE')))")"

# a branch moving is news to a reviewer. it is not news to someone who left one comment.
python3 - "$MRS" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["single"]["5:40"]["sha"] = "eee222"
data["single"]["5:40"]["updated_at"] = "2026-08-03T18:00:00.000Z"
json.dump(data, open(sys.argv[1], "w"))
PY
connect
check "a push on it raised no commits row"         2 "$(count changes_pushed)"
check "and still only the one row about it"        1 "$(titled "$TITLE")"

echo
echo "--- a comment-only resource that closes stops being watched ---"
python3 - "$MRS" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["single"]["5:40"]["state"] = "closed"
json.dump(data, open(sys.argv[1], "w"))
PY
connect
check "closed, so it left the watch list"          1 "$(watched)"
check "closing it announced nothing"               1 "$(count mr_merged)"

echo
echo "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
