<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/banner-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/banner-light.png">
  <img src="docs/banner-dark.png" alt="Sift: the few things that actually need you, in one place." width="100%">
</picture>

Sift is a notification hub. A source sends you many notifications, and most of them do not concern
you. Sift finds the ones that do. It ranks them. It puts them in one feed. You can read that feed
and you can search it properly. GitLab is the first source. The seams for more sources are in place.

## Why

GitLab sends you an email about a change whether or not the change concerns you. Your mail client
then holds a large pile of mail. Its search is poor. You cannot find an item in the pile again.

Sift reads the source itself. It decides which items involve you. It shows you those items in one
feed.

## What it tells you about

- **Work that asks for you.** Somebody assigns an item to you. Somebody requests your review.
  Somebody requests your approval. Somebody names you in a comment.
- **Merge requests that still wait for you.** Sift reads the current state of each one. It does not
  depend on a notification. You see a review request even when GitLab raised no to-do. You also see
  it when somebody dismissed the to-do.
- **Discussions on an item you are part of.** This covers a new reply in a thread. It also covers a
  new thread. You are part of an item when you are the author, the assignee or a reviewer. One
  comment of yours is also enough. Each thread gets one row. A thread with twelve replies stays one
  row.
- **Commits on a merge request you review.** The row names the author of the branch.
- **A merge request that merges.** The row names the person who merged it. The item leaves your list
  in a visible way.
- **A failure.** A pipeline fails. A merge request cannot merge.

Your own comments never notify you. Your own commits never notify you. GitLab writes system notes
about itself, and Sift ignores them.

## Status

These parts are built:

- accounts, and a GitLab connection you approve on GitLab itself
- every item in the list above
- the feed in day groups, with a colour for each type of event
- read and unread
- a Home page with one card for each source
- a warning when a connection no longer works. An empty list then never looks like good news.

Read and unread behaves like this:

- If you open a row, Sift marks it read.
- Each row has a control. Use it to skip an item. Use it again to bring the item back.
- If an item moves again after you read it, it becomes unread again.
- The counts above the feed are also the filter. All, Unread and Read are one click each.
- One button clears every unread item at once.
- The browser tab shows the unread count in its title and on its icon. It shows the count only while
  Sift is open in that tab.

Several events can happen on one merge request. Sift puts them in one entry, and you can collapse
that entry. The list does not repeat the same title four times. A long list loads fifty entries at
a time, and Sift asks the server for each page as you ask for it. A long history therefore does not
make the app slower.

You can read the feed with the newest activity first. You can also read it with the oldest first.
Use the second order to find the item that has waited longest.

Every page has a search field at the top. It searches every connected source, and it searches your
whole history. It does not search only the tab you are on, and it does not search only the part of
the list on screen. It forgives a typo. It forgives words in the wrong order. `is:unread`, `is:mr`,
`project:` and `from:` make a search narrow.

Sift deletes nothing, and nothing drops out of the feed. Somebody completes a to-do, or a merge
request merges. The row stays in the list. It turns grey and it says "done". The feed is your whole
history. Read and unread is the only axis you filter it on. Finished work does not count as unread,
because nothing waits for you there.

One part is not built yet. That part is email as a second source. Email covers Outlook and Gmail.

Your Sift account is an email address and a password. A source gives Sift permission to read that
source. A source never becomes your way in to Sift.

## Run it

Docker is the only prerequisite. The Gradle wrapper installs its own Java 25 toolchain. The JDK on
your machine does not matter.

```
make up        # builds and runs everything on http://localhost:7777
```

That is the whole setup of the app. On the first run Sift writes a `.env` file with a new encryption
key. It waits for the database. It applies the migrations. It then serves the app.

To connect GitLab you must also make an application on your GitLab. See **Connect GitLab** below.

The search uses the `fuzzystrmatch` extension of Postgres, and a migration creates it. The bundled
database allows this. If you point Sift at a database of your own, give its user permission to
create that extension.

To work on the code, start the parts separately. Both sides then reload.

```
make db                      # just postgres
make backend                 # the backend from source, on http://localhost:7777
cd frontend && npm run dev    # vite, on http://localhost:5174
```

`make help` lists the rest. `make logs` follows the app. `make stop` keeps your data. `make clean`
deletes the volume.

## Connect GitLab

Sift asks GitLab for permission. You do not paste a token. First you make an application on your
GitLab. You do this one time.

1. Open **User Settings**, then **Applications**, on your GitLab.
2. Give the application a name, for example `Sift`.
3. Set the redirect URI to `http://localhost:7777/api/sources/gitlab/oauth/callback`.
4. Select the scope `read_api`. Select no other scope.
5. Keep **Confidential** selected.
6. Save. GitLab then shows an Application ID and a Secret. GitLab shows the secret one time only.

Put those two values in `.env`, with the address of your GitLab:

```
SIFT_GITLAB_URL=https://gitlab.com
SIFT_GITLAB_CLIENT_ID=<the Application ID>
SIFT_GITLAB_CLIENT_SECRET=<the Secret>
SIFT_GITLAB_REDIRECT_URI=http://localhost:7777/api/sources/gitlab/oauth/callback
```

Run `make up` again. Open **Settings** and press **Connect with GitLab**. GitLab asks you to approve.
Sift then reads the source immediately. Your feed is full straight away. After that, Sift reads the
source every five minutes.

An administrator can prevent a person from making an application. If your instance does this, ask the
administrator for an application. Sift has no other way to connect a source.

The access token expires after approximately two hours, and Sift renews it without your help. If the
permission stops working, Sift says so on the feed itself. Settings then offers to connect again. You
can withdraw the permission on GitLab at any time.

You always know how current the list is. Each source tab gives the time of the last read, and a
refresh control sits next to it. Settings has the same function as a **Check now** button. Both read
the source immediately. Neither one waits for the next pass. If a read fails, Sift gives you the
reason.

The `read_api` scope is read-only, on purpose. Sift changes nothing on your GitLab. To mark a to-do
done through the API, Sift needs the full `api` scope. That scope permits reads and writes across
everything you can see. Sift gives you a link to GitLab for those actions instead.

## Ports

Postgres uses port **5433**. The backend uses port **7777**. The Vite dev server uses port **5174**.
These three ports avoid 5432, 5173 and the range 8080 to 8090. A local service stack often holds
those. `SIFT_PORT` changes the port of the backend.

The dev server sends `/api` and `/actuator` to the backend. The API and the app then share one
origin. The session cookie and the CSRF handshake behave exactly as they do in a built deployment.

## Configuration

Everything is in `.env`. Copy that file from `.env.example`. The four `SIFT_GITLAB_` values are
necessary to connect GitLab. Without them Settings tells you how to make an application.

| Variable | Meaning |
| --- | --- |
| `SIFT_ENCRYPTION_KEY` | Base64 of 32 random bytes. Required. If you change it, Sift cannot decrypt any stored token. |
| `SIFT_ALLOWED_EMAIL_DOMAINS` | A comma separated list. If it is empty, any address can register. Use an empty value on a local instance only. |
| `SIFT_GITLAB_URL` | The address of your GitLab, for example `https://gitlab.com`. |
| `SIFT_GITLAB_CLIENT_ID` | The Application ID of your GitLab application. |
| `SIFT_GITLAB_CLIENT_SECRET` | The Secret of your GitLab application. |
| `SIFT_GITLAB_REDIRECT_URI` | The redirect URI of your GitLab application. It must agree with GitLab character for character. |
| `SIFT_SYNC_INTERVAL` | The time between two reads of each source, as an ISO-8601 duration. The default is `PT5M`. Use a short value such as `PT20S` for a test. You then see a change in 20 seconds. |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | The database credentials. |

## Check it works

The test suite needs Docker and nothing else.

```
cd backend && ./gradlew test
```

It runs against a real Postgres in a container, and it starts that container itself. It covers the
parts where a silent error costs most: which items still wait for you, which items are complete, the
search and the order of the list, and the rule that you never see the items of another person.

`verify/` holds the integration suites. They drive the real backend over HTTP against a stand-in
GitLab. One of them drives the user interface in a browser.

```
verify/verify-participation.sh
```

Run one suite at a time. Each suite starts its own database and its own backend. Expect about one
minute before the first check. `verify/README.md` explains what each suite covers.

## Architecture

Sift is a backend-for-frontend. The browser holds a session cookie and nothing else. The source
tokens stay on the server, and Sift encrypts them. Every call to GitLab goes through the server. The
API and the app come from one origin, and there is no CORS configuration anywhere.

Sift also completes the GitLab approval on the server. No access token and no renewal token reaches
your browser at any point.

Sift encrypts a token with AES-GCM before the token reaches the database. Nobody can read a token
straight out of the database. On a machine only you can reach, this protects against two things: a
casual look at the data, and an old backup. A hosted instance relies on the same protection.

## Appearance

The dark theme is near-black. The light theme is a warm off-white. Brass marks the things that need
you. The light theme is a design of its own. It is not an inversion of the dark theme. The type is
Instrument Sans, with IBM Plex Mono for metadata. Both are self-hosted.

Each row has a left edge. The edge is brass while the row is unread. It turns grey after you read
the row. The work that still waits for you is therefore the first thing you see down the page.

Each row also gives the reason it is in your list, next to the time. A colour groups the reasons:
needs review, assigned to you, you were named, a discussion moved, something broke, or merged. The
reason is a word. You do not decode a colour.

The style of an action shows what the action costs you. Check a source, connect a source again and
disconnect a source sit next to each other, and the three look different. The destructive one is
red, and it asks you twice.

Pick one of three states: light, dark, or the setting of your system. Dark is the default. Sift
remembers your choice between visits. The correct theme is in place before the first paint. The page
never shows you the wrong theme first.
