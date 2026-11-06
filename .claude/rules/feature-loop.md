# Feature loop

A repeatable procedure for one agent iteration: find a released feature request that isn't done,
pick it, plan it, implement it, test every rule in its spec, and hand back a test report. One
iteration does **one** feature. Loop again for the next. See `AGENTS.md` for the facts this relies on
(deploy contract, harness commands, platform gotchas); this file is the *how*.

## Guardrails (read first, every iteration)

- **Never claim a feature.** Only a person claims, on the harness, and a claim is permanent. The most
  the loop does is report "ready to claim" **with evidence** (green checks / passing tests).
- **Work on a feature branch; never deploy.** Every feature's work happens on its own branch, created
  before any edits (step 2). Committing to that branch is fine. **Never** merge to `main`, push `main`,
  or otherwise deploy without being asked — pushing to `main` deploys. Leave the branch for a person to
  review and merge.
- **Claim before building.** Teammates run this loop on their own machines too. Grab a task with
  `workflow/allocate.sh grab <slug>` before branching, and `release`/`done` it when the iteration ends,
  so two people never build the same feature (step 1). That pushes only the `agent-allocations` ledger
  branch — never `main` — and is the one push the loop may make unasked.
- **Keep `REVIEW.md` current — every iteration.** It's the running log judged at 17:00 (every heading
  needs text to be eligible). Step 9b appends to it with `workflow/review-log.sh`, which commits only
  `REVIEW.md` straight to `origin/main` from a private worktree. That docs-only push doesn't deploy
  (`deploy.yml` ignores `**/*.md`) and is the **only** push to `main` the loop may make unasked.
- **Never touch the hands-off list** (`AGENTS.md` → "Hands off"): `.fagdag/` committed files, the
  `SCW_*`/`TEAM_URL` secrets and variables, the deploy workflow's org config, anything outside this
  team's own service. Never print or commit `.fagdag/harness-token`.
- **Don't break the deploy contract.** Port 8080, `GET /health` semantics, `PG*` env, ephemeral disk.
- **A feature request is not a full spec.** It gives business rules and the API surface, not status
  codes, edge cases or error handling. The team decides those and **writes them down** (step 3). A
  person can ask the organisers' product owner about business rules; surface open questions, don't guess.
- **Stop and ask** when a business rule is genuinely ambiguous, when a change would touch a base
  feature's behaviour, or when the only way forward is to guess at a rule that affects scoring.

## Preconditions

- `.fagdag/harness-token` holds the **bare** token (no `Authorization: Bearer` prefix). Sanity-check:
  `curl -fsS -H "Authorization: Bearer $(cat .fagdag/harness-token)" "$(cat .fagdag/harness-url)/specs/speakers/spec.md"` returns 200.
- Docker is running (tests use Testcontainers Postgres).
- Harness is rate-limited: bursts of 5 then ~1 req/s; `429` carries `Retry-After`. Space calls out.
- Allocation is coordinated with teammates through `workflow/allocate.sh` (an append-only ledger on the
  `agent-allocations` branch; see `workflow/task-allocation.md`). Your git identity (`git config
  user.email`, or `$MEMBER`) names your claims, and you need push access to that branch.

## The loop

### 1. Discover — what needs doing

```bash
H=$(cat .fagdag/harness-url) TEAM=$(cat .fagdag/team) TOKEN=$(cat .fagdag/harness-token)
# released specs (feature slugs):
curl -fsS -H "Authorization: Bearer $TOKEN" "$H/specs" | grep -oE '/specs/[a-z0-9-]+' | sort -u
# current state of every feature/check:
curl -fsS -H "Authorization: Bearer $TOKEN" "$H/api/teams/$TEAM/feedback"
```

Compare the two and build an **ordered list of candidates**, highest priority first:
1. **Regression bug tasks** — every FAULTY check on a *claimed* feature. A regression bleeds points each
   tick, so it outranks all feature work and **must be cleared before any feature request** (see
   "Regression bug tasks" below). Treat **each faulty check as its own task** with the deterministic id
   `bug-<feature>-<check>` (e.g. `bug-ratings-check-3`) — deterministic so two members who spot the same
   regression compute the same id and the ledger grab stops them both taking it. Grab it in the ledger
   before fixing, like a feature (two of us once wrote the same fix at the same time).
