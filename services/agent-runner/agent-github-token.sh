#!/usr/bin/env bash
# Prints a GitHub token for the requested repo. A pre-seeded
# GH_TOKEN/GITHUB_PERSONAL_ACCESS_TOKEN wins; otherwise the helper mints
# a short-lived GitHub App installation token through agents-api and
# caches it per repo until it is close to expiry. The target repo is the
# first argument, else AGENT_GITHUB_REPO_URL, else the current git
# remote, else REPO_URL. That keeps commands run inside
# /workspace/<repo-name> scoped to that repo, while root-level commands
# still fall back to the primary repository.
set -u

CACHE_DIR="${GH_APP_TOKEN_CACHE_DIR:-/tmp}"
SKEW="${GH_APP_TOKEN_SKEW_SECONDS:-300}"
WORKSPACE_ROOT="${WORKSPACE_ROOT:-/workspace}"

# Pre-seeded tokens win and are repo-agnostic.
if [ -n "${GH_TOKEN:-}" ]; then
  printf '%s' "$GH_TOKEN"
  exit 0
fi

if [ -n "${GITHUB_PERSONAL_ACCESS_TOKEN:-}" ]; then
  printf '%s' "$GITHUB_PERSONAL_ACCESS_TOKEN"
  exit 0
fi

repo_slug() {
  local url="$1"
  case "$url" in
    git@github.com:*) url="${url#git@github.com:}" ;;
    ssh://git@github.com/*) url="${url#ssh://git@github.com/}" ;;
    https://github.com/*) url="${url#https://github.com/}" ;;
    http://github.com/*) url="${url#http://github.com/}" ;;
  esac
  url="${url%.git}"
  printf '%s' "$url"
}

target_repo_url() {
  if [ -n "${1:-}" ]; then
    printf '%s' "$1"
    return 0
  fi
  if [ -n "${AGENT_GITHUB_REPO_URL:-}" ]; then
    printf '%s' "$AGENT_GITHUB_REPO_URL"
    return 0
  fi
  if git -C "$WORKSPACE_ROOT" remote get-url origin 2>/dev/null; then
    return 0
  fi
  if git remote get-url origin 2>/dev/null; then
    return 0
  fi
  if [ -n "${REPO_URL:-}" ]; then
    printf '%s' "$REPO_URL"
    return 0
  fi
  return 1
}

REPO_URL_RESOLVED="$(target_repo_url "${1:-}")" || exit 1
[ -n "$REPO_URL_RESOLVED" ] || exit 1
SLUG="$(repo_slug "$REPO_URL_RESOLVED")"
# Per-repo cache so back-to-back calls for different repos don't clobber
# each other; an explicit GH_APP_TOKEN_CACHE still overrides for one repo.
CACHE="${GH_APP_TOKEN_CACHE:-${CACHE_DIR}/.gh-app-token-$(printf '%s' "$SLUG" | tr '/' '-')}"

parse_expiry() {
  local value="$1"
  date -d "$value" +%s 2>/dev/null ||
    date -j -f "%Y-%m-%dT%H:%M:%SZ" "$value" +%s 2>/dev/null ||
    echo 0
}

cached_token() {
  local now exp tok
  now=$(date +%s)
  [ -f "$CACHE" ] || return 1
  exp=$(sed -n 1p "$CACHE" 2>/dev/null)
  tok=$(sed -n 2p "$CACHE" 2>/dev/null)
  [ -n "$exp" ] && [ -n "$tok" ] && [ "$exp" -gt "$((now + SKEW))" ] 2>/dev/null || return 1
  printf '%s' "$tok"
}

mint_token() {
  [ -n "${GITHUB_APP_TOKEN_URL:-}" ] && [ -n "${GITHUB_APP_TOKEN_BEARER:-}" ] || return 1

  local payload resp tok exp_iso exp
  payload=$(jq -nc --arg repoUrl "$REPO_URL_RESOLVED" '{repoUrl: $repoUrl}') || return 1
  resp=$(curl -fsS --max-time 10 -X POST "$GITHUB_APP_TOKEN_URL" \
    -H "Authorization: Bearer $GITHUB_APP_TOKEN_BEARER" \
    -H "Content-Type: application/json" \
    -d "$payload" 2>/dev/null) || return 1

  tok=$(printf '%s' "$resp" | jq -r '.token // empty' 2>/dev/null)
  exp_iso=$(printf '%s' "$resp" | jq -r '.expiresAt // empty' 2>/dev/null)
  [ -n "$tok" ] || return 1

  exp=$(parse_expiry "$exp_iso")
  (
    umask 077
    printf '%s\n%s\n' "$exp" "$tok" >"$CACHE"
  )
  printf '%s' "$tok"
}

cached_token || mint_token
