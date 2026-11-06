# REVIEW

> **Showcase brief (17:00, 4 minutes, hard stop).** You screen-share this repo from the shared
> presenter laptop, in a random order drawn at 17:00. No slides, and no harness screens.
>
> 1. **Our workflow in one minute:** section 1, pointing at the files in `workflow/` and `.claude/`.
> 2. **One story:** a bug our review caught before the harness did, or one the harness caught that
>    we missed, and why. Use the claim log.
> 3. **One artifact we'd reuse**, opened on screen.
>
> Keep this file as a running log from t = 0, so 17:00–17:05 is for tidying, not writing. To be
> eligible to win, every heading below needs some text. The comments under each heading are prompts
> and don't count; replace them with your own words.

## 1. Workflow

Several people run the same agent loop in parallel, each on their own machine:

1. **Discover**: the agent reads the released specs and the team's harness feedback, then ranks
   the candidates: FAULTY checks first, then unclaimed features, then partially passing ones.
2. **Allocate**: `workflow/allocate.sh claim <slug>` grabs the task in a shared append-only ledger on
   the `agent-allocations` branch. It's a compare-and-swap over `git push` with a 2 h TTL, so two
   machines never build the same feature, and the ledger never touches `main` (no deploys).
   Design: [`workflow/task-allocation.md`](workflow/task-allocation.md).
3. **Branch, spec, plan, implement**: one `feature/<slug>` branch per feature. The agent writes a
   decision block (status codes and edge cases the spec leaves open) and a requirement checklist,
   then follows the house patterns (DAO + row locks, service, thin controller).
4. **Test**: one integration test per checklist line (Testcontainers Postgres) plus an
   `examples/<slug>.hurl`, then a regression review against every spec that's already done.
5. **Report**: a test report with a coverage table. A person reviews, merges to `main` (which
   deploys) and claims on the harness. Agents never merge, deploy or claim.
6. **Log**: each iteration appends to this file via `workflow/review-log.sh` (added at 14:30,
   once we noticed this file was still empty).

The whole loop is [`.claude/rules/feature-loop.md`](.claude/rules/feature-loop.md), which Claude Code
loads at start-up on every machine.

## 2. How we reviewed agent output

- **Tests against the spec:** each feature's `*Test.java` maps one test to each spec rule, plus the
  edge cases we decided on and a concurrency test for any limit. `./mvnw package` must be green
  before a branch goes to a person.
- **Regression gates:** `TalkShapeRegressionTest` pins the shared `Talk` shape, which talks,
  co-speakers and acceptance all return. `CompatibilityTest` pins Boot 2.7 behaviour (404 body,
  trailing JSON) through the Spring Boot 4 / Jackson 3 migration.
- **Hurl examples** in `examples/` run against local and deployed instances with `examples/run.sh`.
- **A whole-project review agent** (`940dff6`) found an SQL injection in speaker search, N+1 queries,
  a rounding mismatch with the Ratings spec, an unused `MAX_TALKS` and inconsistent email/bio
  validation. All of it was fixed before the harness saw it.
- **A person** reads each branch's test report and diff before merging. **The harness** is the last
  gate: we read FAULTY transcripts through the feedback API and the request log
  (`RequestLoggingFilter` + `.github/fagdag/logs.sh`).

## 3. Claim log

