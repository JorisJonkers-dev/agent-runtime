#!/usr/bin/env bash
# render-local.sh — local CI-parity render for agent-runtime.
#
# Mirrors what deploy-validate.yml / deploy-artifact.yml do in CI:
#   validate -> render -> kubeconform -> leak-scan -> scorecard
#
# Usage:
#   ./platform/render-local.sh [--diff] [--context-dir PATH] [--scorecard-only]
#
# Options:
#   --diff            compare rendered output against committed fixtures; exit 1 on drift
#   --context-dir     use a local context directory (bypasses digest requirement for local dev)
#   --scorecard-only  skip install/pull/render and only evaluate the SC-11 scorecard
#                     (dry mode: works without the npm package installed)
#
# The scorecard functions are pure shell + jq so they stay testable without
# network access or the @jorisjonkers-dev/deploy-config-schema npm package.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

FLAG_DIFF=false
FLAG_SCORECARD_ONLY=false
CONTEXT_DIR="${CONTEXT_DIR:-}"
CONTEXT_REF="${CONTEXT_REF:-ghcr.io/jorisjonkers-dev/cluster-deploy-context-public@sha256:9479bc22ae11183c0b68f257d2c1a21455be8c3cff602d3a491ea3ff31d01fe3}"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/out}"
RESOLVED_VERSION=""
NPM_PROVENANCE_VERIFIED="${NPM_PROVENANCE_VERIFIED:-true}"

ENVS=(production)
FRAGMENTS=(
  kubernetes-workload-fragment
  traefik-route-fragment
  gatus-endpoint-fragment
  edge-catalog-fragment
  image-metadata-fragment
)

log() { echo "[render-local] $*" >&2; }
warn() { echo "[render-local] WARNING: $*" >&2; }
fail() {
  echo "ERROR: $*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: render-local.sh [--diff] [--context-dir PATH] [--scorecard-only]

Options:
  --diff            compare rendered output against committed fixtures; exit 1 on drift
  --context-dir     use a local context directory (bypasses digest requirement for local dev)
  --scorecard-only  skip install/pull/render and only evaluate the SC-11 scorecard
  -h, --help        show this help
USAGE
}

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --diff)
        FLAG_DIFF=true
        shift
        ;;
      --context-dir)
        [ $# -ge 2 ] || fail "--context-dir requires a PATH argument"
        CONTEXT_DIR="$2"
        shift 2
        ;;
      --scorecard-only)
        FLAG_SCORECARD_ONLY=true
        shift
        ;;
      -h | --help)
        usage
        exit 0
        ;;
      *)
        usage >&2
        fail "unknown option: $1"
        ;;
    esac
  done
}

resolve_schema_version() {
  if [ -n "${SCHEMA_VERSION:-}" ]; then
    RESOLVED_VERSION="$SCHEMA_VERSION"
    log "Using SCHEMA_VERSION from env: $RESOLVED_VERSION"
  elif [ -f "$REPO_ROOT/.platform/deploy-version" ]; then
    RESOLVED_VERSION="$(tr -d '[:space:]' < "$REPO_ROOT/.platform/deploy-version")"
    log "Using schema version from .platform/deploy-version: $RESOLVED_VERSION"
  else
    RESOLVED_VERSION="0.20.0"
    log "Using baked-in schema version: $RESOLVED_VERSION"
  fi
}

install_schema() {
  log "Installing @jorisjonkers-dev/deploy-config-schema@$RESOLVED_VERSION"
  npm install --global "@jorisjonkers-dev/deploy-config-schema@$RESOLVED_VERSION" \
    --registry https://npm.pkg.github.com \
    || fail "failed to install deploy-config-schema@$RESOLVED_VERSION"
}

npm_audit_and_scorecard_init() {
  if npm audit signatures --scope @jorisjonkers-dev >/dev/null 2>&1; then
    NPM_PROVENANCE_VERIFIED="true"
  else
    NPM_PROVENANCE_VERIFIED="false"
    warn "npm audit signatures failed; npm_signatures_verified=fail"
  fi
}

