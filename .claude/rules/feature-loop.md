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
  `workflow/allocate.sh claim <slug>` before branching, and `release`/`done` it when the iteration ends,
  so two people never build the same feature (step 1). That pushes only the `agent-allocations` ledger
  branch — never `main` — and is the one push the loop may make unasked.
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
1. **FAULTY checks on a claimed feature** (regressions bleed points) — fix first.
2. **A released, unclaimed feature with no checks passing** (new points).
3. **A partially-passing feature** (finish it).

For a FAULTY check, pull the failing request and transcript:
```bash
curl -fsS -H "Authorization: Bearer $TOKEN" "$H/api/teams/$TEAM/feedback/timeline?check=<feature>/<check>"
```

Then **claim the first candidate you can**, so teammates running this loop don't build the same thing.
Walk the list in priority order and try to grab each:
```bash
workflow/allocate.sh claim <slug>    # exit 0 = it's yours; 10 = a teammate has it; 11 = contention, retry later
```
Take the first candidate that exits `0` as this iteration's target. On exit `10`, move to the next
candidate. If nothing is actionable (all released specs done, no faults) **or** every candidate is
already claimed by a teammate, **end the iteration** and report that — back off without claiming anything.

### 2. Branch — isolate the work

Once a target is claimed (step 1) and **before any edits**, cut a fresh feature branch off an up-to-date `main`.
All of this iteration's work (code, tests, examples) stays on that branch; one branch per feature.

```bash
git checkout main && git pull --ff-only          # start from the latest main
git switch -c feature/<slug>                      # e.g. feature/acceptance; one branch per feature
```

If the branch already exists (a retried or resumed feature), switch to it (`git switch feature/<slug>`)
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

The stack is Spring Boot 2.7 / Java 11, plain `JdbcTemplate`, no ORM. Follow the patterns already here:

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
Files changed: <list>
Open questions for the product owner: <none | …>

Status: on branch feature/<slug>, ready for a person to review, merge to main (which deploys) and claim
on the harness — agents can't merge, deploy or claim.
Evidence: passing tests above + (once deployed) green harness checks for <slug>.
```

### 10. Next iteration

**Release the allocation** so teammates aren't blocked: `workflow/allocate.sh done <slug>` when the
feature was implemented, or `release <slug>` if the iteration ended before finishing. Do this even when
the run failed or you backed off after claiming.

Report, then stop that iteration, leaving the feature branch in place for a person to review and merge.
The loop picks the next target on the following run — starting again from an up-to-date `main` (step 2),
not from the previous feature's branch. The human still has to merge, deploy and claim; the loop should
not wait on that — it moves to the next actionable item (or reports "nothing actionable" and backs off).
