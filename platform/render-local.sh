#!/usr/bin/env bash
# render-local.sh -- run the same deployment checks CI runs, locally.
#
# This was ~400 lines of bash duplicated into every service repository, and the
# copies drifted from the CI implementation they were meant to mirror. The logic
# now lives in @jorisjonkers-dev/deploy-check, which the deploy-preview action
# runs too, so a local result and a CI result cannot disagree.
#
# The schema version and context ref are read out of this repository's own
# workflow rather than restated here. The previous copy hardcoded both and both
# went stale -- the schema version by four minor releases, the context digest by
# two republications.
#
# Usage:
#   ./platform/render-local.sh [--context-dir DIR] [-- <extra deploy-check args>]
#
# Requires node, and either oras (to pull the context by digest) or
# --context-dir pointing at an already-pulled context package. Installing the
# pinned toolkit reads npm.pkg.github.com, so export GITHUB_TOKEN (or
# NODE_AUTH_TOKEN) first: export GITHUB_TOKEN="$(gh auth token)".
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

fail() { echo "ERROR: $*" >&2; exit 1; }

CONTEXT_DIR=""
EXTRA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --context-dir) [ $# -ge 2 ] || fail "--context-dir requires a path"; CONTEXT_DIR="$2"; shift 2 ;;
    --) shift; EXTRA=("$@"); break ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) fail "unknown option: $1 (pass extra deploy-check flags after --)" ;;
  esac
done

# Single source of truth: whatever the workflows pin is what a local run uses.
value_from_workflows() {
  local key="$1"
  grep -rhoE "^[[:space:]]*${key}:[[:space:]]*[^[:space:]]+" .github/workflows/*.yml 2>/dev/null \
    | awk '{print $2}' | sort -u | head -1
}

# deploy-check ships from the same repository as the reusable workflows and is
# released with them, so the version comment on the deploy-validate pin is the
# version to run. Hardcoding it here is what let the previous copy rot.
DEPLOY_CHECK_VERSION="$(
  grep -rhoE 'github-workflows/\.github/workflows/deploy-(validate|artifact)\.yml@[0-9a-f]{40} # v[0-9]+\.[0-9]+\.[0-9]+' \
    .github/workflows/*.yml 2>/dev/null \
    | grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+$' | tr -d v \
    | sort -t. -k1,1n -k2,2n -k3,3n | tail -1
)"
[ -n "$DEPLOY_CHECK_VERSION" ] \
  || fail "no '# vX.Y.Z' comment on a deploy-validate/deploy-artifact pin in .github/workflows/*.yml"

SCHEMA_VERSION="$(value_from_workflows 'schema-version')"
CONTEXT_REF="$(value_from_workflows 'context-ref')"
[ -n "$SCHEMA_VERSION" ] || fail "no schema-version found in .github/workflows/*.yml"
[ -n "$CONTEXT_REF" ] || fail "no context-ref found in .github/workflows/*.yml"

if [ -z "$CONTEXT_DIR" ]; then
  command -v oras >/dev/null 2>&1 \
    || fail "oras is not installed; install it or pass --context-dir <pulled-context>"
  CONTEXT_DIR="$(mktemp -d)"
  echo "[render-local] pulling context $CONTEXT_REF" >&2
  oras pull "$CONTEXT_REF" --output "$CONTEXT_DIR" >&2 \
    || fail "oras pull failed for $CONTEXT_REF"
fi

# GitHub Packages requires a token even for public packages, and npx would
# otherwise resolve the scope against registry.npmjs.org and 404. Build a
# throwaway npmrc rather than touching the developer's own.
TOKEN="${GITHUB_TOKEN:-${NODE_AUTH_TOKEN:-}}"
if [ -z "$TOKEN" ] && command -v gh >/dev/null 2>&1; then
  TOKEN="$(gh auth token 2>/dev/null || true)"
fi
[ -n "$TOKEN" ] || fail "no GitHub token: export GITHUB_TOKEN=\"\$(gh auth token)\" (npm.pkg.github.com requires auth even for public packages)"

NPMRC="$(mktemp)"
trap 'rm -f "$NPMRC"' EXIT
printf '@jorisjonkers-dev:registry=https://npm.pkg.github.com\n//npm.pkg.github.com/:_authToken=%s\n' "$TOKEN" > "$NPMRC"
export npm_config_userconfig="$NPMRC"
# The toolkit install inside deploy-check reads this too.
export NODE_AUTH_TOKEN="$TOKEN"

echo "[render-local] deploy-check ${DEPLOY_CHECK_VERSION}, toolkit ${SCHEMA_VERSION}" >&2
exec npx --yes "@jorisjonkers-dev/deploy-check@${DEPLOY_CHECK_VERSION}" preview \
  --deploy-dir platform \
  --schema-version "$SCHEMA_VERSION" \
  --context-ref "$CONTEXT_REF" \
  --context-dir "$CONTEXT_DIR" \
  "${EXTRA[@]+"${EXTRA[@]}"}"