require_digest_ref() {
  case "$1" in
    *@sha256:*) ;;
    *) fail "E_CONTEXT_REF_NOT_PINNED: context ref must be digest-pinned (got: $1). Pass --context-dir for local dev." ;;
  esac
}

# Locate cluster-context-public.yml inside a context directory, as CI does.
find_cluster_context() {
  local match
  match=$(find "$1" -type f -name 'cluster-context-public.yml' 2>/dev/null | sort | head -1)
  [ -n "$match" ] || fail "E_CONTEXT_FILE_MISSING: cluster-context-public.yml not found under $1"
  printf '%s\n' "$match"
}

pull_or_use_local_context() {
  if [ -n "$CONTEXT_DIR" ]; then
    CONTEXT_PKG_DIR="$CONTEXT_DIR"
    warn "--context-dir bypasses OCI digest requirement. Not suitable for CI."
  else
    require_digest_ref "$CONTEXT_REF"
    log "Pulling public cluster context: $CONTEXT_REF"
    mkdir -p "$OUT_DIR/context-pkg"
    oras pull "$CONTEXT_REF" --output "$OUT_DIR/context-pkg" \
      || fail "failed to pull context package $CONTEXT_REF"
    CONTEXT_PKG_DIR="$OUT_DIR/context-pkg"
  fi
  CONTEXT_FILE="$(find_cluster_context "$CONTEXT_PKG_DIR")"
}

render_all_fragments() {
  local env fragment
  for env in "${ENVS[@]}"; do
    mkdir -p "$OUT_DIR/manifests/$env" "$OUT_DIR/metadata/$env"
    for fragment in "${FRAGMENTS[@]}"; do
      log "render $fragment ($env)"
      deploy-config-schema render "$fragment" "$SCRIPT_DIR" \
        --env "$env" \
        --context "$CONTEXT_REF" \
        --context-dir "$CONTEXT_PKG_DIR" \
        --images "$SCRIPT_DIR/images.lock.json" \
        --output "$OUT_DIR/manifests/$env/$fragment.yaml" \
        || fail "render failed for $fragment ($env)"
    done
    deploy-config-schema artifact emit-kustomization-health \
      --deployment "$SCRIPT_DIR/deployment.yml" \
      --env "$env" \
      --image-digests "$SCRIPT_DIR/images.lock.json" \
      --out "$OUT_DIR/metadata/$env/kustomization-health.yml" \
      || fail "emit-kustomization-health failed ($env)"
  done
}

validate_rendered_output() {
  local env
  # A fragment is a schema document wrapping its payload, so kubeconform and
  # kustomize -- both of which expect apiVersion/kind at the top level -- always
  # fail when pointed at out/manifests. CI does not validate that shape at all;
  # render correctness comes from the schema CLI. The applyable objects are what
  # can be checked, so lift them first and validate those.
  for env in "${ENVS[@]}"; do
    log "emit-apply-bundle ($env)"
    deploy-config-schema artifact emit-apply-bundle \
      --manifests "$OUT_DIR/manifests/$env" \
      --out "$OUT_DIR/apply/$env" \
      || fail "emit-apply-bundle failed for $env"
    if command -v kubeconform >/dev/null 2>&1; then
      log "kubeconform ($env)"
      kubeconform -schema-location default -strict "$OUT_DIR/apply/$env" \
        || fail "kubeconform validation failed for $env"
    else
      warn "kubeconform not installed; skipping schema validation of $env"
    fi
    log "kustomize build dry-run ($env)"
    kustomize build "$OUT_DIR/apply/$env" >/dev/null \
      || fail "kustomize build failed for $env"
  done
  if grep -rl 'kind: Secret' "$OUT_DIR/manifests/" 2>/dev/null | grep -q .; then
    fail "E_FORBIDDEN_KIND: kind=Secret found in rendered manifests"
  fi
}

