# Verification suites

Integration checks that drive the real backend over HTTP against a stand-in GitLab, on a real
Postgres. There are no unit tests yet; these are what stands in for them, and they have caught every
non-obvious bug in this project so far.

Each script starts everything it needs and tears it down again.

```
./verify-sync.sh              connect, priority mapping, paging, resolve, disconnect, a revoked token
./verify-mr.sh                merge requests read as state, and the three de-duplications
./verify-participation.sh     threads, replies, pushed commits, and everything that must NOT be emitted
./verify-read.sh              marking items read, tenancy on it, and what a later sync un-reads
./verify-unreadable-token.sh  a token that will not decrypt, and "check now"
./verify-feed-ui.sh           the same flow driven in a browser, with screenshots
```

## Two rules

**Run one at a time, in the foreground.** They share the stub on 7788, the backend on 7779 and
Postgres on 5439. Two at once, or one launched in the background, produces a wall of failures that
look like application bugs and are not. If everything fails at once, check the boot log for
`Started SiftApplication` before believing any of it.

**Those ports are chosen to miss a running instance.** `compose` publishes Postgres on 5433 and the
app on 7777, so nothing here can touch an instance you are using.

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

`fake-gitlab.py` serves `/api/v4/user`, `todos`, `merge_requests`, `issues` and a resource's
`discussions`, from JSON fixtures it re-reads on every request, so a test can change what the
instance returns between sweeps. `make-todos.py` and `make-mrs.py` write those fixtures. Touching the
file named by `REVOKE_FILE` makes it reject every token, which stands in for a revoked PAT.

Unconfigured routes answer with an empty list rather than 404, so a suite that does not care about
issues or discussions is not broken by the app reading them.
