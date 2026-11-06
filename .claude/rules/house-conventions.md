# House conventions — lessons the deployed service taught us

Every rule here was paid for with FAULTY ticks on the harness. Follow them in every feature and fix; when
the harness teaches a new one, add it here (with the incident) in the same change that fixes it.

## Status codes (precedence, in this order)

1. **404: a resource named in the path doesn't exist** (unknown talk id, speaker id, room `R0`, slot
   `slot2`). Check this **before** reading or validating the body.
   *(co-speakers ticks 86–91; scheduling/check-1 ticks 157–163: the same mistake twice.)*
2. **400: the body is wrong**: a missing or mistyped field, *and* an id **in the body** that doesn't exist
   (`POST /talks` with an unknown `speakerId`, `PUT /programme/{room}/{slot}` with an unknown `talkId`).
   *(scheduling/check-1, tick 164+: 404 for an unknown `talkId` in the body was wrong.)*
3. **409: the request is valid but the current state forbids it**: limits, duplicates, illegal or
   repeated state transitions (X → X counts: acceptance WAITLISTED → WAITLISTED was a fault).
4. **Never 500.** A lost race on a unique key is a 409, not an exception: catch `DuplicateKeyException`.
   Grep the deployed logs for `500` after every deploy.

## Concurrency and the database

- **Every limit needs a concurrency test** that fires more requests than the limit at once, and the limit
  must hold in the database (row lock `SELECT … FOR UPDATE`, or a conditional write), not just in Java.
- **Never set a session-level anything**: no `isolation = …` on `@Transactional`, no `SET` without
  `LOCAL`. On the deployed database a session `SET` leaks to other requests' connections. *(14:07 incident:
  programme-export's `REPEATABLE_READ` leaked and let 22 attendees into a 20-seat workshop and made ratings
  500; local tests can't see it.)* `ReadCommittedTransactions` pins every transaction to READ COMMITTED as a
  backstop; `IsolationLeakTest` poisons the pool to prove it. Keep both.
- A green local concurrency test is necessary, not sufficient: the deployed pool behaves differently.

## Before a person claims (pre-claim gate)

The harness shows a feature's checks only after the permanent claim, so the first verdict costs points.
Before asking a person to claim, against the **deployed** URL:
1. `examples/run.sh https://fagsem-h2026-team-3.scienta.cloud` is green (works without Hurl, via Docker).
2. Adversarial cases by hand or in the `.hurl`: unknown ids in the path (404) and in the body (400),
   repeated operations, and a concurrent burst over every limit.
3. No new `500` in the deployed logs since the deploy.

## Coordination

- **Grab before fixing, not only before building.** A FAULTY check is a task: `workflow/allocate.sh grab
  <slug>` first. *(Two of us wrote the same scheduling fix at once.)*
- **Batch code merges** when you can: every code deploy costs about one tick of FAULTY checks everywhere.
