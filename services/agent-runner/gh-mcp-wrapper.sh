#!/usr/bin/env bash
# Launches the GitHub MCP server (stdio) with a GitHub token.
#
# github-mcp-server requires GITHUB_PERSONAL_ACCESS_TOKEN at process
# start. The shared token helper returns a pre-seeded token or mints a
# fresh GitHub App installation token through agents-api using the
# same cache as the gh CLI wrapper.
set -u

TOKEN_HELPER="${AGENT_GITHUB_TOKEN_HELPER:-/usr/local/bin/agent-github-token}"

can_retry_mint() {
  [ -n "${GITHUB_APP_TOKEN_URL:-}" ] &&
    [ -n "${GITHUB_APP_TOKEN_BEARER:-}" ]
}

TOKEN=""
attempt=1
max_attempts="${GITHUB_MCP_TOKEN_RETRIES:-4}"
retry_sleep="${GITHUB_MCP_TOKEN_RETRY_SLEEP_SECONDS:-3}"
case "$max_attempts" in
  ''|*[!0-9]*) max_attempts=4 ;;
esac
case "$retry_sleep" in
  ''|*[!0-9]*) retry_sleep=3 ;;
esac
while :; do
  TOKEN=$("$TOKEN_HELPER" 2>/dev/null || true)
  [ -n "$TOKEN" ] && break

  if ! can_retry_mint || [ "$attempt" -ge "$max_attempts" ] 2>/dev/null; then
    break
  fi

  echo "[gh-mcp-wrapper] WARN: GitHub token unavailable; retrying before MCP startup ($attempt/$max_attempts)" >&2
  sleep "$retry_sleep"
  attempt=$((attempt + 1))
done

if [ -z "$TOKEN" ]; then
  cat >&2 <<'EOF'
[gh-mcp-wrapper] ERROR: no GitHub token available for github-mcp-server.
[gh-mcp-wrapper] Set GITHUB_PERSONAL_ACCESS_TOKEN/GH_TOKEN, or provide
[gh-mcp-wrapper] GITHUB_APP_TOKEN_URL and GITHUB_APP_TOKEN_BEARER. The
[gh-mcp-wrapper] runner also needs REPO_URL or a current git remote inside
[gh-mcp-wrapper] /workspace/<repo-name> so it can mint a repo-scoped token.
EOF
  exit 78
fi

export GITHUB_PERSONAL_ACCESS_TOKEN="$TOKEN"

# The runner uses GitHub App installation tokens. They are repo-scoped and
# cannot call user-token endpoints like GET /user, so keep the advertised
# MCP surface focused on repo, PR, issue, action, and git workflows.
TOOLSETS="${GITHUB_MCP_TOOLSETS:-repos,pull_requests,issues,actions,git}"
EXCLUDE_TOOLS="${GITHUB_MCP_EXCLUDE_TOOLS:-create_repository,fork_repository}"

args=(stdio)
[ -n "$TOOLSETS" ] && args+=(--toolsets "$TOOLSETS")
[ -n "$EXCLUDE_TOOLS" ] && args+=(--exclude-tools "$EXCLUDE_TOOLS")

exec github-mcp-server "${args[@]}"