# The toolkit exposes only emit-apply-bundle, emit-contract and
# emit-kustomization-health under `artifact`; there is no
# validate-raw-manifests subcommand in any published version, so a
# rawManifests declaration cannot be checked locally. Say so rather than
# scoring an unexplained fail.
reject_unsupported_raw_manifests() {
  grep -qE '^[[:space:]]*rawManifests:' <(deployment_source "$SCRIPT_DIR/deployment.yml") || return 0
  fail "E_RAW_MANIFESTS_UNSUPPORTED: deployment.yml declares rawManifests, but deploy-config-schema exposes no artifact validate-raw-manifests subcommand. CI cannot guard it either."
}

emit_contract() {
  deploy-config-schema artifact emit-contract \
    --artifact-name "agent-runtime" \
    --context-ref "$CONTEXT_REF" \
    --environments "production" \
    --images "$SCRIPT_DIR/images.lock.json" \
    --deployment "$SCRIPT_DIR/deployment.yml" \
    --context "$CONTEXT_FILE" \
    --provenance-verified "$NPM_PROVENANCE_VERIFIED" \
    --out "$OUT_DIR/artifact-contract.yaml" \
    || fail "emit-contract failed"
}

deployment_source() {
  sed 's/#.*$//' "$1"
}

scorecard_images_pinned() {
  local lock="$1" ref
  [ -f "$lock" ] || {
    echo "fail"
    return
  }
  while IFS= read -r ref; do
    case "$ref" in
      *@sha256:*) ;;
      *)
        echo "fail"
        return
        ;;
    esac
  done < <(jq -r 'if type == "array" then map(.ref) else [.[]] end | .[]' "$lock")
  echo "pass"
}

scorecard_route_owner_authmode() {
  local src="$1"
  if ! grep -qE '^[[:space:]]*routes:' <<<"$src"; then
    echo "not_applicable"
  elif grep -qE '^[[:space:]]*owner:' <<<"$src" && grep -qE '^[[:space:]]*authMode:' <<<"$src"; then
    echo "pass"
  else
    echo "fail"
  fi
}

scorecard_stateful_policy() {
  local src="$1"
  if ! grep -qE '^[[:space:]]*stateful:[[:space:]]*true' <<<"$src"; then
    echo "not_applicable"
  elif grep -qE '^[[:space:]]*migrationPolicy:' <<<"$src"; then
    echo "pass"
  else
    echo "fail"
  fi
}

scorecard_raw_manifests() {
  local src="$1"
  if ! grep -qE '^[[:space:]]*rawManifests:' <<<"$src"; then
    echo "not_applicable"
  elif [ -f "$OUT_DIR/raw-manifests-guard.json" ]; then
    echo "pass"
  else
    echo "fail"
  fi
}