2. **A released, unclaimed feature with no checks passing** (new points).
3. **A partially-passing feature** (finish it).

For a FAULTY check, pull the request it failed on and the transcript — the raw material for the bug report:
```bash
curl -fsS -H "Authorization: Bearer $TOKEN" "$H/api/teams/$TEAM/feedback/timeline?check=<feature>/<check>"
```

**Grab the first candidate you can**, highest priority first, so teammates running this loop don't take
the same work (this is the ledger lock, *not* the harness claim). Use `bug-<feature>-<check>` as the id
for a regression, or the feature `<slug>` for feature work:
```bash
workflow/allocate.sh grab <id>    # exit 0 = it's yours; 10 = a teammate has it; 11 = contention, retry later
```
Take the first that exits `0` as this iteration's target. On exit `10`, move to the next. If nothing is
actionable — no faults left to grab and no feature to build — **end the iteration** and report that,
grabbing nothing. A regression you grab goes to **Regression bug tasks** next; a feature goes to step 2.

### Regression bug tasks — clear before any feature

A grabbed `bug-<feature>-<check>` task jumps ahead of all feature work. Work it like this, then return to
step 1 (the loop keeps clearing regressions until none are grabbable before it builds a feature):

1. **Report the problem.** From the transcript, write a short bug report: the faulty check and its
   `bug-<feature>-<check>` id, the request it failed on (method + templated path), what our service
   answered vs what the rule expects, and a root-cause hypothesis. Emit it in the iteration output and
   log a line to `REVIEW.md` with `workflow/review-log.sh` so the team sees it.
2. **Branch** `bugfix/<taskId>` off an up-to-date `main` (e.g. `bugfix/bug-ratings-check-3`) — same rules
   as step 2, only the prefix differs; never work on `main`.
3. **Reproduce, then fix.** Add a test that fails the way the check does, then fix it in the house style
   (steps 4–5). If the fault only reproduces under platform behaviour local Testcontainers doesn't share
   (e.g. a stricter DB isolation that raises `40001`), say so and get as close as you can — a focused
   unit test of the fix logic counts.
4. **Verify + regression review** (steps 7–8): `./mvnw package` green and no other feature regresses.
5. **Emit the bug report** with the fix and evidence (reproducing test now passing, build green), then
   **release** the task (`workflow/allocate.sh done <taskId>`). A person still reviews, merges and deploys.

### 2. Branch — isolate the work

Once a target is claimed (step 1) and **before any edits**, cut a fresh feature branch off an up-to-date `main`.
All of this iteration's work (code, tests, examples) stays on that branch; one branch per feature.

```bash
git checkout main && git pull --ff-only          # start from the latest main
git switch -c feature/<slug>                      # feature work; a regression uses bugfix/<taskId>
```

Feature work uses `feature/<slug>`; a regression bug task uses `bugfix/<taskId>` (e.g.
`bugfix/bug-ratings-check-3`). If the branch already exists (a retried or resumed task), switch to it
rather than creating a second one. Never work directly on `main`. Do not push or open a PR unless asked;
leave the branch local for a person to review and merge (merging to `main` is what deploys).

### 3. Spec it — turn the request into decisions

