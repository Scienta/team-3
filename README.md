<!-- fagdag:start (organiser section; the rest of this README came with the base app) -->
## Fagdag: start here

The harness is **<https://fagsem-h2026-harness.scienta.cloud>**, and the rules are on the printed
rules sheet. Your team slug is in `.fagdag/team`. In the first minutes:

1. **Sign in** to the harness with your Scienta Google account.
2. **Open your team's pages:** `/teams/<team>/claims` (where a person claims; agents can't) and
   `/feedback` (check results). Copy the team token from `/feedback` into `.fagdag/harness-token`,
   which is gitignored. Never commit or share it.
3. **Push an empty commit** (`git commit --allow-empty -m "chore: Test deploy" && git push`) and
   watch the **deploy** workflow in the Actions tab go green. Your service is at `TEAM_URL`,
   `https://fagsem-h2026-<team>.scienta.cloud`.
4. **Run the examples** against it: `examples/run.sh https://fagsem-h2026-<team>.scienta.cloud`.
5. **Fetch a spec.** The slugs are on `/roadmap`. The base features have precise specs; the drip
   features come as feature requests, and you write the spec and the tests. Hurl files you add to
   `examples/` are picked up by `run.sh`:

   ```bash
   H=$(cat .fagdag/harness-url) TOKEN=$(cat .fagdag/harness-token)
   curl -fsS -H "Authorization: Bearer $TOKEN" "$H/specs/speakers/spec.md"
   ```

   **Add request logging early.** The base app logs no requests. The checks' traffic is your best
   debugging aid, next to the transcripts on `/feedback`. Read the logs with `.github/fagdag/logs.sh`.

6. **Set up your workflow.** Start `REVIEW.md` now and keep it as a running log. `AGENTS.md` has the
   facts agents need (contract, commands, gotchas), and `CLAUDE.md` imports it: keep that line, and
   put your own agent instructions in `.claude/rules/`. The organisers' optional starter kit for
   Claude Code is linked from `/roadmap` and `/specs`.

## Fagdag: deploy, logs and examples

**Deploy.** Every push to `main` runs `.github/workflows/deploy.yml` on a GitHub-hosted runner. It
builds the root `Dockerfile` with the commit's SHA baked in, pushes it to your registry as
`app:<sha>` and `app:latest`, and runs `.github/fagdag/rollout.sh`. That redeploys your container,
fails unless Scaleway reports it `ready`, then waits up to 60 s for `GET /health` to answer 200 with
the new SHA as its `version`. If that fails, `app:latest` goes back to the version that was live
before (which keeps serving) and the job fails, with the container's `error_message` and its logs.
You may edit the workflow and add your own gates; if you break it, you lose your own ticks. The
organisers set its config: the secrets `SCW_ACCESS_KEY` and `SCW_SECRET_KEY` (your push key) and the
variables `SCW_REGISTRY`, `SCW_CONTAINER_ID`, `SCW_ORGANIZATION_ID` and `TEAM_URL`. Don't change them. To roll back by
hand, re-tag a good `app:<sha>` as `app:latest` and redeploy, or `git revert` and push.

**Deploy contract** (anything else, including the stack, is yours to change):

- a `Dockerfile` at the repo root, built for `linux/amd64`, which takes `--build-arg GIT_SHA` and
  bakes it in;
- the service listens on port **8080**;
- `GET /health` returns 200 `{"status": "ok", "version": "<GIT_SHA>"}` only when the service can
  reach its database within 5 s, and otherwise 503 `{"status": "db-unavailable", "version": …}`;
- the database comes from `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER` and `PGPASSWORD`: your team's
  own Postgres database, which keeps its data across deploys;
- the filesystem is ephemeral: anything written to disk is lost on redeploy;
- no other external dependencies;
- one instance, which may scale to zero when idle: beyond 80 concurrent requests the platform
  queues requests and then answers `503` itself, which counts as your failure.

**Your project is your sandbox.** Your push key can change more than your image, but scaling, size
and the domain are fixed by the organisers, and their next apply reverts any drift. Don't touch
anything outside your own service. The database is yours alone: you may connect to it directly, as
a debugging aid, with the `PG*` values your container gets. The connection pool is capped at 5
(`spring.datasource.hikari.maximum-pool-size`); tune it if you like. Two things to know: advisory
locks are "not guaranteed" on this database (use row locks, `SELECT … FOR UPDATE`), and a session
`SET` leaks to whoever gets that pooled connection next (set things inside the transaction).
`application.properties` turns `sslmode` off for the local Compose Postgres; the `Dockerfile` sets
`PGSSLMODE=require` for the container.

**Logs.** Your container's logs (stdout and stderr, timestamped) are in Scaleway Cockpit. From a
laptop or an agent, `.github/fagdag/logs.sh` prints them, oldest first, with the URL and read-only
token committed in `.fagdag/logs-url` and `.fagdag/logs-token`:

```bash
.github/fagdag/logs.sh --tail 200 --since 2026-11-06T13:00:00Z
```

`--tail` defaults to 200 (max 5000) and `--since` to the last 24 hours. The token can read your
project's logs and nothing else. To follow them live, use
[logcli](https://grafana.com/docs/loki/latest/query/logcli/):

```bash
LOKI_ADDR=$(cat .fagdag/logs-url) LOKI_BEARER_TOKEN=$(cat .fagdag/logs-token) \
  logcli query --tail '{resource_type="serverless_container"}'
```

In GitHub, run the **logs** workflow from the Actions tab. After a failed deploy, the deploy job's
last step prints the logs since the job started. A crash on start-up shows up in the logs, an image
pull error only in the `error_message`, and a build error in the Actions log.

**Examples.** `examples/run.sh [base-url]` runs the base specs' published Hurl examples (needs
[Hurl](https://hurl.dev)). They're the happy path, not a gate. The harness's checks go further.

**Build and run locally.** Docker is needed for both: the tests start Postgres with
Testcontainers, and `docker compose up -d` starts a local Postgres matching the defaults in
`application.properties` (`localhost:5432`, database, user and password `cfp`). Then `./mvnw package`
(Java 11) and `java -jar target/cfp.jar`, or `docker build .` to build what gets deployed.

**`REVIEW.md`, `workflow/` and `.claude/`.** Keep `REVIEW.md` as a running log from the start; it
has the showcase brief. Your workflow artifacts go in `workflow/` and `.claude/`.
<!-- fagdag:end -->

# CFP backend

Backend for the conference call for papers. Speakers register and submit talks, the programme
committee votes on them.

## Requirements

- Java 8
- Maven 3.5+

## Running

```
mvn spring-boot:run
```

The service starts on http://localhost:8081. It needs the local Postgres from `docker-compose.yml`
(`docker-compose up -d`); the schema is created on start-up.

## API

| Method | Path | Description |
|---|---|---|
| POST | `/speakers` | Register a speaker |
| GET | `/speakers/{id}` | Get a speaker |
| POST | `/talks` | Submit a talk (max 5 per speaker) |
| GET | `/talks/{id}` | Get a talk |
| POST | `/talks/{id}/votes` | Vote for a talk (1-3 stars) |
| GET | `/talks/top` | Top 10 talks by votes |

Formats: `LIGHTNING`, `PRESENTATION`, `WORKSHOP`, `KEYNOTE`.

## Tests

```
mvn test
```

## Deploy

Push to `master`, Jenkins builds and deploys to the test server. Ask Kjetil for access.

## Contact

cfp-team@example.com
