#!/usr/bin/env bash
#
# Task allocation across machines (see workflow/task-allocation.md).
#
# Coordinates which teammate is building which feature, via an append-only JSON Lines ledger
# (allocations.jsonl) on the orphan branch `agent-allocations`, which is NEVER merged to main (pushing
# main deploys). Grabbing a task is an optimistic compare-and-swap: fetch -> check free -> append claim
# -> push; git's atomic per-ref push rejects the loser of a race, who re-fetches and re-checks.
#
# Usage:
#   workflow/allocate.sh claim   <task>   # exit 0 claimed, 10 already taken, 11 kept losing the race
#   workflow/allocate.sh done    <task>   # release after finishing a feature
#   workflow/allocate.sh release <task>   # release when giving up / iteration ended early
#   workflow/allocate.sh status           # current owner of every task
#   workflow/allocate.sh mine             # tasks currently claimed by me
#
# Config (env, with defaults):
#   MEMBER           identity for claims           (default: git config user.email)
#   ALLOC_REMOTE     git remote                    (default: origin)
#   ALLOC_BRANCH     ledger branch                 (default: agent-allocations)
#   ALLOC_TTL_HOURS  a claim older than this is reclaimable as stale (default: 2)
#   ALLOC_RETRIES    CAS push attempts             (default: 5)
#   ALLOC_WORKTREE   where the ledger branch is checked out (default: <git-dir>/alloc-wt)
#
# Dependencies: git, python3.

set -uo pipefail

REMOTE="${ALLOC_REMOTE:-origin}"
BRANCH="${ALLOC_BRANCH:-agent-allocations}"
FILE="allocations.jsonl"
TTL_HOURS="${ALLOC_TTL_HOURS:-2}"
RETRIES="${ALLOC_RETRIES:-5}"

die() { echo "allocate: $*" >&2; exit 2; }

command -v git >/dev/null 2>&1 || die "git not found"
command -v python3 >/dev/null 2>&1 || die "python3 not found"
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "not inside a git repository"
cd "$(git rev-parse --show-toplevel)" || die "cannot cd to repo root"

MEMBER="${MEMBER:-$(git config user.email || true)}"
[ -n "$MEMBER" ] || die "no identity: set git config user.email or the MEMBER env var"
HOST="$(hostname 2>/dev/null || echo unknown)"
WT="${ALLOC_WORKTREE:-$(git rev-parse --git-dir)/alloc-wt}"

# ---- ledger helpers (python3 does all JSON parsing/writing and the TTL check) ----

# Prints one of: FREE | MINE | "TAKEN <member>" | "STALE <member>" for $task, reading $WT/$FILE.
owner_state() {
    python3 - "$WT/$FILE" "$1" "$MEMBER" "$TTL_HOURS" <<'PY'
import sys, json, datetime
path, task, me, ttl = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])
last = None
try:
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                e = json.loads(line)
            except ValueError:
                continue
            if e.get("task") == task:
                last = e
except FileNotFoundError:
    pass
if last is None or last.get("status") != "claimed":
    print("FREE"); sys.exit()
member = last.get("member", "")
try:
    t = datetime.datetime.fromisoformat(str(last.get("ts", "")).replace("Z", "+00:00"))
except ValueError:
    t = None
now = datetime.datetime.now(datetime.timezone.utc)
stale = t is None or (now - t).total_seconds() > ttl * 3600
if member == me:
    print("MINE")
elif stale:
    print("STALE " + member)
else:
    print("TAKEN " + member)
PY
}

append_event() { # append_event <task> <status>
    python3 - "$WT/$FILE" "$1" "$MEMBER" "$HOST" "$2" <<'PY'
import sys, json, datetime
path, task, member, host, status = sys.argv[1:6]
rec = {
    "ts": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "task": task, "member": member, "host": host, "status": status,
}
with open(path, "a") as f:
    f.write(json.dumps(rec) + "\n")
PY
}

# ---- worktree for the ledger branch (kept out of the member's normal working tree) ----

ensure_worktree() {
    if git -C "$WT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        return 0
    fi
    git worktree prune >/dev/null 2>&1 || true
    rm -rf "$WT"
    if git ls-remote --exit-code --heads "$REMOTE" "$BRANCH" >/dev/null 2>&1; then
        git fetch -q "$REMOTE" "$BRANCH" || die "cannot fetch $BRANCH"
        git worktree add -q --detach "$WT" "$REMOTE/$BRANCH" || die "cannot add worktree"
        git -C "$WT" checkout -q -B "$BRANCH" "$REMOTE/$BRANCH" || die "cannot check out $BRANCH"
    else
        # bootstrap: an orphan branch with an empty ledger
        git worktree add -q --detach "$WT" HEAD || die "cannot add worktree"
        git -C "$WT" checkout -q --orphan "$BRANCH" || die "cannot create orphan $BRANCH"
        git -C "$WT" rm -rf . >/dev/null 2>&1 || true
        : > "$WT/$FILE"
        git -C "$WT" add "$FILE"
        git -C "$WT" commit -q -m "chore: init agent-allocations ledger" || die "cannot init ledger"
        if ! git -C "$WT" push -q -u "$REMOTE" "$BRANCH" 2>/dev/null; then
            # lost the bootstrap race: another machine created the branch first -> adopt theirs
            git -C "$WT" fetch -q "$REMOTE" "$BRANCH" || die "cannot fetch $BRANCH after bootstrap race"
            git -C "$WT" reset -q --hard "$REMOTE/$BRANCH"
        fi
    fi
}

