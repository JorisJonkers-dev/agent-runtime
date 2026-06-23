# The credential-login worker: drives the Claude Code and Codex CLIs over a PTY
# to capture OAuth credentials into Vault. Both CLIs are baked into the image.
# The browser-facing half of the portal lives in agents-ui / agents-api.

# ---- build stage ----------------------------------------------------------
FROM node:22-bookworm-slim AS build
WORKDIR /app

# node-pty is a native addon; it needs a toolchain + python at install time.
RUN apt-get update \
  && apt-get install -y --no-install-recommends python3 make g++ \
  && rm -rf /var/lib/apt/lists/*

# Build context is the repo root (the publish workflow runs buildx with `.`),
# so context-relative COPY paths carry the services/agents-login/ prefix.
COPY services/agents-login/package.json services/agents-login/package-lock.json ./
RUN npm ci

COPY services/agents-login/tsconfig.json ./
COPY services/agents-login/src ./src
RUN npm run build \
  && npm prune --omit=dev

# ---- runtime stage --------------------------------------------------------
FROM node:22-bookworm-slim AS runtime

# Pinned CLI versions. Keep these in lockstep with the agent-runner image so the
# captured credential schema always matches what the runners consume.
ARG CLAUDE_CODE_VERSION=2.1.186
ARG CODEX_VERSION=0.141.0
RUN npm install -g \
      @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION} \
      @openai/codex@${CODEX_VERSION} \
  && npm cache clean --force

ENV NODE_ENV=production \
    HOME=/home/agent \
    CODEX_HOME=/home/agent/.codex

# node:22 already ships a UID 1000 user (node), so `useradd --uid 1000` exits 4
# on the clash. Reuse UID 1000 and just provision a writable HOME — the
# container runs as the numeric UID 1000 with HOME=/home/agent.
RUN mkdir -p /home/agent/.claude /home/agent/.codex \
  && chown -R 1000:1000 /home/agent

WORKDIR /app
COPY --from=build --chown=1000:1000 /app/node_modules ./node_modules
COPY --from=build --chown=1000:1000 /app/dist ./dist
COPY --chown=1000:1000 services/agents-login/package.json ./
COPY --chown=1000:1000 services/agents-login/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

USER 1000
EXPOSE 8081
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
