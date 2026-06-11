#!/usr/bin/env bash
# Git credential helper for runner workspaces. Git calls this as
# `git credential-agent-gh-app get` and receives a repo-scoped GitHub
# App token as an HTTPS password, allowing `git push` without SSH keys.
set -u

TOKEN_HELPER="${AGENT_GITHUB_TOKEN_HELPER:-/usr/local/bin/agent-github-token}"

normalize_repo_path() {
  local url="$1"
  case "$url" in
    git@github.com:*) url="${url#git@github.com:}" ;;
    ssh://git@github.com/*) url="${url#ssh://git@github.com/}" ;;
    https://github.com/*) url="${url#https://github.com/}" ;;
    http://github.com/*) url="${url#http://github.com/}" ;;
    *) return 1 ;;
  esac
  url="${url%.git}"
  printf '%s' "$url"
}

action="${1:-}"
[ "$action" = "get" ] || exit 0

protocol=""
host=""
path=""
while IFS='=' read -r key value; do
  [ -n "$key" ] || break
  case "$key" in
    protocol) protocol="$value" ;;
    host) host="$value" ;;
    path) path="$value" ;;
  esac
done

[ "$protocol" = "https" ] || exit 0
[ "$host" = "github.com" ] || exit 0

requested_slug="${path%.git}"

if [ -n "${REPO_ALLOW:-}" ]; then
  # Multi-repo: mint a token for any requested repo in the allow-list. The
  # allow-list (set by the entrypoint from the workspace's repos) keeps the
  # blast radius bounded — a request for a repo outside it returns nothing.
  [ -n "$requested_slug" ] || exit 0
  allowed=0
  for slug in $REPO_ALLOW; do
    [ "$requested_slug" = "$slug" ] && allowed=1 && break
  done
  [ "$allowed" = 1 ] || exit 0
  token=$(AGENT_GITHUB_REPO_URL="https://${host}/${requested_slug}" "$TOKEN_HELPER" 2>/dev/null) || exit 0
else
  # Legacy single-repo gate: only serve the configured primary REPO_URL.
  primary_slug=""
  [ -n "${REPO_URL:-}" ] && primary_slug=$(normalize_repo_path "$REPO_URL" || true)
  if [ -n "$requested_slug" ] && [ -n "$primary_slug" ]; then
    [ "$requested_slug" = "$primary_slug" ] || exit 0
  fi
  token=$("$TOKEN_HELPER" 2>/dev/null) || exit 0
fi

[ -n "$token" ] || exit 0

printf 'username=x-access-token\n'
printf 'password=%s\n\n' "$token"