| Feature | Claimed or skipped | Tick | Evidence before claiming |
|---|---|---|---|
| speakers, talks, ratings | claimed (base) | 0 | Base features, pre-claimed. Whole-project review at 13:10 fixed SQL injection, N+1 and rounding before any fault |
| co-speakers | claimed | 85 | `CoSpeakersTest` + `examples/co-speakers.hurl` green. **Not enough:** checks 1 and 2 went FAULTY at ticks 86–91 (see §4) |
| acceptance | claimed | 100 | `AcceptanceTest` + `examples/acceptance.hurl` green, regression review against talks/co-speakers. check-3 later flickered (see §4) |
| spring-boot-4 | claimed | 124 | Merged to `main` at 14:14 and deployed. All base, co-speakers and acceptance checks stayed green through the migration; `CompatibilityTest` + `TalkShapeRegressionTest` pin the Boot 2.7 behaviour. 2/2 checks OK after claiming |
| scheduling | in progress, allocated in ledger 13:11Z (teammate) | – | Branch `feature/scheduling`; not yet merged or claimed |
| workshop-registration | in progress, allocated in ledger 13:33Z (teammate) | – | Spec v2: capacity 20 under a rush (needs a row lock + concurrency test), case-insensitive email uniqueness; not yet merged or claimed |
| workshop-registration | ready to claim (person to confirm), branch `feature/workshop-registration` not merged | 129 | `WorkshopRegistrationTest` (9 tests incl. a 45-request rush) + `examples/workshop-registration.hurl` green; all 6 Hurl files green locally; 60-request rush on the real jar gave exactly 20 × 201; regression review vs talks/acceptance/co-speakers: Talk shape unchanged, withdrawn workshop keeps its list |
| programme-export | allocated in ledger 13:40Z (us, 14 s after release at tick 133); **blocked** | – | Reads the programme that scheduling writes; scheduling isn't on `main` yet, so the export (and its harness checks, which fill the programme via `PUT /programme/{room}/{slot}`) waits for it |
| programme-export | built on `feature/programme-export` (stacked on `feature/scheduling`); **ready to claim once both are merged and deployed** | – | `ProgrammeExportTest` (9 tests: order, slot times, speaker order, live renames/co-speaker changes/withdrawn talks, RFC 4180 quoting, UTF-8, `text/csv` for `Accept: application/json`, LF endings, empty programme) + `examples/programme-export.hurl` (not run: no Hurl here); full build 90 tests green |
| workshop-registration | claimed | 147 | Fix `122d027` deployed first. 4/4 green from the first run (ticks 148–149), no points bled. Evidence: `WorkshopRegistrationTest` + `examples/workshop-registration.hurl`, and the harness's own pre-claim sequences in the request log all ran to their closing `GET` |

## 4. What the harness caught that we didn't

- **co-speakers, ticks 86–91 (right after claiming):** `DELETE` and `PUT /talks/{id}/co-speakers/{speakerId}`
  were FAULTY. We validated input before looking up the talk and speaker, so an unknown id got a
  400 instead of a 404. Removing a speaker who wasn't a co-speaker also needed a 404, and adding
  the main speaker as a co-speaker needed a 400. Fixed in `86c4816` and `481e94f`, green from
  tick 92. **Why we missed it:** our tests checked each error on its own, never the *precedence*
  (unknown resource + bad input). Since then the rule is "404 for unknown resources before
  validating the body", which now also applies to `PUT /talks/{id}/status` and `POST /talks/{id}/withdraw`.
- **acceptance check-3, ticks 104, 109 and 111:** `PUT /talks/{id}/status` was intermittently
  FAULTY. Waitlisting a talk that was already WAITLISTED was allowed, and the check expects a 409.
  Fixed in `fd5ecf6`. **Why we missed it:** we tested the transition table, not the repeat
  transitions (X → X).
- **Every check, tick 103 (and several at 111):** `POST /speakers` got no response, which matches
  deploys (cold start or redeploy). That's not a logic bug, but it made us stop redeploying for
  docs-only pushes (`deploy.yml` `paths-ignore`, `fd5ecf6`).
- **Not a harness catch, but close:** `withdraw` with a non-integer `speakerId` returned a 500
  (`1374ed5`, fixed to 400).

- **workshop-registration, before claim (talks 5006, 5059, 5116):** registering on an *accepted* talk that isn't a workshop got our 404 ("no such workshop"), and the harness's sequence stopped there, with no closing `GET`, unlike every other refusal. The spec has one rule, "only workshops that the committee has accepted take registrations", and the harness already took 409 for "not accepted". Switched to 409 (and `GET` → `[]`) in `122d027`; the next run (talk 5429) went on to its `GET`. Claimed afterwards with 4/4 green and nothing bled.
  **Why we missed it:** we read `/workshops/{id}` as "the id must be a workshop" (404) instead of following the spec's single rule. **What helped:** reading the harness's unclaimed traffic in the request log before claiming. A sequence that stops right after our answer is a likely fault.

