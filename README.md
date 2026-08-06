<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/banner-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/banner-light.png">
  <img src="docs/banner-dark.png" alt="Sift: the few things that actually need you, in one place." width="100%">
</picture>

A quiet notification hub. Sift pulls the things that actually concern you out of a source's
firehose, ranks them, and puts them in one feed you can read through and search properly. GitLab
first, with the seams in place for more sources later.

## Why

GitLab emails you whether or not a change concerns you, and finding anything in that pile
afterwards means fighting your mail client's search. Sift works out what actually involves you,
ranks it, and puts it in one feed with your own mute and boost rules on top.

## What it tells you about

- **Things asking for you**: assigned to you, your review requested, your approval needed, someone
  naming you in a comment.
- **Merge requests still waiting on you**, read from their current state rather than from a
  notification. A review request stays visible even if the matching to-do was never raised or has
  since been dismissed.
- **Discussions moving on anything you are part of**: new replies on a thread, and new threads on a
  merge request or issue you authored, are assigned, are reviewing, or have simply left a comment on.
  One row per thread that updates, not one per reply, so a busy discussion stays one thing to look at.
- **Commits pushed to something you are reviewing**, named after whose branch moved, which usually
  means look again.
- **A merge request being merged**, and who merged it, so the thing that was waiting on you closes
  out visibly instead of just disappearing.
- **Things that broke**: a failed pipeline, a merge request that cannot merge.

Your own comments and your own pushes never notify you, and neither does GitLab narrating itself.

## Status

Built: accounts, connecting GitLab from Settings, everything in the list above, the feed grouped by
day with colour by event type, read and unread, a Home dashboard summarising each source, and a
visible warning when a token stops working so an empty list is never mistaken for good news.

Read and unread works the way you would expect. Opening a row marks it read, each row has a toggle
for anything you want to skip or bring back, and something that moves again after you read it comes
back unread. The counts above the feed are also the filter, so All, Unread and Read are one click
away, and there is a button to clear everything unread at once. The browser tab carries the unread
count too, in its title and on its icon, while Sift is open in it.

When several things happen to one merge request, they arrive as one entry you can fold shut rather
than as the same title four times over. Long lists load fifty at a time.

You can read the feed newest first, or oldest first when what you want is whatever has been waiting
on you the longest.

There is a search field at the top of every page. It searches everything from every connected source,
not the tab you happen to be on, it forgives typos and words in the wrong order, and `is:unread`,
`is:mr`, `project:` and `from:` narrow it down.

Nothing is ever deleted, and nothing drops out. A to-do somebody completes, or a merge request that
gets merged, stays in the list and is marked "done" in grey. So the feed is the whole history, and the
only thing you filter it on is whether you have read it. Finished work does not count as unread, because
nothing is waiting on you. Rows you have read stay where they are, which is what loading in pages is
for.

Not built yet: signing in to GitLab instead of pasting a token, and email as a second source, both
Outlook and Gmail.

## Running it

Docker is the only prerequisite. The Gradle wrapper provisions its own Java 25 toolchain, so
whatever JDK you happen to have does not matter.

```
make up        # builds and runs everything on http://localhost:7777
```

That is the whole setup. It writes a `.env` with a fresh encryption key on first run, waits for
the database to be ready, applies migrations, and serves the app.

To work on it instead, run the pieces separately so both sides reload:

```
make db                      # just postgres
make backend                 # the backend from source, on http://localhost:7777
cd frontend && npm run dev    # vite, on http://localhost:5174
```

`make help` lists the rest. `make logs` follows the app, `make stop` keeps your data, `make clean`
deletes the volume.

## Connecting GitLab

Open **Settings**, put in your instance URL, and Sift shows you a link that opens GitLab's token
page with the name and scope already filled in. Paste the token back and connect.

The token is checked against your instance before it is stored, so a typo fails immediately rather
than quietly never syncing, and a first read happens in the same step so your feed is populated
straight away. After that Sift re-reads every five minutes.

If a token later expires or is revoked, Sift says so on the feed itself rather than just going
quiet, and Settings offers to replace it.

You never have to guess how current the list is. Each source's tab says when it was last synced and
offers a refresh next to it, and Settings has the same thing as a **Check now** button. Either one
reads the source immediately instead of waiting for the next pass, and if it fails it tells you why
rather than appearing to do nothing.

`read_api` is read-only on purpose. Marking a to-do done through the API would need GitLab's full
`api` scope, which is read *and* write across everything you can see, so Sift links out to GitLab
for actions instead.

## Ports

Postgres is published on **5433**, the backend listens on **7777**, and the Vite dev server runs
on **5174**. All three stay clear of 5432, 5173 and the 8080-8090 range that local service stacks
tend to occupy. Override the backend with `SIFT_PORT`.

The dev server proxies `/api` and `/actuator` to the backend, so it shares one origin with the API
and the session cookie and CSRF handshake behave exactly as they do in a built deployment.

## Configuration

Everything lives in `.env`, copied from `.env.example`.

| Variable | Meaning |
| --- | --- |
| `SIFT_ENCRYPTION_KEY` | base64 of 32 random bytes. Required. Changing it makes every stored token undecryptable. |
| `SIFT_ALLOWED_EMAIL_DOMAINS` | Comma separated. Empty lets any address register, which suits a local instance only. |
| `SIFT_SYNC_INTERVAL` | How often each source is re-read, as an ISO-8601 duration. Defaults to `PT5M`. Drop it to something like `PT20S` while trying things out, so you are not waiting five minutes to see a change. |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Database credentials. |

## Checking it works

The test suite needs nothing but Docker:

```
cd backend && ./gradlew test
```

It runs against a real Postgres in a container it starts itself, and covers the parts where being
quietly wrong would matter most: which items are still waiting on you, which have been dealt with,
and never showing you somebody else's.

`verify/` holds the rest, integration suites that drive the real backend over HTTP against a stand-in
GitLab, including one that drives the UI in a browser:

```
verify/verify-participation.sh
```

Run those one at a time. `verify/README.md` explains what each covers.

## Shape

Sift is a backend-for-frontend. The browser only ever holds a session cookie; source tokens stay
server-side, encrypted, and every call to GitLab is proxied. There is no CORS configuration
anywhere because the API and the bundle are served from one origin.

Tokens are encrypted with AES-GCM before they reach the database, so they are never readable
straight out of it. On a machine only you can reach, that mostly guards against casual snooping and
stray backups; it is the same protection a hosted instance would rely on.

## Look

Near-black in dark, warm off-white in light, with brass for the things that need you. Light is
designed rather than inverted. Type is Instrument Sans with IBM Plex Mono for metadata, both
self-hosted.

Each row carries a left edge that is brass while you have not read it and grey once you have, so what
is still waiting is the first thing you see down the page. **Why** it is in your list is written out
next to the timestamp, coloured by kind: needs review, assigned to you, you were named, a discussion
moved, something broke, or merged. A word rather than a colour to decode, and the colour groups those
words so a long list still scans.

Actions are styled by what they cost you. Checking a source, replacing a token and disconnecting one
sit next to each other and look nothing alike, and the destructive one is red and asks twice.

Pick from three states, light, dark, or match system. Dark is the default, your choice is
remembered between visits, and the right theme is in place before the first paint, so the page
never flashes the wrong one at you.
