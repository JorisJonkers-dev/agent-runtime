# syntax=docker/dockerfile:1

# agent-runner: the image every per-workspace Pod boots from. Bundles:
#   - Claude Code CLI + Codex CLI (the unit of "the agent")
#   - tmux + bash + git + gh (the agent's tools)
#   - language toolchains: node 22, jdk 21, python, go (pinned below)
#   - uv + mise for fast Python/toolchain management
#   - ast-grep (sg) for AST-scoped symbol search
#   - the agent-gateway jar as PID 1, owning the tmux server and the
#     REST/WS plane agents-api talks to
#
# The image is deliberately mutable inside the container; adversarial
# use is in scope. Isolation comes from the Pod boundary + the
# `agent-runners` NetworkPolicy + the credential PVCs being mounted
# only where they are needed.

FROM gradle:9.5.1-jdk21-alpine AS gateway-build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY gradle/ gradle/
COPY services/agents-api/build.gradle.kts services/agents-api/
COPY services/agent-gateway/build.gradle.kts services/agent-gateway/
RUN --mount=type=secret,id=github_token \
    --mount=type=secret,id=github_actor \
    set -eu; \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)"; \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)"; \
    export GITHUB_TOKEN GITHUB_ACTOR; \
    gradle :services:agent-gateway:dependencies --no-daemon || true
COPY services/agent-gateway/src/main/ services/agent-gateway/src/main/
RUN --mount=type=secret,id=github_token \
    --mount=type=secret,id=github_actor \
    set -eu; \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)"; \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)"; \
    export GITHUB_TOKEN GITHUB_ACTOR; \
    gradle :services:agent-gateway:bootJar --no-daemon

FROM debian:bookworm-slim
ARG GIT_SHA=unknown
ENV SERVICE_VERSION=${GIT_SHA}
ENV DEBIAN_FRONTEND=noninteractive

