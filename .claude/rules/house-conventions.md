# House conventions

Principles for every feature and every fix. Each one exists because the deployed service broke without it;
the incidents are listed as evidence, not as the rule. The harness decides what's right, and the spec rarely
says, so these principles are how we guess the same way every time. When the harness teaches us something
new, add or sharpen a principle here in the same change as the fix (see "After a harness fault").

## 1. Requirements review — built into every iteration

A feature request isn't a spec. Every fault so far was a requirement nobody wrote down: an error case, a
repeat, a race. So requirements are reviewed three times, and each review leaves a written trace.

**A. Before code (feature-loop step 3): the requirements table.** One row per sentence of the spec that
states a rule, plus the rows the principles below add even when the spec is silent:

| # | Requirement (spec words) | Kind | Decided behaviour (status, body) | Invariant under concurrency? | Test |
|---|---|---|---|---|---|

- *Kind*: shape, rule, error, repeat, ordering, or invariant.
- For every endpoint, add rows for the unknown path id (§2.1), the bad body and wrong types (§2.2–2.3), the
  repeat (§2.5), and every invariant (§4), even if the spec never mentions them.
- For every shared shape or endpoint this touches (the `Talk` shape, `talk.status`, `/programme`), add a row
  per earlier spec requirement it could break.
- Behaviour the spec leaves open is decided by §2, then by mirroring the nearest endpoint the harness
  already accepts (§2.6), and marked *decided*, so it's visible in the report and can be asked about.

**B. Before handing over the branch (feature-loop step 8): an independent review.** The author is anchored
on their own reading. Spawn a fresh reviewer agent and give it **only** the spec(s), the diff, this file
and the test names, not your table or your conclusions. Ask it to:
1. Build its own list of requirements from the spec, then mark each one *covered* (test plus code line) or
   *not covered*.
2. Check the diff against every principle in §2–§5 and name any violation with file:line.
3. List anything the spec says that it can't find in either.
Diff its list with your table. Every gap is fixed, or recorded as a *decided* row with the reason. Put both
lists' result in the test report's coverage table.

**C. Before a person claims (pre-claim gate).** The harness shows a feature's checks only after the
permanent claim, so the first verdict costs points. After the deploy, against the **deployed** URL:
1. `examples/run.sh https://fagsem-h2026-team-3.scienta.cloud` is green (works without Hurl, via Docker).
2. Every *error*, *repeat* and *invariant* row of the table has a matching request in the feature's
   `.hurl`, including a concurrent burst over each limit (run by hand if Hurl can't express it).
3. No new `500` in the deployed logs since the deploy.

## 2. Status codes and request handling

Check in this order, and stop at the first failure:

1. **404: something named in the path doesn't exist**: an unknown talk or speaker id, room `R0`, slot
   `slot2`. Check it before reading the body.
   *(co-speakers ticks 86–91; scheduling/check-1 ticks 157–163: the same mistake twice.)*
2. **400: the body is wrong**: a missing field, an empty string, an out-of-range value, **or an id in the
   body that doesn't exist** (`POST /talks` with an unknown `speakerId`, `PUT /programme/…` with an unknown
   `talkId`). *(scheduling/check-1, 164–173.)*
3. **400: wrong JSON types, never coerced.** `true`, `1.5`, `"7"` or `null` where a whole number belongs is
   a 400, not a 500 and not a silent conversion. Check `instanceof Integer || Long`, not `Number`.
   *(withdraw `speakerId` → 500, `1374ed5`; scheduling `talkId: true`, `a67bdae`.)*
4. **409: the request is valid, but the current state forbids it**: a limit is reached, a duplicate, a
   transition that isn't allowed from the current status.
5. **Repeats, by what the request means:**
   - *Make it so* (PUT of a state, adding a member that's already there): it already is, so **200**, no
     change. *(Placing the same talk in the same cell again: harness accepts 200.)*
   - *A transition or action* (accept, waitlist, withdraw) from a status it isn't allowed from, including
     its own target status: **409**. *(acceptance WAITLISTED → WAITLISTED.)*
   - *Remove something*: if it isn't there, **404**. *(scheduling DELETE of an empty cell, tick 174.)*
6. **When the spec is silent, mirror the nearest endpoint the harness already accepts** (same verb, same
   kind of resource), and write it down as a *decided* row. Never invent a new convention.
7. **Never 500.** A lost race on a unique key or a lock is a 409. A 500 in the logs is a bug, whatever
   the harness says.

## 3. The platform: no connection state

**Assume every transaction may run on a different server connection, and that anything left on a
connection is inherited by a stranger's request.** On the deployed database it is: a session `SET` leaks
to whoever gets the pooled connection next (AGENTS.md).

- **Never** set anything at session level: no `@Transactional(isolation = …)`, no `SET` without `LOCAL`, no
  advisory locks, temp tables, session variables or `search_path` changes. Per-transaction settings go
  through `SET LOCAL` or `SET TRANSACTION` (`TransactionConfig` does isolation for every transaction).
  *(14:07 incident: programme-export's REPEATABLE READ leaked, letting 22 attendees into 20 seats and making
  ratings return 500s. Every local test was green.)*
- **5 connections, shared by everything.** No N+1 queries in list endpoints; no slow work (HTTP calls,
  loops over many rows) inside a transaction that holds a lock.
- **One instance, scales to zero, ~1 tick of FAULTY checks per code deploy.** Batch code merges; docs and
  `examples/` don't deploy.

## 4. Invariants and concurrency

- **Every invariant the spec states is enforced by the database**: a unique constraint, a row lock
  (`SELECT … FOR UPDATE`) before the count, or a conditional write. Java checks alone race. Limits,
  uniqueness ("one per email, in any case"), "only once", and upserts all count.
- **Every invariant gets a concurrency test** that fires more requests than the limit at once, and
  asserts both the count and that every response is an expected status, never a 500.
- **Recreate the production condition when it differs from local.** A green local concurrency test is
  necessary, not sufficient. If production has a property local doesn't (a leaking pool, slow cold start),
  reproduce it in the test the way `IsolationLeakTest` poisons the pool, and see the test **fail before**
  the fix.

## 5. Coordination

- **Pull before every iteration**, including a quick fix: these rules only protect people who have them.
- **Grab before fixing, not only before building.** A FAULTY check is a task: `workflow/allocate.sh grab
  <slug>` first. *(Three collisions in one afternoon, two on the same scheduling check.)*

## After a harness fault

Fix it, then ask **which principle should have caught it**. If one did and was ignored, say so in
`REVIEW.md` §4. If none did, add or sharpen one here in the same change, with the incident as evidence.
