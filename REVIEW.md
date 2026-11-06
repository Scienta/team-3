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

## 6. One thing we'd do differently

Start the running log in the loop from minute one. We built a careful spec-to-report loop, but none
of its steps wrote here, so at 14:30 this file was still the organisers' template, and every
iteration's report had gone to a terminal and been lost. Now the loop's last step appends to the log.
A second, smaller one: test error *precedence* (404 before 400) and repeat transitions as part of
the checklist, since those are what the harness caught after we claimed.
