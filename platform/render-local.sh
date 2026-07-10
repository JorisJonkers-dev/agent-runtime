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
CONTEXT_REF="${CONTEXT_REF:-ghcr.io/jorisjonkers-dev/cluster-deploy-context-public@sha256:64d00fe03a271dbd03a48005d0a0cc6cc5fe43df23c0e97b649c2f8b3e78b418}"
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
    RESOLVED_VERSION="0.16.0"
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
        --output "$OUT_DIR/manifests/$env" \
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
  log "kubeconform"
  kubeconform -schema-location default \
    -strict "$OUT_DIR/manifests/" \
    || fail "kubeconform validation failed"
  log "kustomize build dry-run"
  for env in "${ENVS[@]}"; do
    kustomize build "$OUT_DIR/manifests/$env" >/dev/null \
      || fail "kustomize build failed for $env"
  done
  if grep -rl 'kind: Secret' "$OUT_DIR/manifests/" 2>/dev/null | grep -q .; then
    fail "E_FORBIDDEN_KIND: kind=Secret found in rendered manifests"
  fi
}

validate_raw_manifests_if_present() {
  [ -d "$SCRIPT_DIR/raw-manifests" ] || return 0
  deploy-config-schema artifact validate-raw-manifests \
    --deployment "$SCRIPT_DIR/deployment.yml" \
    --root "$SCRIPT_DIR/raw-manifests" \
    --output-root "$OUT_DIR/raw-manifests" \
    --forbidden-kinds Secret,ClusterRole,ClusterRoleBinding,CustomResourceDefinition,Namespace \
    --out "$OUT_DIR/raw-manifests-guard.json" \
    || fail "raw-manifests validation failed"
}

emit_contract() {
  log "leak-scan (deployment-artifact deny-list)"
  deploy-config-schema artifact leak-scan \
    --path "$OUT_DIR" \
    --mode deployment-artifact \
    || fail "E_LEAK_DETECTED: deny-list scan flagged rendered output"

  deploy-config-schema artifact emit-contract \
    --artifact-name "agent-runtime" \
    --schema-version "$RESOLVED_VERSION" \
    --context-ref "$CONTEXT_REF" \
    --environments "production" \
    --images "$SCRIPT_DIR/images.lock.json" \
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
  validate_raw_manifests_if_present
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