compute_scorecard() {
  local deployment="$1" lock="$2"
  local src
  src="$(deployment_source "$deployment")"

  local schema_pinned=fail context_pinned=fail health_declared=fail
  local rollback=fail no_raw_secrets=pass npm_flag=fail
  local workload_count health_count

  grep -qE '^[[:space:]]*schemaVersion:[[:space:]]*"?[0-9]' <<<"$src" && schema_pinned=pass
  case "$CONTEXT_REF" in *@sha256:*) context_pinned=pass ;; esac

  workload_count="$(grep -cE '^[[:space:]]*-[[:space:]]*name:' <<<"$src" || true)"
  health_count="$(grep -cE '^[[:space:]]*health:' <<<"$src" || true)"
  if [ "$workload_count" -gt 0 ] && [ "$health_count" -ge "$workload_count" ]; then
    health_declared=pass
  fi

  if grep -qE '^[[:space:]]*rollbackTargetRetention:' <<<"$src" \
    && grep -qE '^[[:space:]]*acknowledged:[[:space:]]*true' <<<"$src"; then
    rollback=pass
  fi

  if [ -d "$SCRIPT_DIR/raw-manifests" ] \
    && grep -rl 'kind: Secret' "$SCRIPT_DIR/raw-manifests" 2>/dev/null | grep -q .; then
    no_raw_secrets=fail
  fi
  if [ -d "$OUT_DIR/manifests" ] \
    && grep -rl 'kind: Secret' "$OUT_DIR/manifests" 2>/dev/null | grep -q .; then
    no_raw_secrets=fail
  fi

  [ "$NPM_PROVENANCE_VERIFIED" = "true" ] && npm_flag=pass

  mkdir -p "$OUT_DIR"
  jq -n \
    --arg schema_pinned "$schema_pinned" \
    --arg context_pinned "$context_pinned" \
    --arg no_latest_images "$(scorecard_images_pinned "$lock")" \
    --arg health_declared "$health_declared" \
    --arg route_owner_authmode_declared "$(scorecard_route_owner_authmode "$src")" \
    --arg rollback_retention_acknowledged "$rollback" \
    --arg no_raw_secrets "$no_raw_secrets" \
    --arg stateful_policy_declared "$(scorecard_stateful_policy "$src")" \
    --arg raw_manifests_guarded "$(scorecard_raw_manifests "$src")" \
    --arg npm_signatures_verified "$npm_flag" \
    '{
      schema_pinned: $schema_pinned,
      context_pinned: $context_pinned,
      no_latest_images: $no_latest_images,
      health_declared: $health_declared,
      route_owner_authmode_declared: $route_owner_authmode_declared,
      rollback_retention_acknowledged: $rollback_retention_acknowledged,
      no_raw_secrets: $no_raw_secrets,
      stateful_policy_declared: $stateful_policy_declared,
      raw_manifests_guarded: $raw_manifests_guarded,
      npm_signatures_verified: $npm_signatures_verified
    }' > "$OUT_DIR/scorecard.json"
}

write_scorecard_outputs() {
  {
    echo "# Deployment readiness scorecard — agent-runtime"
    echo ""
    echo "| Check | Status |"
    echo "|-------|--------|"
    jq -r 'to_entries[] | "| \(.key) | \(.value) |"' "$OUT_DIR/scorecard.json"
    echo ""
    echo "pass = ready · fail = blocks deployment · not_applicable = check does not apply"
  } > "$OUT_DIR/scorecard.md"
  cat "$OUT_DIR/scorecard.md"
}

compare_against_fixtures() {
  local drift
  drift="$(diff -rq "$OUT_DIR" "$SCRIPT_DIR/fixtures" 2>&1 || true)"
  if [ -n "$drift" ]; then
    echo "ERROR: Rendered output differs from committed fixtures:" >&2
    echo "$drift" >&2
    echo "Run render-local.sh without --diff to regenerate fixtures, then commit." >&2
    exit 1
  fi
  log "No drift detected between rendered output and committed fixtures."
}

exit_based_on_scorecard() {
  local fails
  fails="$(jq '[to_entries[] | select(.value == "fail")] | length' "$OUT_DIR/scorecard.json")"
  if [ "$fails" -gt 0 ]; then
    echo "ERROR: Scorecard has $fails fail(s). See out/scorecard.md for details." >&2
    exit 1
  fi
  log "All scorecard checks passed."
}

main() {
  parse_args "$@"

  if [ "$FLAG_SCORECARD_ONLY" = true ]; then
    log "scorecard-only dry mode (npm package not required)"
    compute_scorecard "$SCRIPT_DIR/deployment.yml" "$SCRIPT_DIR/images.lock.json"
    write_scorecard_outputs
    exit_based_on_scorecard
    return
  fi

  resolve_schema_version
  install_schema
  npm_audit_and_scorecard_init
  pull_or_use_local_context
  render_all_fragments
  validate_rendered_output
  reject_unsupported_raw_manifests
  emit_contract
  compute_scorecard "$SCRIPT_DIR/deployment.yml" "$SCRIPT_DIR/images.lock.json"
  write_scorecard_outputs
  if [ "$FLAG_DIFF" = true ]; then
    compare_against_fixtures
  fi
  exit_based_on_scorecard
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