# Base tools. `gh` from the official apt source; Docker CLI / Compose
# from Docker's apt source; node 22 LTS via the nodesource one-liner;
# Temurin JDK 21 from the Adoptium apt source; python from Debian.
#
# Java must be 21, not Debian bookworm's `default-jdk` — that resolves
# to openjdk-17, and the gateway jar is compiled to Java 21 bytecode
# (class file 65.0), so a 17 runtime dies at boot with
# `UnsupportedClassVersionError ... only ... up to 61.0` and every
# runner Pod crash-loops. openjdk-21 is in neither bookworm main nor
# backports, so the Adoptium Temurin build is the source.
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl gnupg jq tini \
      bash zsh tmux git openssh-client \
      build-essential \
      python3 python3-pip python3-venv \
      ripgrep fd-find bat less \
      bubblewrap \
    && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
       | gpg --dearmor -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
       > /etc/apt/sources.list.d/github-cli.list \
    && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
       | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" \
       > /etc/apt/sources.list.d/adoptium.list \
    && curl -fsSL https://download.docker.com/linux/debian/gpg \
       | gpg --dearmor -o /usr/share/keyrings/docker.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker.gpg] https://download.docker.com/linux/debian bookworm stable" \
       > /etc/apt/sources.list.d/docker.list \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
      gh nodejs temurin-21-jdk \
      docker-ce-cli docker-buildx-plugin docker-compose-plugin \
    && docker --version >/dev/null \
    && docker compose version >/dev/null \
    && rm -rf /var/lib/apt/lists/*

# Claude Code + Codex CLIs. Both ship via npm; pin to the versions
# the auth flow was tested against and bump deliberately. The CLIs
# read OAuth tokens from $HOME/.claude/.credentials.json and
# $CODEX_HOME/auth.json — both mounted as PVCs by every Pod that
# uses this image.
RUN npm install -g \
      @anthropic-ai/claude-code \
      @openai/codex \
    && npm cache clean --force

# Playwright MCP (browser automation) + a matching chromium. The MCP
# server (bin: playwright-mcp) is installed globally so the stdio launch
# needs no runtime npx fetch. The chromium revision is tied to the
# Playwright version, so `playwright` is pinned to the exact version
# @playwright/mcp depends on — bump the two in lockstep. Browsers + their
# system libs live in a shared, world-readable path so the non-root
# agent user can launch chromium headless.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN npm install -g @playwright/mcp@0.0.75 playwright@1.61.0-alpha-1778188671000 \
    && playwright install --with-deps chromium \
    && chmod -R a+rX /ms-playwright \
    && npm cache clean --force

# GitHub MCP server (stdio), launched via gh-mcp-wrapper which mints a
# fresh App installation token at spawn (the App token rotates hourly
# and a static MCP header cannot follow it).
ARG GITHUB_MCP_VERSION=1.1.2
RUN arch="$(dpkg --print-architecture)" \
    && case "$arch" in \
         amd64) gmcp_arch=x86_64 ;; \
         arm64) gmcp_arch=arm64 ;; \
         *) echo "unsupported arch: $arch" >&2; exit 1 ;; \
       esac \
    && curl -fsSL "https://github.com/github/github-mcp-server/releases/download/v${GITHUB_MCP_VERSION}/github-mcp-server_Linux_${gmcp_arch}.tar.gz" \
       | tar -xz -C /usr/local/bin github-mcp-server \
    && chmod +x /usr/local/bin/github-mcp-server

# Go toolchain — installed as root from the official binary tarball so
# the GOROOT is world-readable and the agent user can install tooling
# under GOPATH without privilege.
# Fetch checksums from https://go.dev/dl/ before bumping.
ARG GO_VERSION=1.24.3
RUN arch="$(dpkg --print-architecture)" \
    && case "$arch" in \
         amd64) go_arch=amd64 ;; \
         arm64) go_arch=arm64 ;; \
         *) echo "unsupported arch: $arch" >&2; exit 1 ;; \
       esac \
    && curl -fsSL "https://dl.google.com/go/go${GO_VERSION}.linux-${go_arch}.tar.gz" \
       -o /tmp/go.tar.gz \
    && tar -C /usr/local -xzf /tmp/go.tar.gz \
    && rm /tmp/go.tar.gz
ENV GOROOT=/usr/local/go
ENV GOPATH=/home/agent/go
ENV PATH=/usr/local/go/bin:${PATH}

# uv — fast Python package/toolchain manager. Installed from GitHub
# releases as a static binary so it works without pip or a venv.
# Fetch checksums from https://github.com/astral-sh/uv/releases before bumping.
ARG UV_VERSION=0.7.8
RUN arch="$(dpkg --print-architecture)" \
    && case "$arch" in \
         amd64) uv_arch=x86_64-unknown-linux-gnu ;; \
         arm64) uv_arch=aarch64-unknown-linux-gnu ;; \
         *) echo "unsupported arch: $arch" >&2; exit 1 ;; \
       esac \
    && curl -fsSL "https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-${uv_arch}.tar.gz" \
       -o /tmp/uv.tar.gz \
    && tar -C /usr/local/bin -xzf /tmp/uv.tar.gz --strip-components=1 \
         "uv-${uv_arch}/uv" "uv-${uv_arch}/uvx" \
    && rm /tmp/uv.tar.gz \
    && chmod +x /usr/local/bin/uv /usr/local/bin/uvx

# Serena MCP — LSP-backed semantic code intelligence. Installed as a
# uv-managed tool for the agent user later in the file so the `serena`
# command is on the runner PATH without using uvx/network at MCP startup.
ARG SERENA_AGENT_VERSION=1.5.3

# mise — polyglot runtime manager (Node, Python, Go, Ruby …).
# Installed as the root-owned system binary so the agent user can
# activate toolchains without a separate install step.
# Fetch checksums from https://github.com/jdx/mise/releases before bumping.
ARG MISE_VERSION=2025.5.16
RUN arch="$(dpkg --print-architecture)" \
    && case "$arch" in \
         amd64) mise_arch=x64 ;; \
         arm64) mise_arch=arm64 ;; \
         *) echo "unsupported arch: $arch" >&2; exit 1 ;; \
       esac \
    && curl -fsSL "https://github.com/jdx/mise/releases/download/v${MISE_VERSION}/mise-v${MISE_VERSION}-linux-${mise_arch}" \
       -o /usr/local/bin/mise \
    && chmod +x /usr/local/bin/mise

# ast-grep (sg) — structural search/rewrite for Kotlin, TypeScript,
# Python etc. Installed globally so skills and hooks can call `sg`
# without a workspace-level install. The npm prefix is /usr, so the
# package's `sg` bin collides with Debian's /usr/bin/sg (newgrp) and the
# global install aborts with EEXIST; drop the shadow-utils `sg` first so
# `sg` resolves to ast-grep, which is the binary this image wants.
ARG AST_GREP_VERSION=0.38.4
RUN rm -f /usr/bin/sg \
    && npm install -g "@ast-grep/cli@${AST_GREP_VERSION}" \
    && npm cache clean --force

# The gateway jar runs on the Temurin 21 JDK installed above; the
# entrypoint launches it with ZGC the same way every other service does.
COPY --from=gateway-build /app/services/agent-gateway/build/libs/*.jar /opt/agent-gateway.jar
COPY .specify /opt/agent-kit/sdd
COPY services/agent-runner/entrypoint.sh /opt/entrypoint.sh
RUN chmod +x /opt/entrypoint.sh
RUN set -eu; \
    tmp="$(mktemp -d)"; \
    mkdir -p "${tmp}/.claude" "${tmp}/.codex"; \
    output="$(HOME="${tmp}" CODEX_HOME="${tmp}/.codex" CLAUDE_CONFIG_DIR="${tmp}/.claude" AGENT_RUNNER_ENTRYPOINT_SELF_TEST=agent-kit-manifest /opt/entrypoint.sh)"; \
    printf '%s\n' "${output}" | grep -q "manifest missing for Claude"; \
    printf '%s\n' "${output}" | grep -q "manifest missing for Codex"; \
    printf 'version=old\n' > "${tmp}/.claude/.knowledge-system-version"; \
    printf 'version=old\n' > "${tmp}/.codex/.knowledge-system-version"; \
    output="$(HOME="${tmp}" CODEX_HOME="${tmp}/.codex" CLAUDE_CONFIG_DIR="${tmp}/.claude" AGENT_KIT_EXPECTED_VERSION=current AGENT_RUNNER_ENTRYPOINT_SELF_TEST=agent-kit-manifest /opt/entrypoint.sh)"; \
    printf '%s\n' "${output}" | grep -q "is old, expected current"; \
    printf 'version=current\n' > "${tmp}/.claude/.knowledge-system-version"; \
    printf 'version=current\n' > "${tmp}/.codex/.knowledge-system-version"; \
    output="$(HOME="${tmp}" CODEX_HOME="${tmp}/.codex" CLAUDE_CONFIG_DIR="${tmp}/.claude" AGENT_KIT_EXPECTED_VERSION=current AGENT_RUNNER_ENTRYPOINT_SELF_TEST=agent-kit-manifest /opt/entrypoint.sh)"; \
    test -z "${output}"; \
    allow="$(REPO_URL='git@github.com:o/primary.git' REPO_URLS='https://github.com/o/extra.git#dev git@github.com:o/second.git' AGENT_RUNNER_ENTRYPOINT_SELF_TEST=repo-allow /opt/entrypoint.sh)"; \
    test "${allow}" = 'o/primary o/extra o/second'; \
    test -z "$(REPO_URL='' REPO_URLS='' AGENT_RUNNER_ENTRYPOINT_SELF_TEST=repo-allow /opt/entrypoint.sh)"; \
    test "$(REPO_URL='git@github.com:o/agents.git' AGENT_RUNNER_ENTRYPOINT_SELF_TEST=repo-dir /opt/entrypoint.sh)" = 'agents'; \
    test "$(REPO_URL='https://github.com/o/website.git' AGENT_RUNNER_ENTRYPOINT_SELF_TEST=repo-dir /opt/entrypoint.sh)" = 'website'; \
    mkdir -p "${tmp}/workspace/agents/.git"; \
    WORKSPACE_ROOT="${tmp}/workspace" AGENT_KIT_SDD_SOURCE=/opt/agent-kit/sdd AGENT_RUNNER_ENTRYPOINT_SELF_TEST=speckit-seed /opt/entrypoint.sh; \
    test -f "${tmp}/workspace/agents/.specify/templates/spec-template.md"; \
    test -x "${tmp}/workspace/agents/.specify/scripts/bash/create-new-feature.sh"; \
    test -f "${tmp}/workspace/agents/.specify/memory/constitution.md"; \
    test -f "${tmp}/workspace/agents/.specify/.agent-kit-sdd-seed.sha256"; \
    rm -rf "${tmp}"

# Shared token helper used by gh, GitHub MCP, and Git's credential
# helper. Centralising cache/minting logic keeps push + PR creation on
# the same repo-scoped token.
COPY services/agent-runner/agent-github-token.sh /usr/local/bin/agent-github-token
RUN chmod +x /usr/local/bin/agent-github-token

# `gh` wrapper that mints a fresh App installation token before each
# real gh call. Installed ahead of the apt gh (/usr/bin/gh) on PATH so
# `gh pr create` / `gh run rerun` carry a short-lived, repo-scoped
# token; the wrapper is a no-op passthrough when the App is not wired.
COPY services/agent-runner/gh-app-token-wrapper.sh /usr/local/bin/gh
RUN chmod +x /usr/local/bin/gh

# Git credential helper for HTTPS pushes. The entrypoint rewrites
# github.com SSH remotes to HTTPS after boot-time clone, so `git push`
# uses the same installation token instead of requiring an SSH key.
COPY services/agent-runner/git-credential-agent-gh-app.sh /usr/local/bin/git-credential-agent-gh-app
RUN chmod +x /usr/local/bin/git-credential-agent-gh-app

# Wrapper that launches the GitHub MCP server with a freshly-minted App
# token (see gh-mcp-wrapper.sh). Referenced as the `github` MCP server's
# command in the agents-mcp-servers ConfigMap.
COPY services/agent-runner/gh-mcp-wrapper.sh /usr/local/bin/gh-mcp-wrapper
RUN chmod +x /usr/local/bin/gh-mcp-wrapper

# Non-root agent user, with /workspace writable. /home/agent is the
# anchor for ~/.claude, ~/.codex, ~/.gitconfig, ~/.ssh — all mounted
# from PVCs/Secrets at runtime. The runtime Pod also injects the host
# Docker socket's supplemental group; the image-level docker group keeps
# local/manual runs usable when the host happens to use the common GID.
ARG DOCKER_GROUP_ID=999
RUN if getent group docker >/dev/null; then :; \
      elif getent group "${DOCKER_GROUP_ID}" >/dev/null; then groupadd docker; \
      else groupadd --gid "${DOCKER_GROUP_ID}" docker; fi \
    && useradd -m -u 1000 -G docker -s /bin/bash agent \
    && mkdir -p /workspace /home/agent/.claude /home/agent/.codex /home/agent/.ssh \
    && chown -R agent:agent /workspace /home/agent
USER agent
WORKDIR /workspace
ENV HOME=/home/agent
ENV CODEX_HOME=/home/agent/.codex
# /home/agent/go/bin — user-installed Go binaries
# /home/agent/.local/bin — uv-managed tools + pip installs
# GOROOT/GOPATH are set above at the root layer; repeat for agent context.
ENV GOPATH=/home/agent/go
ENV PATH=/home/agent/.npm-global/bin:/home/agent/go/bin:/home/agent/.local/bin:${PATH}
RUN uv tool install -p 3.13 "serena-agent==${SERENA_AGENT_VERSION}" \
    && serena --help >/dev/null

EXPOSE 8090
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/entrypoint.sh"]
