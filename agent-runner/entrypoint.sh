#!/bin/sh
# agent-runner entrypoint. Three responsibilities:
#
#   1. Configure git to authenticate to GitHub over HTTPS via the
#      App-token credential helper (rewriting SSH remotes to HTTPS), so
#      clones/pushes use the short-lived GitHub App installation token —
#      no SSH deploy key is mounted or needed.
#   2. Make the runner's identity reproducible (git user.name +
#      user.email come from env or fall back to a marker).
#   3. exec the gateway jar as PID 1 (via tini so children reap).
set -eu

CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"
CLAUDE_CONFIG_DIR="${CLAUDE_CONFIG_DIR:-$HOME/.claude}"
WORKSPACE_ROOT="${WORKSPACE_ROOT:-/workspace}"
AGENT_KIT_SDD_SOURCE="${AGENT_KIT_SDD_SOURCE:-/opt/agent-kit/sdd}"
AGENT_KIT_SDD_MARKER_FILE="${AGENT_KIT_SDD_MARKER_FILE:-.agent-kit-sdd-seed.sha256}"

check_agent_kit_manifest() {
  agent_name="$1"
  manifest_path="$2"
  expected_version="${AGENT_KIT_EXPECTED_VERSION:-}"

  if [ ! -f "$manifest_path" ]; then
    echo "[entrypoint] WARN: knowledge-system agent kit manifest missing for ${agent_name} at ${manifest_path}; run agents-kb-install or /install.sh --agent all"
    return
  fi

  installed_version=$(awk -F= '/^version=/{print $2; exit}' "$manifest_path" 2>/dev/null || true)
  if [ -z "$installed_version" ]; then
    echo "[entrypoint] WARN: knowledge-system agent kit manifest for ${agent_name} has no version at ${manifest_path}"
    return
  fi

  if [ -n "$expected_version" ] && [ "$installed_version" != "$expected_version" ]; then
    echo "[entrypoint] WARN: knowledge-system agent kit manifest for ${agent_name} is ${installed_version}, expected ${expected_version}; run agents-kb-install or /install.sh --agent all"
  fi
}

check_agent_kit_manifests() {
  check_agent_kit_manifest "Claude" "${CLAUDE_CONFIG_DIR}/.knowledge-system-version"
  check_agent_kit_manifest "Codex" "${CODEX_HOME}/.knowledge-system-version"
}

# Strip a GitHub remote (ssh or https) down to its owner/repo slug.
repo_slug() {
  printf '%s' "$1" | sed \
    -e 's#^git@github.com:##' \
    -e 's#^ssh://git@github.com/##' \
    -e 's#^https://github.com/##' \
    -e 's#^http://github.com/##' \
    -e 's#\.git$##'
}

repo_dir_name() {
  basename "$1" .git
}

