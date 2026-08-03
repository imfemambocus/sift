#!/usr/bin/env python3
"""Writes the fake instance's todo dataset. `full`, `shrunk`, or `many:<count>`."""
import json
import sys
from datetime import datetime, timedelta, timezone

# hours back from now, so the feed groups into Today / Yesterday / a weekday like a real one
AGE_HOURS = {1: 0.2, 2: 1.5, 3: 5, 4: 26, 5: 30, 6: 50, 7: 74, 8: 3}


def created(todo_id):
    hours = AGE_HOURS.get(todo_id, (todo_id % 90) + 1)
    stamp = datetime.now(timezone.utc) - timedelta(hours=hours)
    return stamp.strftime("%Y-%m-%dT%H:%M:%S.000Z")

PROJECT = {
    "id": 7,
    "name": "backend",
    "path_with_namespace": "sift/backend",
    "web_url": "https://gitlab.example.org/sift/backend",
}
FRONTEND = {
    "id": 8,
    "name": "frontend",
    "path_with_namespace": "sift/frontend",
    "web_url": "https://gitlab.example.org/sift/frontend",
}
GROUP = {"id": 3, "name": "Platform", "full_path": "lcsb/platform",
         "web_url": "https://gitlab.example.org/groups/lcsb/platform"}
AUTHOR = {"id": 9, "username": "colleague", "name": "A Colleague",
          "avatar_url": "https://gitlab.example.org/avatar/9", "web_url": "https://gitlab.example.org/colleague"}


def todo(todo_id, action, title, project=PROJECT, group=None, target=True, body=None):
    return {
        "id": todo_id,
        "action_name": action,
        "target_type": "MergeRequest",
        "target_url": f"https://gitlab.example.org/sift/backend/-/merge_requests/{todo_id}",
        "body": body if body is not None else title,
        "state": "pending",
        "created_at": created(todo_id),
        "author": AUTHOR,
        "project": project,
        "group": group,
        "target": {"title": title, "iid": todo_id,
                   "web_url": f"https://gitlab.example.org/x/-/merge_requests/{todo_id}"} if target else None,
    }


FULL = [
    todo(1, "assigned", "Add rate limiting to the sync sweep"),
    todo(2, "review_requested", "Fix the theme flash on first paint", FRONTEND),
    todo(3, "mentioned", "Weekly sync notes"),
    todo(4, "marked", "An old idea worth keeping"),
    todo(5, "build_failed", "Pipeline failed on main"),
    todo(6, "an_action_gitlab_added_later", "Something Sift has never seen"),
    # group-level todo: no project at all, so contextLabel must fall back to the group
    todo(7, "approval_required", "Quarterly access review", project=None, group=GROUP),
    # no target: the title has to fall back to the body
    todo(8, "directly_addressed", "ignored", target=False, body="Can you look at this today?"),
]

SHRUNK = [FULL[0], FULL[2], FULL[4], FULL[5], FULL[6], FULL[7]]

mode = sys.argv[1]
path = sys.argv[2]

if mode == "full":
    data = FULL
elif mode == "shrunk":
    data = SHRUNK
elif mode.startswith("many:"):
    count = int(mode.split(":")[1])
    data = [todo(100 + index, "assigned", f"Bulk item {index}") for index in range(count)]
else:
    raise SystemExit(f"unknown mode {mode}")

with open(path, "w") as handle:
    json.dump(data, handle)
print(f"{mode}: {len(data)} todos")
