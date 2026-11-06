#!/usr/bin/env bash
#
# Append to a section of REVIEW.md on origin/main, from any branch, without touching your working tree.
#
# REVIEW.md is the team's running log (see REVIEW.md and .claude/rules/feature-loop.md step 9b). The
# feature loop works on feature branches, but the log has to land on main as it happens, not at merge
# time. This script checks origin/main out in a private worktree (inside .git/), appends stdin to the
# end of the chosen "## N." section, commits ONLY REVIEW.md and pushes to main, retrying on a race
# like allocate.sh. A push that changes only *.md files does not deploy (deploy.yml paths-ignore).
#
# Usage:
#   workflow/review-log.sh <section> [message] <<'EOF'
#   | co-speakers | claimed | 85 | CoSpeakersTest + examples/co-speakers.hurl green |
#   EOF
#
#   <section>  1..6, the number of the "## N." heading to append to (3 = claim log, 4 = harness caught)
#   [message]  commit message suffix (default: "update section <section>")
#
# Config (env, with defaults):
#   REVIEW_REMOTE    git remote               (default: origin)
#   REVIEW_BRANCH    branch the log lives on  (default: main)
#   REVIEW_RETRIES   push attempts            (default: 5)
#   REVIEW_WORKTREE  private worktree path    (default: <git-dir>/review-wt)
#
# Exit: 0 pushed, 2 usage/setup error, 11 kept losing the push race.
# Dependencies: git, python3.

set -uo pipefail

REMOTE="${REVIEW_REMOTE:-origin}"
BRANCH="${REVIEW_BRANCH:-main}"
RETRIES="${REVIEW_RETRIES:-5}"
FILE="REVIEW.md"

die() { echo "review-log: $*" >&2; exit 2; }

command -v git >/dev/null 2>&1 || die "git not found"
command -v python3 >/dev/null 2>&1 || die "python3 not found"
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "not inside a git repository"
cd "$(git rev-parse --show-toplevel)" || die "cannot cd to repo root"

SECTION="${1:-}"
[[ "$SECTION" =~ ^[1-6]$ ]] || die "usage: review-log.sh <section 1-6> [message] < text"
MSG="${2:-update section $SECTION}"

TEXT="$(cat)"
[ -n "${TEXT//[[:space:]]/}" ] || die "nothing on stdin to append"

export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.safecrlf GIT_CONFIG_VALUE_0=false   # quiet autocrlf warnings
WT="${REVIEW_WORKTREE:-$(git rev-parse --git-dir)/review-wt}"
WT="$(cd "$(dirname "$WT")" && pwd)/$(basename "$WT")"

ensure_worktree() {
    if [ ! -e "$WT/.git" ]; then
        git worktree prune >/dev/null 2>&1
        git fetch -q "$REMOTE" "$BRANCH" || die "cannot fetch $REMOTE/$BRANCH"
        git worktree add -q --detach "$WT" "$REMOTE/$BRANCH" >/dev/null || die "cannot create worktree $WT"
    fi
}

# Appends $TEXT at the end of section N (before the next "## " heading, or at end of file).
append_to_section() {
    REVIEW_TEXT="$TEXT" python3 - "$WT/$FILE" "$SECTION" <<'PY'
import os, re, sys
path, n = sys.argv[1], sys.argv[2]
text = os.environ["REVIEW_TEXT"].rstrip("\n")
lines = open(path, encoding="utf-8").read().split("\n")
start = next((i for i, l in enumerate(lines) if re.match(rf"^## {n}\.", l)), None)
if start is None:
    sys.exit(f"review-log: no '## {n}.' heading in {path}")
end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
# insert after the last non-blank line of the section
ins = end
while ins > start + 1 and lines[ins - 1].strip() == "":
    ins -= 1
# a table row continues the table; anything else gets a blank line before it
prev = lines[ins - 1] if ins > 0 else ""
block = text.split("\n")
if not (block[0].startswith("|") and prev.startswith("|")):
    block = [""] + block
lines[ins:ins] = block
open(path, "w", encoding="utf-8").write("\n".join(lines))
PY
}

ensure_worktree
for attempt in $(seq 1 "$RETRIES"); do
    git -C "$WT" fetch -q "$REMOTE" "$BRANCH" || die "cannot fetch $REMOTE/$BRANCH"
    git -C "$WT" reset -q --hard "$REMOTE/$BRANCH"
    append_to_section || exit 2
    git -C "$WT" add "$FILE"
    git -C "$WT" diff --cached --quiet && { echo "review-log: no change"; exit 0; }
    git -C "$WT" commit -q -m "review: $MSG" -- "$FILE" || die "commit failed"
    if git -C "$WT" push -q "$REMOTE" "HEAD:$BRANCH" 2>/dev/null; then
        echo "review-log: appended to section $SECTION on $REMOTE/$BRANCH ($(git -C "$WT" rev-parse --short HEAD))"
        exit 0
    fi
    echo "review-log: push rejected (attempt $attempt), retrying" >&2
    sleep $((attempt))
done
echo "review-log: gave up after $RETRIES attempts" >&2
exit 11
