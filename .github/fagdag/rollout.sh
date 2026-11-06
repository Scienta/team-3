#!/usr/bin/env bash
# Rolls the pushed image out to your container. Run by .github/workflows/deploy.yml after the build
# has pushed $SCW_REGISTRY/$ROLLOUT_IMAGE:<sha> and :latest.
#
#   1. Last good: the version the live GET /health reports now (none on the first deploy).
#   2. scw container container redeploy -w, and fail unless the container ends up ready
#      (scw exits 0 even when the new revision ends in error).
#   3. Poll GET /health until it answers 200 with version == the SHA, for up to 60 s. The old
#      instance keeps answering for a while during the rollout.
#   4. On failure, re-tag :<last good> as :latest without redeploying: the old revision keeps
#      serving, and a later redeploy pulls it again rather than the broken image. Exit 1.
#   5. One ✅/❌ line on the run's summary page.
#
# Env:
#   SCW_REGISTRY          the registry namespace, e.g. rg.fr-par.scw.cloud/<namespace>
#   SCW_CONTAINER_ID      the container's ID
#   SCW_ACCESS_KEY, SCW_SECRET_KEY   for scw (and docker, already logged in to the registry)
#   ROLLOUT_URL           the service's base URL, with https://
#   ROLLOUT_SHA           the commit being deployed (default $GITHUB_SHA)
#   ROLLOUT_IMAGE         the image name in the registry (default app)
#   ROLLOUT_STARTED_AT    when the deploy started, RFC 3339 (2026-11-06T13:00:00Z), for the time
#                         taken (default now)
#   ROLLOUT_GATE_SECONDS  how long to wait for the new version (default 60)
#   ROLLOUT_POLL_SECONDS  wait between /health polls (default 5)
#   GITHUB_STEP_SUMMARY   set by Actions; the summary line goes here
set -euo pipefail

: "${SCW_REGISTRY:?SCW_REGISTRY is not set}"
: "${SCW_CONTAINER_ID:?SCW_CONTAINER_ID is not set}"
: "${ROLLOUT_URL:?ROLLOUT_URL is not set}"
sha="${ROLLOUT_SHA:-${GITHUB_SHA:?ROLLOUT_SHA or GITHUB_SHA must be set}}"
image="$SCW_REGISTRY/${ROLLOUT_IMAGE:-app}"
gate_seconds="${ROLLOUT_GATE_SECONDS:-60}"
poll_seconds="${ROLLOUT_POLL_SECONDS:-5}"
url="${ROLLOUT_URL%/}"
# jq rather than date: GNU's date -d isn't on macOS.
if [ -n "${ROLLOUT_STARTED_AT:-}" ]; then
  started="$(jq -rn --arg t "$ROLLOUT_STARTED_AT" '$t | fromdate')" \
    || { echo "ROLLOUT_STARTED_AT must be RFC 3339 like 2026-11-06T13:00:00Z, not $ROLLOUT_STARTED_AT" >&2; exit 2; }
else
  started="$(date +%s)"
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Reads GET /health once; sets $status ("000" when nothing answers) and $version.
read_health() {
  rm -f "$tmp/health"
  status="$(curl --silent --max-time 5 -o "$tmp/health" -w '%{http_code}' "$url/health" 2>/dev/null)" || status=000
  version="$(jq -r '.version // empty' "$tmp/health" 2>/dev/null)" || version=""
}

# Points :latest back at the last good image, without a redeploy: the old revision is still
# serving, and a later redeploy or organiser apply then pulls the same image again.
roll_back() {
  if [ -z "$last_good" ]; then
    echo "Rollback: no last good version (first deploy), so :latest stays $sha."
  elif [ "$last_good" = "$sha" ]; then
    echo "Rollback: the live version is already $sha, so :latest stays."
  elif ! [[ "$last_good" =~ ^[0-9a-f]{7,40}$ ]]; then
    echo "Rollback: the live version $last_good isn't a commit SHA, so :latest stays $sha."
  elif docker pull "$image:$last_good" && docker tag "$image:$last_good" "$image:latest" &&
    docker push "$image:latest"; then
    echo "Rollback: $image:latest is $last_good again; that version keeps serving."
  else
    echo "::error::Rollback failed: $image:latest is still $sha. Re-tag a good :<sha> as :latest by hand."
  fi
}

# "2m 15s" since $ROLLOUT_STARTED_AT (or since this script started).
took() {
  local s=$(($(date +%s) - started))
  if [ "$s" -ge 60 ]; then echo "$((s / 60))m $((s % 60))s"; else echo "${s}s"; fi
}

# One line on the run's summary page (only in Actions).
summary() {
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "$1" >>"$GITHUB_STEP_SUMMARY"; fi
}

failed() {
  echo "::error::$1"
  roll_back
  summary "❌ Deploy of \`${sha:0:7}\` to $url failed after $(took)"
  exit 1
}

echo "Deploying $sha to $url"
echo "  image: $image:$sha (also :latest)"
read_health
last_good="$version"
echo "Last good: ${last_good:-none (first deploy)}"

echo "Redeploying container $SCW_CONTAINER_ID (scw waits for it to settle)"
if ! scw container container redeploy "$SCW_CONTAINER_ID" -w -o json >"$tmp/container.json"; then
  failed "The redeploy failed (scw's error is above)."
fi
# scw exits 0 even when the new revision ends in error, so the status decides.
container_status="$(jq -r '.status // "unknown"' "$tmp/container.json" 2>/dev/null)" || container_status=unknown
echo "Status after redeploy: $container_status"
if [ "$container_status" != ready ]; then
  echo "error_message: $(jq -r '.error_message // "(none)"' "$tmp/container.json" 2>/dev/null || cat "$tmp/container.json")"
  failed "The container is $container_status after the redeploy, not ready."
fi

echo "Checking $url/health for version $sha (up to $gate_seconds s)"
deadline=$(($(date +%s) + gate_seconds))
while :; do
  read_health
  if [ "$status" = 000 ]; then
    echo "  /health: no answer"
  else
    echo "  /health: $status, version ${version:-(none)}"
  fi
  [ "$status" = 200 ] && [ "$version" = "$sha" ] && break
  if [ "$(date +%s)" -ge "$deadline" ]; then
    failed "$url/health didn't answer with version $sha within $gate_seconds s."
  fi
  sleep "$poll_seconds"
done
echo "Deployed $sha to $url in $(took)"
summary "✅ Deployed \`${sha:0:7}\` to $url in $(took)"