- Fetch the spec: `curl -fsS -H "Authorization: Bearer $TOKEN" "$H/specs/<feature>/spec.md"`.
- Read any specs it references (e.g. co-speakers extends the `talks` Talk shape).
- Write a short **decision block** (in the test report, and as comments where the rules live): the
  status codes, edge cases and error handling the spec leaves open. Cross-check numbers against the
  spec, not the stale base `README.md` (e.g. the submission limit is **3**, not the README's "5").
- Extract a **requirements checklist**: one line per rule the spec states, each testable. This list
  drives steps 6–7 and the coverage table in the report.

### 4. Plan — mirror the existing shape

The stack is Spring Boot 4 / Java 17 bytecode on a Java 25 runtime (Jackson 3: `tools.jackson.*`; `jakarta.*` not `javax.*`; responses kept Boot-2.7-compatible by `CompatibilityConfig`, pinned by `CompatibilityTest`), plain `JdbcTemplate`, no ORM. Follow the patterns already here:

- **Schema**: append to `src/main/resources/schema.sql` only — every statement `IF NOT EXISTS`, never
  edit old ones (it runs on every start-up).
- **DAO** per table (`*Dao.java`): constructor-injected `JdbcTemplate`, raw SQL. For limits/uniqueness
  that must hold under concurrency, use **row locks** (`SELECT … FOR UPDATE`) or conditional writes —
  advisory locks aren't guaranteed here. See `TalkDao.insertIfBelow` and `CoSpeakerDao.lockTalk`.
- **Service** (`ConferenceService`): business rules, `@Transactional`, throw `ResponseStatusException`
  with the status codes decided in step 3. Keep the connection pool (capped at 5) in mind — batch
  lookups in list endpoints rather than N+1 (see `ConferenceService.addCoSpeakers`).
- **Controller**: thin `@RestController` method delegating to the service (see `TalkController`).
- **DTO**: extend the existing DTO when a spec adds "one more field"; initialise collections so they
  serialise as `[]`, not `null`.

List the files to touch and the edge cases before writing code. `co-speakers` (DAO + service methods +
controller routes + DTO field + schema) is the reference implementation to copy.

### 5. Implement

Write the change following step 4, on the feature branch from step 2. Keep edits minimal and in the
house style. Request logging already
exists (`RequestLoggingFilter`); lean on `.github/fagdag/logs.sh` to see what the checks send.

### 6. Test — cover every rule in the spec

- **Integration test** `src/test/java/no/scienta/cfp/<Feature>Test.java`, modelled on `TalksTest` /
  `CoSpeakersTest`: `@SpringBootTest(RANDOM_PORT)` + `java.net.http.HttpClient` + `TestDatabase.fresh`.
  One test (or assertion group) per line of the step-3 requirements checklist, **including** the
  decided edge cases (404/409/400, idempotency, ordering) and a **concurrency test** for any limit.
- **Hurl examples** `examples/<feature>.hurl` (picked up by `examples/run.sh`): happy path plus the key
  edge cases, same format as `examples/talks.hurl`. Drip features have no published examples — write
  your own.
- Every requirement must map to at least one test. If a rule can't be tested, say so in the report.

### 7. Verify

```bash
./mvnw package        # compiles + runs all tests under Testcontainers (Docker required)
```
Build must be green and **no base feature may regress** (all pre-existing tests still pass; new DTO
fields are additive). If Hurl is installed, also run `examples/run.sh` against a local instance
(`docker compose up -d` then `java -jar target/cfp.jar`).

### 8. Regression review — don't break earlier specs

Start with `.claude/rules/house-conventions.md`: status-code precedence, no session-level `SET` or
`isolation` (the 14:07 incident), concurrency tests for every limit. Check the change against each rule.

A new feature must not violate any requirement of a feature that is already done. Running the old tests
(step 7) is necessary but not sufficient — they only catch what they already assert. After the build is
green, do an explicit read-through:

- **Re-read every earlier implemented spec**, not just the one you touched — each released, done
  feature's `spec.md`:
  ```bash
  H=$(cat .fagdag/harness-url) TOKEN=$(cat .fagdag/harness-token)
  for f in <done-slugs>; do echo "== $f =="; curl -fsS -H "Authorization: Bearer $TOKEN" "$H/specs/$f/spec.md"; done
  ```
  Walk each spec's requirements against your change and name any interaction. Pay special attention to
  **shared shapes and endpoints** the new feature also returns or mutates — e.g. the `Talk` shape and
  `talk.status` are shared by `talks`, `co-speakers` and `acceptance`; a new field or status value must
  still satisfy the talks spec's ordering, submission limit and existing fields.
- **Evaluate the earlier regression tests.** Confirm the existing `*Test.java` still assert those
  requirements and still pass, then judge whether the new feature creates an interaction they don't yet
  cover (e.g. "how do co-speakers behave on a WITHDRAWN talk?", "does the new status leak into
  `GET /talks/top` ordering?"). Where there's a gap, **add a regression test** for it in the relevant
  test class and re-run `./mvnw package`.
- If any earlier requirement can't be preserved, **stop and ask** — never ship a feature that bleeds an
  earlier feature's points. Record the outcome in the report's **Regression review** section.

### 9. Test report — the deliverable

Emit a report in this shape:

```
## Feature: <slug>  (tick <n>, <YYYY-MM-DD>)

Decisions (rules the request left open):
- <case> -> <status code / behaviour>

Requirement coverage:
| # | Spec requirement            | Test(s)                         | Result |
|---|-----------------------------|---------------------------------|--------|
| 1 | <rule from spec>            | <FeatureTest#method / hurl>     | PASS   |
| ...                                                                              |

Build: ./mvnw package -> SUCCESS (N tests, 0 failures)   [or the failure output]
Regression review: specs re-read [<done-slugs>]; interactions found [<none | …>];
  regression tests added [<none | names>]; all prior tests pass
Branch: feature/<slug>  (not merged, not pushed)
Allocation: claimed via agent-allocations ledger; released/done at iteration end
REVIEW.md: claim-log row [+ harness-fault entry] appended via workflow/review-log.sh (<sha>)
Files changed: <list>
Open questions for the product owner: <none | …>

Pre-claim gate (house-conventions.md): after deploy, run.sh + adversarial cases green against the deployed URL, no new 500s
Status: on branch feature/<slug>, ready for a person to review, merge to main (which deploys) and claim
on the harness — agents can't merge, deploy or claim.
Evidence: passing tests above + (once deployed) green harness checks for <slug>.
```

For a **regression bug task**, emit a bug report instead of the feature shape:

```
## Bug: <taskId>  (<feature>/<check> FAULTY, tick <n>, <YYYY-MM-DD>)

Problem: <method> <templated path> -> our service answered <...>; the <feature> spec expects <...>.
Root cause: <hypothesis / confirmed cause>.
Fix: <what changed, which files>.
Reproduce: <test that fails before / passes after — or why it can't reproduce locally>.
Build: ./mvnw package -> SUCCESS (N tests, 0 failures)
Regression review: no other feature regresses (all prior tests pass).
Branch: bugfix/<taskId>  (not merged, not pushed)
Allocation: grabbed via agent-allocations ledger; done at iteration end.
Status: on branch bugfix/<taskId>, ready for a person to review, merge and deploy.
```

### 9b. Log it in `REVIEW.md` — don't skip

Append this iteration to the running log on `main` (from any branch; your working tree is untouched):

```bash
# claim log row (section 3): feature, claimed/skipped/ready-to-claim, tick, evidence held
workflow/review-log.sh 3 "<slug> claim log" <<'EOF'
| <slug> | ready to claim (person to confirm) | <tick> | <tests + hurl that are green, regression review> |
EOF
# a FAULTY check you fixed (section 4): what the harness sent, why our tests missed it, the fix commit
workflow/review-log.sh 4 "<slug> harness fault" <<'EOF'
- **<feature>/<check>, ticks <a>–<b>:** <request> failed because <cause>. Fixed in `<sha>`.
  **Why we missed it:** <gap in our tests/review>.
EOF
```

- Always write the section 3 row (also for a skip or back-off, with the reason). Write section 4 whenever
  the iteration fixed a FAULTY check. Add to section 2 or 5 when the iteration introduced a new review
  gate or a reusable artifact.
- When a **person** claims on the harness, they (or the agent they ask) update that row's status to
  `claimed` with the tick — agents never write `claimed` on their own.
- If `review-log.sh` exits non-zero, say so in the report; don't silently drop the entry.

### 10. Next iteration

**Release the allocation** so teammates aren't blocked: `workflow/allocate.sh done <slug>` when the
feature was implemented, or `release <slug>` if the iteration ended before finishing. Do this even when
the run failed or you backed off after claiming.

Report, then stop that iteration, leaving the feature branch in place for a person to review and merge.
The loop picks the next target on the following run — starting again from an up-to-date `main` (step 2),
not from the previous feature's branch. The human still has to merge, deploy and claim; the loop should
not wait on that — it moves to the next actionable item (or reports "nothing actionable" and backs off).
