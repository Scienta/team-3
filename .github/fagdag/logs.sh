#!/usr/bin/env bash
# Prints your service's container logs, oldest first, from Scaleway Cockpit (Loki). Run by
# .github/workflows/logs.yml and, after a failed deploy, by .github/workflows/deploy.yml. Works
# from a laptop too: .github/fagdag/logs.sh --tail 50
#
# It reads the Loki URL and the read-only logs token committed in .fagdag/logs-url and
# .fagdag/logs-token. The token can read your project's logs and nothing else.
#
# Options:
#   --tail N       the newest N lines (default 200, Loki allows up to 5000)
#   --since TIME   only lines after this time: RFC 3339 (2026-11-06T13:00:00Z) or a Unix time in
#                  nanoseconds (default: the last 24 hours)
#   --url URL      another Loki URL instead of .fagdag/logs-url
#   --token TOKEN  another token instead of .fagdag/logs-token
#
# query_range doesn't stream. To follow the logs live, use logcli:
#   LOKI_ADDR=$(cat .fagdag/logs-url) LOKI_BEARER_TOKEN=$(cat .fagdag/logs-token) \
#     logcli query --tail '{resource_type="serverless_container"}'
set -euo pipefail

usage() {
  echo "Usage: $0 [--tail N] [--since TIME] [--url URL] [--token TOKEN]" >&2
  exit 2
}

# The repo's .fagdag/, found from this script's place (.github/fagdag/) so any working directory works.
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fagdag_dir="$(dirname "$(dirname "$here")")/.fagdag"
tail=200 since="" url="" token=""
while [ $# -gt 0 ]; do
  case "$1" in
    --tail) [ $# -ge 2 ] || usage; tail="$2"; shift ;;
    --since) [ $# -ge 2 ] || usage; since="$2"; shift ;;
    --url) [ $# -ge 2 ] || usage; url="$2"; shift ;;
    --token) [ $# -ge 2 ] || usage; token="$2"; shift ;;
    *) usage ;;
  esac
  shift
done

# Reads a committed file, or explains where it should be.
committed() {
  if [ ! -s "$fagdag_dir/$1" ]; then
    echo "No .fagdag/$1 in this repo (the organisers commit it). Pass --${1#logs-} instead." >&2
    exit 1
  fi
  tr -d '[:space:]' <"$fagdag_dir/$1"
}
[ -n "$url" ] || url="$(committed logs-url)"
[ -n "$token" ] || token="$(committed logs-token)"
# jq rather than date: GNU's date -d isn't on macOS.
[ -n "$since" ] || since="$(jq -rn '(now - 24 * 3600) | floor | todate')"

# --fail-with-body shows Loki's message (a bad token, a bad time) and still fails.
body="$(curl --silent --show-error --fail-with-body --max-time 30 -G \
  -H "X-Token: $token" \
  --data-urlencode 'query={resource_type="serverless_container"}' \
  --data-urlencode "limit=$tail" \
  --data-urlencode "start=$since" \
  --data-urlencode "direction=backward" \
  "${url%/}/loki/api/v1/query_range")" || {
  echo "$body" >&2
  exit 1
}

# Every stream's lines, merged and sorted by their nanosecond timestamps (all 19 digits).
lines="$(jq -r '[.data.result[].values[]] | sort_by(.[0]) | .[]
  | "\(.[0][0:10] | tonumber | todate) \(.[1])"' <<<"$body")"
if [ -z "$lines" ]; then
  echo "No log lines since $since."
else
  printf '%s\n' "$lines"
fi