sync_worktree() {
    git -C "$WT" fetch -q "$REMOTE" "$BRANCH" || true
    git -C "$WT" reset -q --hard "$REMOTE/$BRANCH" || true
}

# ---- commands ----

cmd_claim() {
    local task="$1"
    ensure_worktree
    local attempt state verdict who
    for attempt in $(seq 1 "$RETRIES"); do
        sync_worktree
        state="$(owner_state "$task")"
        verdict="${state%% *}"; who="${state#* }"
        case "$verdict" in
            MINE)  echo "already yours: $task"; return 0 ;;
            TAKEN) echo "taken by $who"; return 10 ;;
            STALE) append_event "$task" "released" ;;   # free the dead claim, then claim below
            FREE)  : ;;
        esac
        append_event "$task" "claimed"
        git -C "$WT" add "$FILE"
        git -C "$WT" commit -q -m "claim $task by $MEMBER"
        if git -C "$WT" push -q "$REMOTE" "HEAD:$BRANCH" 2>/dev/null; then
            echo "claimed $task"
            return 0
        fi
        # push rejected: a teammate advanced the ref -> retry (re-fetch, re-check)
    done
    echo "could not claim $task after $RETRIES attempts (contention)"
    return 11
}

cmd_free() { # cmd_free <task> <released|done>
    local task="$1" status="$2" attempt
    ensure_worktree
    for attempt in $(seq 1 "$RETRIES"); do
        sync_worktree
        append_event "$task" "$status"
        git -C "$WT" add "$FILE"
        git -C "$WT" commit -q -m "$status $task by $MEMBER"
        if git -C "$WT" push -q "$REMOTE" "HEAD:$BRANCH" 2>/dev/null; then
            echo "$status $task"
            return 0
        fi
    done
    echo "could not record $status for $task after $RETRIES attempts"
    return 11
}

cmd_report() { # cmd_report <all|mine>
    local scope="$1"
    if ! git fetch -q "$REMOTE" "$BRANCH" 2>/dev/null; then
        echo "no ledger yet (branch $BRANCH does not exist)"
        return 0
    fi
    local tmp; tmp="$(mktemp)"
    git show "$REMOTE/$BRANCH:$FILE" > "$tmp" 2>/dev/null || true
    python3 - "$tmp" "$MEMBER" "$scope" "$TTL_HOURS" <<'PY'
import sys, json, datetime
path, me, scope, ttl = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])
last = {}
with open(path) as fh:
    for line in fh:
        line = line.strip()
        if not line:
            continue
        try:
            e = json.loads(line)
        except ValueError:
            continue
        t = e.get("task")
        if t:
            last[t] = e
now = datetime.datetime.now(datetime.timezone.utc)
rows = []
for task in sorted(last):
    e = last[task]
    status, member, ts = e.get("status", ""), e.get("member", ""), e.get("ts", "")
    stale = ""
    if status == "claimed":
        try:
            age = (now - datetime.datetime.fromisoformat(str(ts).replace("Z", "+00:00"))).total_seconds()
            if age > ttl * 3600:
                stale = " (stale)"
        except ValueError:
            stale = " (stale)"
    if scope == "mine" and not (status == "claimed" and member == me):
        continue
    mark = " *" if (status == "claimed" and member == me) else ""
    detail = (" " + ts + stale) if status == "claimed" else ""
    rows.append(f"{task:20} {status:9} {member}{detail}{mark}")
print("\n".join(rows) if rows else "(none)")
PY
    rm -f "$tmp"
}

usage() {
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

main() {
    local cmd="${1:-}"
    case "$cmd" in
        claim)   [ $# -eq 2 ] || usage; cmd_claim "$2" ;;
        done)    [ $# -eq 2 ] || usage; cmd_free "$2" "done" ;;
        release) [ $# -eq 2 ] || usage; cmd_free "$2" "released" ;;
        status)  cmd_report "all" ;;
        mine)    cmd_report "mine" ;;
        *)       usage ;;
    esac
}

main "$@"
