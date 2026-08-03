<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/banner-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/banner-light.png">
  <img src="docs/banner-dark.png" alt="Sift: the few things that actually need you, in one place." width="100%">
</picture>

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

- accounts: register, sign in, sign out, session persistence
- the app shell: icon rail, auth screens, light and dark themes
- the encrypted credential store, ready for a GitLab token
- database migrations

Not built yet: the GitLab sync, the feed itself, the packaged single-container image.

## Running it

Docker is the only prerequisite for the backend. The Gradle wrapper provisions its own Java 25
toolchain, so whatever JDK you happen to have does not matter.

```
make db        # starts postgres, and writes .env with a fresh encryption key on first run
make backend   # runs the backend on http://localhost:7777
```

Then, in a second terminal, for the frontend:

```
cd frontend
npm install
npm run dev    # http://localhost:5174
```

`make help` lists the rest. `make stop` keeps your data, `make clean` deletes the volume.

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
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Database credentials. |

## Shape

Sift is a backend-for-frontend. The browser only ever holds a session cookie; source tokens stay
server-side, encrypted, and every call to GitLab is proxied. There is no CORS configuration
anywhere because the API and the bundle are served from one origin.

Tokens are encrypted with AES-GCM before they reach the database, so they are never readable
straight out of it. On a machine only you can reach, that mostly guards against casual snooping and
stray backups; it is the same protection a hosted instance would rely on.

## Look

Near-black in dark, warm off-white in light, with brass as the single accent. Light is designed
rather than inverted. Type is Instrument Sans with IBM Plex Mono for metadata, both self-hosted.

Pick from three states, light, dark, or match system. Dark is the default, your choice is
remembered between visits, and the right theme is in place before the first paint, so the page
never flashes the wrong one at you.
