#!/usr/bin/env python3
"""Writes the stand-in Google's mailbox. `base`, `plus-new`, or `plus-old`.

Timestamps are relative, in milliseconds, which is what Gmail's `internalDate` is. That makes the
feed group into Today and a weekday like a real one, and it makes `after:` meaningful.

Relative to a base the caller passes, not to the moment of the call. A suite rewrites this file
between sweeps, and "now" would move every message forward each time: a message the first sweep
already read would then look newer than the watermark and be read again. Pass the same base every
time, so only what the mode adds is ever new.

    make-mail.py <mode> <path> [base epoch millis]
"""
import base64
import json
import sys
from datetime import datetime, timedelta, timezone

BASE_MS = int(sys.argv[3]) if len(sys.argv) > 3 else int(datetime.now(timezone.utc).timestamp() * 1000)


def arrived(hours_ago):
    return str(BASE_MS - int(timedelta(hours=hours_ago).total_seconds() * 1000))


def part(name, disposition):
    return {
        "mimeType": "application/octet-stream",
        "filename": name,
        "headers": [{"name": "Content-Disposition", "value": f'{disposition}; filename="{name}"'}],
    }


def text_part(body):
    """What the message says. Unpadded base64url, which is how Gmail sends a part's data."""
    encoded = base64.urlsafe_b64encode(body.encode()).decode().rstrip("=")
    return {"mimeType": "text/plain", "filename": "", "body": {"data": encoded}}


def message(ident, thread, hours_ago, sender, subject, snippet, labels, recipient=None, files=(), body=None):
    headers = [
        {"name": "Subject", "value": subject},
        {"name": "From", "value": sender},
        {"name": "Date", "value": "irrelevant, internalDate is what Sift reads"},
    ]
    if recipient:
        # what a sent row is about: mail you wrote is named after whoever received it
        headers.append({"name": "To", "value": recipient})
    payload = {"mimeType": "multipart/mixed", "headers": headers}
    # a part with no data of its own is what Gmail answers for one it holds separately
    text = text_part(body) if body else {"mimeType": "text/plain", "filename": ""}
    if files:
        # the shape a real one has: an inline image sits inside the part that draws it, and a part
        # somebody attached sits beside that part
        inline = [part(name, "inline") for name, kind in files if kind == "inline"]
        payload["parts"] = [
            {"mimeType": "multipart/related", "filename": "",
             "parts": [text] + inline},
        ] + [part(name, "attachment") for name, kind in files if kind == "attachment"]
    elif body:
        payload["parts"] = [text]
    return {
        "id": ident,
        "threadId": thread,
        "labelIds": labels,
        "internalDate": arrived(hours_ago),
        "snippet": snippet,
        "payload": payload,
    }


ADA = '"Ada Lovelace" <ada@uni.lu>'
GRETE = "grete@uni.lu"
ME = '"Sam" <sam@uni.lu>'

INBOX = ["INBOX", "UNREAD"]
SEEN = ["INBOX"]

BASE = [
    message("m1", "t1", 0.3, ADA, "Chart V2 review", "Could you look at the colour ramp?", INBOX),
    # same thread as m1: the two must collapse into one entry in the feed
    message("m2", "t1", 0.2, GRETE, "Re: Chart V2 review", "I pushed a fix for it.", INBOX),
    # the only place "projector" appears, so a search for it can only match what the message says
    message("m3", "t2", 4, GRETE, "Seminar on Thursday", "Room B on the first floor.", SEEN,
            body="Room B on the first floor. It has a projector and enough seats for everyone."),
    # the signature image is inline and must never be named as something somebody attached
    message("m4", "t3", 28, ADA, "Grant report draft", "Draft attached for your comments.", INBOX,
            files=[("grant report.pdf", "attachment"), ("signature.png", "inline")]),
    # mail you wrote is a row too: an archive you search has to hold it
    message("m5", "t4", 1, ME, "My own reply", "Thanks, looking now.", ["SENT"], ADA),
    # neither of the two below may ever become a row
    message("m6", "t5", 2, ME, "Half written", "TODO finish this", ["DRAFT"]),
    message("m7", "t6", 3, "spammer@example.com", "You have won", "Claim now", ["SPAM"]),
]

# arrives after the first read, so only this one may be added by the second read
NEW = message("m8", "t7", 0.05, ADA, "One more thing", "Forgot to say.", INBOX)

# appears below the floor after the walk back has already reached the beginning of the mailbox,
# which is the one thing a finished walk does not go looking for again
OLD = message("m9", "t8", 200, GRETE, "Ancient history", "From long before Sift looked.", INBOX)

mode = sys.argv[1]
path = sys.argv[2]

if mode == "base":
    data = BASE
elif mode == "plus-new":
    data = BASE + [NEW]
elif mode == "plus-old":
    data = BASE + [NEW, OLD]
else:
    raise SystemExit(f"unknown mode {mode}")

with open(path, "w") as handle:
    json.dump(data, handle)
print(f"{mode}: {len(data)} messages in the mailbox")
