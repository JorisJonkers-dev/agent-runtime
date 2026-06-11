#!/usr/bin/env bash
# `gh` wrapper for agent runners. Before delegating to the real gh, it
# exports a fresh repo-scoped token as GH_TOKEN. The shared token helper
# reuses a cached GitHub App installation token while it still has spare
# life, so back-to-back gh calls mint at most once per token lifetime.
#
# Pure passthrough — `gh` still runs — when the App is not wired (no
# endpoint URL / bearer / repo in the environment) or when minting
# fails, so a misconfiguration degrades to unauthenticated gh rather
# than a hard error.
set -u

REAL_GH=/usr/bin/gh
TOKEN_HELPER="${AGENT_GITHUB_TOKEN_HELPER:-/usr/local/bin/agent-github-token}"

if [ -z "${GH_TOKEN:-}" ]; then
  token=$("$TOKEN_HELPER" 2>/dev/null || true)
  if [ -n "$token" ]; then
    export GH_TOKEN="$token"
  fi
fi

exec "$REAL_GH" "$@"
