# Task allocation across machines

The team runs the feature loop (`.claude/rules/feature-loop.md`) on several machines at once. This is how
they avoid two people building the same feature: a shared **append-only JSON Lines ledger**, synced over
git on a branch that **never touches `main`**, with claims grabbed by **optimistic compare-and-swap**.

This is **not** the harness feature-claim (that is permanent and done by a person, for scoring). This
ledger only coordinates *who is building what* so effort isn't duplicated.

## Why a separate branch

**Pushing to `main` deploys** (deploy contract). So the ledger must never live on `main`, or every grab
would trigger a production deploy and churn the built image. It lives on an **orphan branch
`agent-allocations`** that is never merged to `main`. The loop may push *that* branch; it still never
pushes `main`.

## The ledger — `allocations.jsonl` on branch `agent-allocations`

One event per line, append-only. The current owner of a task is the **last event** for that task.

```json
{"ts":"2026-11-06T13:40:00Z","task":"acceptance","member":"mk@scienta.no","host":"mk-laptop","status":"claimed"}
{"ts":"2026-11-06T14:05:00Z","task":"acceptance","member":"mk@scienta.no","host":"mk-laptop","status":"done"}
```

- `status`: `claimed` → owned; `released` / `done` → free again.
- `member`: identity, from `git config user.email` (override with `$MEMBER`).
- A `claimed` event older than the TTL (default 2 h) counts as **stale → free**, so a crashed machine
  never wedges a task. Reclaiming one writes a `released` (expired) event first, then the new claim.

## The script — `workflow/allocate.sh`

```
allocate.sh claim   <task>   # CAS grab. exit 0 won, 10 already taken, 11 kept losing the race
allocate.sh done    <task>   # append a free event (feature finished)
allocate.sh release <task>   # append a free event (giving up / iteration ended early)
allocate.sh status           # print current owner of every task
allocate.sh mine             # print tasks currently claimed by me
```

Config (env, with defaults): `ALLOC_REMOTE=origin`, `ALLOC_BRANCH=agent-allocations`,
`MEMBER=$(git config user.email)`, `ALLOC_TTL_HOURS=2`, `ALLOC_RETRIES=5`,
`ALLOC_WORKTREE=$(git rev-parse --git-dir)/alloc-wt` (lives inside `.git/`, so it is never tracked and
never disturbs the member's feature-branch working tree).

Dependencies: `git`, `python3` (already used in this repo; parses/writes JSON robustly, computes the
last-event-per-task and the TTL check — no `jq` required).

### `claim` algorithm (the compare-and-swap)

```
ensure_worktree                      # git worktree add $WT $BRANCH, creating/bootstrapping if missing
for attempt in 1..RETRIES:
    git -C $WT fetch $REMOTE $BRANCH
    git -C $WT reset --hard $REMOTE/$BRANCH        # adopt the latest ledger
    owner = last_event(task)                        # python3 over $WT/allocations.jsonl
    if owner.status == claimed and owner.member != MEMBER and age(owner.ts) < TTL:
        print "taken by " owner.member; exit 10
    if owner was stale: append {status:released, note:expired}
    append {ts:now, task, member:MEMBER, host, status:claimed}
    git -C $WT commit -am "claim $task by $MEMBER"
    if git -C $WT push $REMOTE HEAD:$BRANCH:        # atomic per-ref: rejected if someone pushed first
        exit 0
    # push rejected -> a teammate advanced the ref -> loop: re-fetch, re-check, retry
exit 11
```

`release` / `done` are the same append-commit-push with retry, but with no pre-check (freeing is always
allowed). `status` / `mine` are read-only (`git fetch` + `git show $REMOTE/$BRANCH:allocations.jsonl`).

### Bootstrap (automatic, once)

If `$REMOTE/agent-allocations` doesn't exist yet, the first `claim`/`release` creates it: an orphan branch
with an empty `allocations.jsonl`, pushed with `-u`. No manual setup needed; a person just needs push
access (same access they already use for `main`).

## Loop integration — edits to `.claude/rules/feature-loop.md`

- **Step 1 (Discover):** build the ordered candidate list as today.
- **New step 1b (Allocate), before Branch:** walk candidates in priority order, calling
  `workflow/allocate.sh claim <slug>`. The first `exit 0` is this iteration's target. On `exit 10`
  (taken) move to the next candidate. If none can be claimed → report *"all actionable tasks are
  allocated to teammates"* and back off (no work this iteration).
- **Step 2 (Branch):** unchanged — one branch per feature.
- **Step 10 (Next iteration) and on any early exit/failure:** `workflow/allocate.sh done <slug>` on
  success, or `release <slug>` if the iteration ends without finishing, so the task frees for others.
- **Guardrail update:** add that pushing the `agent-allocations` branch is explicitly allowed; `main`
  is still never pushed by the loop.

## Verification

1. **Unit-ish, one machine:** `claim acceptance` → exit 0; `status` shows it mine; second `claim
   acceptance` from a different `MEMBER=` → exit 10; `done acceptance` → frees it; `claim` from the
   other member now → exit 0.
2. **Race:** fire two `claim <same-task>` with different `MEMBER=` concurrently (`&`); exactly one exits
   0, the other exits 10, and the ledger ends with a single live `claimed` owner. Repeat a few times.
3. **TTL:** hand-write a `claimed` event with an old `ts`, then `claim` the same task as another member →
   it reclaims (exit 0) and writes the `released(expired)` + new `claimed`.
4. **No main churn:** `git log origin/main` is unchanged by any of the above; only `agent-allocations`
   moves. No deploy is triggered.

## Risks / notes

- **Clock skew** between machines affects TTL only; 2 h TTL is far larger than any skew, so harmless.
- **Push access required** on every machine (already true, since they deploy). A read-only machine can
  still run `status`/`mine`.
- **Worktree cleanliness:** the script reuses `$WT`; it never checks the orphan branch out in the normal
  tree, so the member's feature-branch work is untouched.
- **Ledger growth** is trivial (a few lines per feature); no rotation needed for the event's lifespan.
