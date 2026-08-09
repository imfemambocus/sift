#!/usr/bin/env python3
"""Does to one message what Gmail itself would, in the stand-in Google's mailbox.

Two files change together, because that is what Gmail does. The message gains or loses a label, or
leaves the mailbox altogether, and the mailbox records what happened in its history, which is where
Sift reads it back from. A fixture that only edited the labels would prove nothing: nothing re-reads
a message Sift already holds.

    relabel-mail.py <mailbox path> <history path> <message id> read|unread|trash|restore|delete
"""
import json
import os
import sys

FIRST_HISTORY_ID = 1000
UNREAD = "UNREAD"
TRASH = "TRASH"
ADDED = "labelsAdded"
REMOVED = "labelsRemoved"
DELETED = "messagesDeleted"

# what each decision does to the message, and what the mailbox records about it
DECISIONS = {
    "read": (UNREAD, False, REMOVED),
    "unread": (UNREAD, True, ADDED),
    "trash": (TRASH, True, ADDED),
    "restore": (TRASH, False, REMOVED),
    "delete": (None, None, DELETED),
}

mailbox_path, history_path, message_id, decision = sys.argv[1:5]
if decision not in DECISIONS:
    raise SystemExit(f"unknown decision {decision}")
label, add, field = DECISIONS[decision]

with open(mailbox_path) as handle:
    mailbox = json.load(handle)

for index, message in enumerate(mailbox):
    if message["id"] != message_id:
        continue
    if field == DELETED:
        mailbox.pop(index)
    else:
        labels = [name for name in message["labelIds"] if name != label]
        if add:
            labels.append(label)
        message["labelIds"] = labels
    break
else:
    raise SystemExit(f"no message {message_id} in {mailbox_path}")

history = []
if os.path.exists(history_path):
    with open(history_path) as handle:
        history = json.load(handle)

history.append({
    "id": (history[-1]["id"] if history else FIRST_HISTORY_ID) + 1,
    "messageId": message_id,
    "field": field,
    "label": label,
})

with open(mailbox_path, "w") as handle:
    json.dump(mailbox, handle)
with open(history_path, "w") as handle:
    json.dump(history, handle)
print(f"{message_id}: {decision}, at history {history[-1]['id']}")
