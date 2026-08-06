# Verification suites

Integration checks that drive the real backend over HTTP against a stand-in GitLab, on a real
Postgres. There are no unit tests yet; these are what stands in for them, and they have caught every
non-obvious bug in this project so far.

Each script starts everything it needs and tears it down again.

```
./verify-sync.sh              connect, priority mapping, paging, resolve, disconnect, a revoked token
./verify-mr.sh                merge requests read as state, and the three de-duplications
./verify-participation.sh     threads, replies, pushed commits, grouping, and what must NOT be emitted
./verify-read.sh              marking items read, tenancy on it, and what a later sync un-reads
./verify-unreadable-token.sh  a token that will not decrypt, and "check now"
./verify-feed-ui.sh           the same flow in a browser, plus search, grouping, the tab badge, and
                              an item completed upstream that stays in the feed as history
```

## Two rules

**Run one at a time, in the foreground.** They share the stub on 7788, the backend on 7779 and
Postgres on 5439. Two at once, or one launched in the background, produces a wall of failures that
look like application bugs and are not. If everything fails at once, check the boot log for
`Started SiftApplication` before believing any of it.

**Those ports are chosen to miss a running instance.** `compose` publishes Postgres on 5433 and the
app on 7777, so nothing here can touch an instance you are using.

`verify-feed-ui.sh` refuses to start if something already serves 5174, rather than kill it. A dev server
someone else started would otherwise be driven instead of its own, and the whole run would go at that
person's backend. Stop yours, or wait for a previous run to clean up, and start it again.

## Scratch and screenshots

Logs, cookies and the fixtures a run mutates go to a temp directory. Override with
`SIFT_VERIFY_WORK=/some/path`, and `SIFT_VERIFY_SHOTS` for where `verify-feed-ui.sh` saves images.

## The browser suite needs Puppeteer

It is deliberately not a dependency of the project. Install it transiently and leave it in
`node_modules`:

```
npm --prefix ../frontend install --no-save puppeteer
./verify-feed-ui.sh
```

Beware: **any** later `npm install` prunes unsaved packages, so re-check that it still resolves
before assuming the suite can run.

## The stand-in GitLab

`fake-gitlab.py` serves `/api/v4/user`, `todos`, `merge_requests`, `issues`, a resource's
`discussions` and the caller's own `events`, from JSON fixtures it re-reads on every request, so a
test can change what the instance returns between sweeps. `make-todos.py` and `make-mrs.py` write
those fixtures. Touching the file named by `REVOKE_FILE` makes it reject every token, which stands in
for a revoked PAT.

Unconfigured routes answer with an empty list rather than 404, so a suite that does not care about
issues, discussions or the activity feed is not broken by the app reading them.

Two routes share one fixture key. `single`, keyed `project:iid`, answers both
`GET /projects/:id/merge_requests/:iid` (what became of something that left the opened lists) and
`GET /projects/:id/merge_requests?iids[]=` (the resources found in the activity feed). Absent means
404 for the first and simply missing from the second, and `state` is honoured on the list, so
flipping a record to `merged` or `closed` is how a suite makes something depart.

## The Testcontainers suite is separate

`cd backend && ./gradlew test` runs 36 in-process tests against a real Postgres 17 container: the
diffing rules, tenancy, the credential sync outcome and the GitLab adapter's de-duplications. It needs
no shell script and no free ports, so it is the one to reach for first. The suites here cover what it
cannot: real HTTP, a real browser, and the packaged container.
