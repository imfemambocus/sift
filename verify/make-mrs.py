#!/usr/bin/env python3
"""Merge request fixtures for the stand-in instance."""
import json
import sys
from datetime import datetime, timedelta, timezone

BASE = "https://gitlab.example.org"
AUTHOR = {"id": 9, "username": "colleague", "name": "A Colleague",
          "avatar_url": f"{BASE}/avatar/9", "web_url": f"{BASE}/colleague"}


def mr(mr_id, iid, title, project, hours, draft=False):
    return {
        "id": mr_id, "iid": iid, "title": title, "state": "opened", "draft": draft,
        "project_id": 5, "sha": f"sha{mr_id}", "user_notes_count": 2,
        "web_url": f"{BASE}/{project}/-/merge_requests/{iid}",
        "created_at": (datetime.now(timezone.utc) - timedelta(hours=hours)).strftime("%Y-%m-%dT%H:%M:%S.000Z"),
        "author": AUTHOR,
        "references": {"full": f"{project}!{iid}"},
    }


# 501 also has a to-do pointing at it, so it must not appear twice
COVERED = mr(501, 11, "Already has a to-do", "sift/backend", 4)
# 502 has no to-do at all: this is the case that was invisible before
UNCOVERED = mr(502, 12, "Review requested with no to-do", "sift/frontend", 2)
DRAFT = mr(503, 13, "Draft, not ready for anyone", "sift/backend", 6, draft=True)
ASSIGNED_ONLY = mr(504, 14, "Assigned to you, nobody asked for review", "sift/backend", 9)

data = {
    "review_requested": [COVERED, UNCOVERED, DRAFT],
    # 502 appears in both lists, which must collapse to one row rather than break the unique key
    "assigned": [UNCOVERED, ASSIGNED_ONLY],
}

with open(sys.argv[1], "w") as handle:
    json.dump(data, handle)
print(f"review_requested={len(data['review_requested'])} assigned={len(data['assigned'])}")
