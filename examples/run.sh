#!/usr/bin/env bash
# Runs the base specs' published Hurl examples against a running service.
# They are the happy path only, and not a gate: passing them doesn't mean the harness's checks pass.
#
# Usage: examples/run.sh [base-url]        (default http://localhost:8080)
# Env:   HURL  the hurl binary (default: hurl on your PATH; https://hurl.dev)
#
# Specs released during the day publish their examples at /specs/<slug>/examples.hurl; save
# them here as <slug>.hurl and they're picked up too.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
base="${1:-http://localhost:8080}"
"${HURL:-hurl}" --test --variable "base_url=$base" "$here"/*.hurl