# The credential-helper allow-list: owner/repo slugs for the primary REPO_URL
# plus every REPO_URLS entry ("url[#branch]"). Bounding the helper to exactly
# the workspace's repos keeps the App token's blast radius the same as the old
# single-repo gate even though the helper now serves more than one repo.
derive_repo_allow() {
  _allow=""
  [ -n "${REPO_URL:-}" ] && _allow="$(repo_slug "$REPO_URL")"
  for _entry in $(printf '%s' "${REPO_URLS:-}" | tr ';\n' '  '); do
    _allow="${_allow} $(repo_slug "${_entry%%#*}")"
  done
  printf '%s' "$_allow" | tr -s ' ' | sed -e 's/^ *//' -e 's/ *$//'
}

# Trust a cloned repo dir for git, Claude Code, and Codex so the CLIs do not
# stop to ask. Mirrors the WORKSPACE_ROOT trust seeded below, per extra repo.
register_repo_trust() {
  _dir="$1"
  if ! git config --global --get-all safe.directory | grep -Fxq "$_dir"; then
    git config --global --add safe.directory "$_dir"
  fi
  if [ -f "$HOME/.claude.json" ]; then
    _ctmp=$(mktemp)
    if jq --arg d "$_dir" '
          (.projects //= {})
          | (.projects[$d] //= {})
          | (.projects[$d].hasTrustDialogAccepted //= true)
          | (.projects[$d].hasCompletedProjectOnboarding //= true)
        ' "$HOME/.claude.json" > "$_ctmp"; then
      mv "$_ctmp" "$HOME/.claude.json"
    else
      rm -f "$_ctmp"
    fi
  fi
  if [ -f "$CODEX_HOME/config.toml" ] &&
     ! grep -q "^\[projects\.\"${_dir}\"\]" "$CODEX_HOME/config.toml"; then
    printf '\n[projects."%s"]\ntrust_level = "trusted"\n' "$_dir" \
      >> "$CODEX_HOME/config.toml"
  fi
}

speckit_marker_hash() {
  _speckit_path="$1"
  _speckit_marker="$2"
  if [ -f "$_speckit_marker" ]; then
    awk -v p="$_speckit_path" '$2 == p { print $1; exit }' "$_speckit_marker"
  fi
}

speckit_seed_file() {
  _speckit_src="$1"
  _speckit_dest="$2"
  _speckit_rel="$3"
  _speckit_src_hash="$(sha256sum "$_speckit_src" | awk '{ print $1 }')"
  _speckit_old_hash="$(speckit_marker_hash "$_speckit_rel" "$_speckit_marker")"
  _speckit_record_hash=""

  mkdir -p "$(dirname "$_speckit_dest")"
  if [ ! -f "$_speckit_dest" ]; then
    cp -p "$_speckit_src" "$_speckit_dest"
    _speckit_record_hash="$_speckit_src_hash"
  elif [ -n "$_speckit_old_hash" ]; then
    _speckit_dest_hash="$(sha256sum "$_speckit_dest" | awk '{ print $1 }')"
    if [ "$_speckit_dest_hash" = "$_speckit_old_hash" ]; then
      if [ "$_speckit_dest_hash" != "$_speckit_src_hash" ]; then
        cp -p "$_speckit_src" "$_speckit_dest"
      fi
      _speckit_record_hash="$_speckit_src_hash"
    else
      if [ "$_speckit_old_hash" != "$_speckit_src_hash" ]; then
        echo "[entrypoint] WARN: ${_speckit_dest} has local changes and a stale SDD seed; leaving it unchanged"
      fi
      _speckit_record_hash="$_speckit_old_hash"
    fi
  else
    _speckit_dest_hash="$(sha256sum "$_speckit_dest" | awk '{ print $1 }')"
    if [ "$_speckit_dest_hash" = "$_speckit_src_hash" ]; then
      _speckit_record_hash="$_speckit_src_hash"
    fi
  fi

  if [ -n "$_speckit_record_hash" ]; then
    printf '%s %s\n' "$_speckit_record_hash" "$_speckit_rel" >> "$_speckit_new_marker"
  fi
}

speckit_seed() {
  _speckit_repo="$1"
  _speckit_dest_root="${_speckit_repo}/.specify"
  _speckit_marker="${_speckit_dest_root}/${AGENT_KIT_SDD_MARKER_FILE}"

  if [ ! -d "$AGENT_KIT_SDD_SOURCE" ]; then
    echo "[entrypoint] WARN: SDD source missing at ${AGENT_KIT_SDD_SOURCE}; skipping ${_speckit_repo}"
    return
  fi

  _speckit_new_marker="$(mktemp)"
  for _speckit_dir in templates scripts; do
    if [ -d "${AGENT_KIT_SDD_SOURCE}/${_speckit_dir}" ]; then
      find "${AGENT_KIT_SDD_SOURCE}/${_speckit_dir}" -type f | sort | while IFS= read -r _speckit_src; do
        _speckit_rel="${_speckit_src#${AGENT_KIT_SDD_SOURCE}/}"
        speckit_seed_file "$_speckit_src" "${_speckit_dest_root}/${_speckit_rel}" "$_speckit_rel"
      done
    fi
  done

  if [ -f "${AGENT_KIT_SDD_SOURCE}/templates/constitution-template.md" ]; then
    speckit_seed_file \
      "${AGENT_KIT_SDD_SOURCE}/templates/constitution-template.md" \
      "${_speckit_dest_root}/memory/constitution.md" \
      "memory/constitution.md"
  fi

  if [ -s "$_speckit_new_marker" ]; then
    mkdir -p "$_speckit_dest_root"
    mv "$_speckit_new_marker" "$_speckit_marker"
  else
    rm -f "$_speckit_new_marker"
  fi
}

speckit_seed_workspace() {
  for _speckit_git_dir in "${WORKSPACE_ROOT}"/*/.git; do
    [ -d "$_speckit_git_dir" ] || continue
    speckit_seed "${_speckit_git_dir%/.git}"
  done
}

append_otel_resource_attribute() {
  key="$1"
  value="$2"

  if [ -z "$value" ]; then
    return
  fi

  case ",${OTEL_RESOURCE_ATTRIBUTES:-}," in
    *",${key}="*) return ;;
  esac

  if [ -n "${OTEL_RESOURCE_ATTRIBUTES:-}" ]; then
    export OTEL_RESOURCE_ATTRIBUTES="${OTEL_RESOURCE_ATTRIBUTES},${key}=${value}"
  else
    export OTEL_RESOURCE_ATTRIBUTES="${key}=${value}"
  fi
}

configure_otel_resource_attributes() {
  append_otel_resource_attribute "service.version" "${SERVICE_VERSION:-unknown}"
  append_otel_resource_attribute "deployment.environment" "${DEPLOYMENT_ENVIRONMENT:-unknown}"
}

if [ "${AGENT_RUNNER_ENTRYPOINT_SELF_TEST:-}" = "agent-kit-manifest" ]; then
  check_agent_kit_manifests
  exit 0
fi

if [ "${AGENT_RUNNER_ENTRYPOINT_SELF_TEST:-}" = "repo-allow" ]; then
  derive_repo_allow
  exit 0
fi

if [ "${AGENT_RUNNER_ENTRYPOINT_SELF_TEST:-}" = "repo-dir" ]; then
  repo_dir_name "${REPO_URL:-}"
  exit 0
fi

if [ "${AGENT_RUNNER_ENTRYPOINT_SELF_TEST:-}" = "speckit-seed" ]; then
  speckit_seed_workspace
  exit 0
fi

if [ "${AGENT_RUNNER_ENTRYPOINT_SELF_TEST:-}" = "otel-resource-attributes" ]; then
  configure_otel_resource_attributes
  printf '%s\n' "$OTEL_RESOURCE_ATTRIBUTES"
  exit 0
fi

check_agent_kit_manifests
if [ -z "${OTEL_SERVICE_NAME:-}" ]; then
  export OTEL_SERVICE_NAME="agent-gateway"
fi
configure_otel_resource_attributes

# Bootstrap git identity — agents-api injects GIT_AUTHOR_NAME /
# GIT_AUTHOR_EMAIL when it creates the Pod; fall back to a clearly
# non-human identity so accidental commits are easy to spot.
git config --global user.name  "${GIT_AUTHOR_NAME:-Agents Agent}"
git config --global user.email "${GIT_AUTHOR_EMAIL:-agents@jorisjonkers.dev}"
git config --global init.defaultBranch main
git config --global credential.helper agent-gh-app
git config --global credential.useHttpPath true
export GIT_TERMINAL_PROMPT=0

# Restore ~/.claude.json from the latest PVC-backed backup if the
# runtime file is missing. Claude Code keeps OAuth tokens in
# ~/.claude/.credentials.json (lives on claude-credentials PVC, so
# survives Pod restarts) but its user config — including the
# org/project bindings without which `claude -p` refuses to run —
# sits at ~/.claude.json (sibling of ~/.claude/, in the container's
# writable layer, lost on every restart). Claude itself writes a
# rolling backup to ~/.claude/backups/.claude.json.backup.<ts> on
# every config write, which DOES land on the PVC, so the post-
# bootstrap behaviour is "every fresh Pod has a backup it can
# restore from". This snippet performs that restore. The latest
# backup wins so token-refresh updates propagate forward.
if [ ! -f "$HOME/.claude.json" ] && [ -d "$HOME/.claude/backups" ]; then
  latest=$(ls -1t "$HOME/.claude/backups"/.claude.json.backup.* 2>/dev/null | head -n1 || true)
  if [ -n "$latest" ]; then
    cp "$latest" "$HOME/.claude.json"
  fi
fi

# Suppress Claude's first-run prompts without clobbering a restored
# config. The OAuth token persists on the claude-credentials PVC, but
# the per-user flags that record "theme chosen / onboarding done /
# directory trusted" live in ~/.claude.json, which is lost on every
# fresh Pod unless a backup happened to exist. A logged-in CLI that
# still has theme==undefined or hasCompletedOnboarding!=true re-runs
# the onboarding wizard (which is also where the theme picker lives),
# and an untrusted project dir re-shows the trust dialog — both block
# the non-interactive tmux session. `jq //=` fills only the missing
# keys, so a real restored config (its own theme, oauthAccount, the
# project history array) is preserved verbatim. WORKSPACE_ROOT keys
# the per-project trust entry to the dir the gateway launches the CLI
# in (AgentSessionManager defaults cwd to the gateway's workspace-root).
if ! git config --global --get-all safe.directory | grep -Fxq "$WORKSPACE_ROOT"; then
  git config --global --add safe.directory "$WORKSPACE_ROOT"
fi
if [ ! -f "$HOME/.claude.json" ]; then
  echo '{}' > "$HOME/.claude.json"
fi
claude_tmp=$(mktemp)
if jq --arg ws "$WORKSPACE_ROOT" '
      (.theme //= "dark")
      | (.hasCompletedOnboarding //= true)
      | (.bypassPermissionsModeAccepted //= true)
      | (.projects //= {})
      | (.projects[$ws] //= {})
      | (.projects[$ws].hasTrustDialogAccepted //= true)
      | (.projects[$ws].hasCompletedProjectOnboarding //= true)
    ' "$HOME/.claude.json" > "$claude_tmp"; then
  mv "$claude_tmp" "$HOME/.claude.json"
else
  rm -f "$claude_tmp"
fi

# Register MCP servers into Claude Code from the declarative ConfigMap
# (agents-mcp-servers, mounted at /etc/agent-mcp). The selected profile
# is an mcpServers object with @KB_URL@/@KB_BEARER_TOKEN@ placeholders
# filled from the Pod env, so no secret is baked into the ConfigMap.
# The managed servers win for their own keys; any hand-added server
# already in the config is preserved. Absent mount (feature off) => no-op.
AGENT_MCP_PROFILE="${AGENT_MCP_PROFILE:-minimal}"
case "$AGENT_MCP_PROFILE" in
  minimal|frontend|cluster|code-intel|full-diagnostic) ;;
  *)
    echo "[entrypoint] WARN: unknown AGENT_MCP_PROFILE=$AGENT_MCP_PROFILE; using minimal"
    AGENT_MCP_PROFILE="minimal"
    ;;
esac

AGENT_MCP_DIR="${AGENT_MCP_DIR:-/etc/agent-mcp}"
if [ -n "${AGENT_MCP_SERVERS_FILE:-}" ]; then
  MCP_SERVERS_FILE="$AGENT_MCP_SERVERS_FILE"
else
  MCP_PROFILE_FILE="${AGENT_MCP_DIR}/claude-mcp-servers.${AGENT_MCP_PROFILE}.json"
  MCP_MINIMAL_FILE="${AGENT_MCP_DIR}/claude-mcp-servers.minimal.json"
  MCP_LEGACY_FILE="${AGENT_MCP_DIR}/claude-mcp-servers.json"
  if [ -f "$MCP_PROFILE_FILE" ]; then
    MCP_SERVERS_FILE="$MCP_PROFILE_FILE"
  elif [ -f "$MCP_MINIMAL_FILE" ]; then
    echo "[entrypoint] WARN: MCP profile $AGENT_MCP_PROFILE not found; using minimal"
    MCP_SERVERS_FILE="$MCP_MINIMAL_FILE"
  else
    MCP_SERVERS_FILE="$MCP_LEGACY_FILE"
  fi
fi
if [ -f "$MCP_SERVERS_FILE" ]; then
  mcp_rendered=$(mktemp)
  sed -e "s|@KB_URL@|${KB_URL:-}|g" \
      -e "s|@KB_BEARER_TOKEN@|${KB_BEARER_TOKEN:-}|g" \
      "$MCP_SERVERS_FILE" > "$mcp_rendered"
  mcp_merged=$(mktemp)
  if jq -s '
        .[0] as $cfg | .[1] as $servers
        | $cfg + { mcpServers: (($cfg.mcpServers // {}) * $servers) }
      ' "$HOME/.claude.json" "$mcp_rendered" > "$mcp_merged" 2>/dev/null; then
    mv "$mcp_merged" "$HOME/.claude.json"
  else
    echo "[entrypoint] WARN: failed to merge MCP servers from $MCP_SERVERS_FILE"
    rm -f "$mcp_merged"
  fi
  rm -f "$mcp_rendered"
fi

# Trust the workspace for Codex the same way. ~/.codex sits on the
# codex-credentials PVC (CODEX_HOME), so auth.json persists, but a
# fresh checkout of the workspace dir is "untrusted" until the
# interactive trust prompt is answered, and the default approval
# policy stops to ask before each command — both stall the tmux
# session. Seeding global non-interactive approval/sandbox plus a
# per-project trusted entry removes every prompt. Only created when
# absent so a hand-edited config on the PVC is never overwritten.
if [ ! -f "$CODEX_HOME/config.toml" ]; then
  mkdir -p "$CODEX_HOME"
  cat > "$CODEX_HOME/config.toml" <<EOF
approval_policy = "never"
sandbox_mode = "danger-full-access"

[projects."$WORKSPACE_ROOT"]
trust_level = "trusted"
EOF
fi

# Codex managed config: force ChatGPT Apps/connectors off and re-seed the
# MCP server set on EVERY boot. Codex exposes account-level connectors as
# `codex_apps.*` tools; the GitHub connector among them is bound to the
# ChatGPT account's own GitHub OAuth identity and therefore inherits every
# org that identity can see (employer orgs included), completely bypassing
# the repo-scoped GitHub App installation token. The only sanctioned GitHub
# access for an agent is the `github` MCP server (gh-mcp-wrapper → a short-
# lived, repo-scoped App token). `features.apps = false` gates the whole
# experimental connectors subsystem off; `apps._default.enabled = false` is
# defence-in-depth (per-app disables are ignored upstream — openai/codex
# #17588), so removing the connector on the ChatGPT account (see SETUP.md)
# stays the hard guarantee. Managed each boot so the invariant survives a
# hand-edited PVC config; the create-once approval/sandbox/trust config and
# any unrelated [features] dotted keys are preserved.
#
# Codex reads remote HTTP MCP servers natively and takes the bearer at
# request time from KB_BEARER_TOKEN (bearer_token_env_var), so no secret
# lands in config.toml — only @KB_URL@ is substituted.
if [ -n "${AGENT_CODEX_MCP_FILE:-}" ]; then
  CODEX_MCP_FILE="$AGENT_CODEX_MCP_FILE"
else
  CODEX_PROFILE_FILE="${AGENT_MCP_DIR}/codex-mcp-servers.${AGENT_MCP_PROFILE}.toml"
  CODEX_MINIMAL_FILE="${AGENT_MCP_DIR}/codex-mcp-servers.minimal.toml"
  CODEX_LEGACY_FILE="${AGENT_MCP_DIR}/codex-mcp-servers.toml"
  if [ -f "$CODEX_PROFILE_FILE" ]; then
    CODEX_MCP_FILE="$CODEX_PROFILE_FILE"
  elif [ -f "$CODEX_MINIMAL_FILE" ]; then
    echo "[entrypoint] WARN: Codex MCP profile $AGENT_MCP_PROFILE not found; using minimal"
    CODEX_MCP_FILE="$CODEX_MINIMAL_FILE"
  else
    CODEX_MCP_FILE="$CODEX_LEGACY_FILE"
  fi
fi
if [ -f "$CODEX_HOME/config.toml" ]; then
  codex_tmp=$(mktemp)
  # Strip every managed section so re-applying never duplicates a key: the
  # [features]/[apps*] disable tables, any dotted features.apps / apps._default
  # keys, and the [mcp_servers.*] tables. Other top-level tables (projects)
  # and keys are passed through untouched.
  awk '
    /^\[mcp_servers\./ { skip = 1; next }
    /^\[features\]/    { skip = 1; next }
    /^\[apps\]/        { skip = 1; next }
    /^\[apps\./        { skip = 1; next }
    /^\[/              { skip = 0 }
    skip               { next }
    /^[[:space:]]*features\.apps[[:space:]]*=/ { next }
    /^[[:space:]]*apps\._default\./            { next }
    { print }
  ' "$CODEX_HOME/config.toml" > "$codex_tmp"
  {
    echo ""
    echo "[features]"
    echo "apps = false"
    echo ""
    echo "[apps._default]"
    echo "enabled = false"
    if [ -f "$CODEX_MCP_FILE" ]; then
      echo ""
      sed -e "s|@KB_URL@|${KB_URL:-}|g" "$CODEX_MCP_FILE"
    fi
  } >> "$codex_tmp"
  # Collapse runs of blank lines so repeated boots don't grow the file.
  awk 'NF{print; blank=0; next} {blank++} blank<2' "$codex_tmp" > "$CODEX_HOME/config.toml"
  rm -f "$codex_tmp"
fi

clone_repo_into_workspace() {
  _repo_url="$1"
  _repo_branch="${2:-}"
  _repo_name="$(repo_dir_name "$_repo_url")"
  _repo_target="${WORKSPACE_ROOT}/${_repo_name}"

  if [ -d "${_repo_target}/.git" ]; then
    echo "[entrypoint] ${_repo_name} already present; skipping clone"
    register_repo_trust "$_repo_target"
    return
  fi

  # Migrate legacy single-repo PVCs that cloned the primary directly
  # into /workspace. Keep staged inputs at the multi-repo root; move
  # the git checkout into /workspace/<repo-name>.
  if [ -d "${WORKSPACE_ROOT}/.git" ] && [ ! -e "$_repo_target" ]; then
    echo "[entrypoint] migrating legacy workspace root clone into ${_repo_target}"
    mkdir -p "$_repo_target"
    find "$WORKSPACE_ROOT" -mindepth 1 -maxdepth 1 \
      ! -name "$_repo_name" \
      ! -name ".agent-inputs" \
      -exec mv {} "$_repo_target/" \;
    register_repo_trust "$_repo_target"
    return
  fi

  if [ -e "$_repo_target" ]; then
    echo "[entrypoint] WARN: ${_repo_target} exists but is not a git checkout; skipping clone of ${_repo_url}"
    return
  fi

  if [ -n "$_repo_branch" ]; then
    git clone --branch "$_repo_branch" "$_repo_url" "$_repo_target" \
      || echo "[entrypoint] WARN: clone of ${_repo_url} failed; continuing"
  else
    git clone "$_repo_url" "$_repo_target" \
      || echo "[entrypoint] WARN: clone of ${_repo_url} failed; continuing"
  fi
  if [ -d "${_repo_target}/.git" ]; then
    register_repo_trust "$_repo_target"
  fi
}

# Make every git operation prefer HTTPS so the credential helper can
# provide the short-lived GitHub App token. Repo URLs stored in the SSH
# form (git@github.com:owner/repo) are rewritten to HTTPS before any
# clone, so the primary and additional repos all authenticate through the
# App installation — no per-repo SSH deploy key needed. This also covers
# `gh pr create` when gh shells out to git.
git config --global url.https://github.com/.insteadOf git@github.com:
git config --global url.https://github.com/.insteadOf ssh://git@github.com/

# Bound the App-token credential helper to exactly this workspace's repos
# (primary + REPO_URLS), exported so the helper and the gateway's child git
# processes inherit it. Empty (no repo configured) leaves the helper's own
# default. Set before any clone so those clones authenticate.
REPO_ALLOW="$(derive_repo_allow)"
if [ -n "$REPO_ALLOW" ]; then
  export REPO_ALLOW
fi

# Clone every workspace repository into its own named folder under
# /workspace. The orchestrator passes REPO_URL (+ optional REPO_BRANCH)
# for the primary repository and REPO_URLS for additional repositories.
# /workspace itself remains the multi-repo root where agents start. All
# clones go over HTTPS, authenticated by the App-token credential helper.
if [ -n "${REPO_URL:-}" ]; then
  clone_repo_into_workspace "$REPO_URL" "${REPO_BRANCH:-}"
fi

# Multi-repo workspaces: clone every repo in REPO_URLS (space/newline/semicolon
# separated, each "url[#branch]") into its own subdir of the workspace over
# HTTPS, authenticated by the App-token credential helper. Idempotent: a repo
# already cloned is left alone.
if [ -n "${REPO_URLS:-}" ]; then
  for entry in $(printf '%s' "$REPO_URLS" | tr ';\n' '  '); do
    repo_clone_url="${entry%%#*}"
    repo_branch=""
    case "$entry" in *"#"*) repo_branch="${entry#*#}" ;; esac
    clone_repo_into_workspace "$repo_clone_url" "$repo_branch"
  done
fi

speckit_seed_workspace

# The gateway shares this Pod's memory cgroup with the agent CLIs it
# launches (Claude Code, Codex) and whatever the workspace itself runs.
# MaxRAMPercentage made the JVM lazily fill 75% of the Pod limit with
# garbage before collecting, starving those co-tenants and getting the
# whole Pod OOMKilled. The gateway is a thin streaming relay with a tiny
# live set, so an absolute 1g heap is ample and leaves the rest of the
# Pod's RAM for the CLIs regardless of the limit.
exec java \
  -XX:+UseZGC \
  -Xmx1g \
  -javaagent:/opt/otel-javaagent.jar \
  -jar /opt/agent-gateway.jar