- **scheduling/check-1, ticks 157–163:** `PUT /programme/R2/slot2` answered 400; an unknown room/slot in the path is a 404. **The co-speakers lesson again**: it lived only in commit messages, so a teammate's agent never saw it. Fixed in `bb07d17`. From tick 164 the same check failed on a nonexistent `talkId` *in the body* (we said 404; ids in the body are 400, as `POST /talks` already did).
- **14:07 incident, our own regression.** workshop-registration/check-4 (from tick 160: **22 attendees in 20 seats**) and ratings/check-3 (from 162: `could not serialize access` 500s). Cause: programme-export's `@Transactional(isolation = REPEATABLE_READ)` made the driver send a session-level `SET`, which leaked to other pooled connections on the deployed database, exactly the AGENTS.md gotcha. All local tests were green, because the local pool doesn't leak. **Why we missed it:** the regression review re-read specs, not platform gotchas; nothing tested under the deployed pool's behaviour. Fix on `fix/isolation-leak`: no isolation levels, every transaction pinned with `SET TRANSACTION … READ COMMITTED`, and `IsolationLeakTest` poisons the pool to reproduce it (failed with 24/20 seats and the same 500 before the fix).
- **Concurrency is now the main failure class:** 3 of the last 4 faults were races (seat limit, ratings upsert, a talk placed twice at once → 500, fixed in `bb07d17`).

- **scheduling/check-1 green at tick 180** after 24 FAULTY ticks, peeled one request at a time: 404 for an unknown room/slot in the path (`bb07d17`), 400 for a nonexistent `talkId` in the body (`ad088ea`), 400 for a non-integer `talkId` (`a67bdae`), and 404 for deleting an empty cell (`bbb3d7f`, tick 174). Each fix only revealed the next failing request, because the check stops at its first failure. **Lesson:** list every error and repeat case up front (house-conventions §1A) instead of discovering them one deploy at a time.

- **af41c5f, our own process slip (no harness impact).** A one-line comment edit after the green build broke compilation, and the ratings-cr merge went out anyway, because the merge command ran after the build with `;` instead of `&&`. The deploy failed at its build step, so production stayed on the previous version; fixed in `50e44d2`. New rule in house-conventions §5: merge only what you just built, gated in the same command.

## 5. Reusable artifacts

- [`.claude/rules/feature-loop.md`](.claude/rules/feature-loop.md): the spec → decisions →
  checklist → test → report loop, with its guardrails (never merge, deploy or claim). It works for
  any "feature request isn't a spec" project.
- [`workflow/allocate.sh`](workflow/allocate.sh) + [`workflow/task-allocation.md`](workflow/task-allocation.md):
  lock-free task allocation across machines with nothing but git (CAS over push, TTL for crashed
  machines).
- [`workflow/review-log.sh`](workflow/review-log.sh): appends to a section of this file on
  `origin/main` from any branch, without leaving it.
- `RequestLoggingFilter` + `TalkShapeRegressionTest` / `CompatibilityTest`: request logging and
  shape-pinning tests make a framework migration (Boot 2.7 → 4) safe.

- [`.claude/rules/house-conventions.md`](.claude/rules/house-conventions.md): principles distilled from every harness fault (status-code precedence, repeats by meaning, no connection state, invariants enforced by the DB), plus a **requirements review built into the loop**: a requirements table before code, an independent reviewer agent that sees only the spec and the diff before hand-over, and a pre-claim gate against the deployed URL. Reusable on any "the request isn't a spec" project.

## 6. One thing we'd do differently

Start the running log in the loop from minute one. We built a careful spec-to-report loop, but none
of its steps wrote here, so at 14:30 this file was still the organisers' template, and every
iteration's report had gone to a terminal and been lost. Now the loop's last step appends to the log.
A second, smaller one: test error *precedence* (404 before 400) and repeat transitions as part of
the checklist, since those are what the harness caught after we claimed.

**Update, 15:20: write lessons where the agents read them.** Every repeat fault was a lesson we had already learned that sat in a commit message. They now live in [`.claude/rules/house-conventions.md`](.claude/rules/house-conventions.md) (status-code precedence, no session-level SETs, concurrency tests, a pre-claim gate against the deployed URL, grab before fixing), which every agent loads at start-up, and the feature loop's regression review starts from it.
