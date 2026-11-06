#!/usr/bin/env bash
# Runs the base specs' published Hurl examples against a running service.
# They are the happy path only, and not a gate: passing them doesn't mean the harness's checks pass.
#
# Usage: examples/run.sh [base-url]        (default http://localhost:8080)
# Env:   HURL        the hurl binary (default: hurl on your PATH; https://hurl.dev)
#        HURL_IMAGE  image for the Docker fallback (default: ghcr.io/orange-opensource/hurl:8.1.0)
#
# No hurl installed? If Docker is running, the examples run in the official Hurl image instead. A
# localhost base URL is rewritten so the container can reach the service on this machine: the WSL IP on
# WSL (Docker Desktop can't use --network host there), host networking on plain Linux, and
# host.docker.internal on macOS/Windows.
#
# Specs released during the day publish their examples at /specs/<slug>/examples.hurl; save
# them here as <slug>.hurl and they're picked up too.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
base="${1:-http://localhost:8080}"

if [ -n "${HURL:-}" ] || command -v hurl >/dev/null 2>&1; then
    exec "${HURL:-hurl}" --test --variable "base_url=$base" "$here"/*.hurl
fi

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    echo "run.sh: hurl isn't installed and Docker isn't running; install hurl (https://hurl.dev) or start Docker" >&2
    exit 2
fi

net=()
if [[ "$base" =~ ^(https?://)(localhost|127\.0\.0\.1)([:/].*)?$ ]]; then
    scheme="${BASH_REMATCH[1]}" rest="${BASH_REMATCH[3]}"
    if grep -qi microsoft /proc/version 2>/dev/null; then
        base="$scheme$(hostname -I | awk '{print $1}')$rest"          # WSL
    elif [ "$(uname -s)" = Linux ]; then
        net=(--network host)                                           # plain Linux: localhost works as is
    else
        base="${scheme}host.docker.internal$rest"                      # macOS / Windows Docker Desktop
    fi
fi

files=()
for f in "$here"/*.hurl; do
    files+=("/ex/$(basename "$f")")
done

echo "run.sh: hurl not found, running in Docker (${HURL_IMAGE:-ghcr.io/orange-opensource/hurl:8.1.0}) against $base" >&2
exec docker run --rm ${net[@]+"${net[@]}"} -v "$here:/ex:ro" "${HURL_IMAGE:-ghcr.io/orange-opensource/hurl:8.1.0}" \
    --test --variable "base_url=$base" "${files[@]}"
