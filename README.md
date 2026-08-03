# Sift

A quiet notification hub. Sift pulls the things that actually concern you out of a source's
firehose, ranks them, and puts them in one feed you can search properly. GitLab first, with the
seams in place for more sources later.

## Why

GitLab emails you whether or not a change concerns you, and finding anything in that pile
afterwards means fighting your mail client's search. Sift reads GitLab's To-Do list instead,
which is already scoped to you personally, applies your own mute and boost rules on top, and puts
a fuzzy command palette over the result.

## Status

Built and verified:

- accounts: register, login, logout, session persistence
- the encrypted credential store, ready for a GitLab token
- database migrations

Not built yet: the GitLab sync, the feed, the React frontend, the packaged single-container image.

## Running it

Docker is the only prerequisite. The Gradle wrapper provisions its own Java 25 toolchain, so
whatever JDK you happen to have does not matter.

```
make db        # starts postgres, and writes .env with a fresh encryption key on first run
make backend   # runs the backend on http://localhost:7777
```

`make help` lists the rest. `make stop` keeps your data, `make clean` deletes the volume.

## Ports

Postgres is published on **5433** and the app listens on **7777**, both chosen to stay clear of
the 5432 and 8080-8090 ranges that local service stacks tend to occupy. Override the app port
with `SIFT_PORT`.

## Configuration

Everything lives in `.env`, copied from `.env.example`.

| Variable | Meaning |
| --- | --- |
| `SIFT_ENCRYPTION_KEY` | base64 of 32 random bytes. Required. Changing it makes every stored token undecryptable. |
| `SIFT_ALLOWED_EMAIL_DOMAINS` | Comma separated. Empty lets any address register, which suits a local instance only. |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Database credentials. |

## Shape

Sift is a backend-for-frontend. The browser only ever holds a session cookie; source tokens stay
server-side, encrypted, and every call to GitLab is proxied. There is no CORS configuration
anywhere because the API and the bundle are served from one origin.

Tokens are encrypted with AES-GCM through a JPA attribute converter, so they are never readable
straight out of the database. On a local instance that mostly guards against casual snooping and
stray volume backups; the same code path is what protects a hosted instance properly.
