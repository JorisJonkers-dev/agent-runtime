#!/usr/bin/env sh
set -eu

# Single-purpose image: the credential-login worker. The browser-facing half of
# the portal lives in agents-ui / agents-api, which proxy to this worker.

# The runtime HOME is a writable emptyDir that shadows the image's prebuilt
# /home/agent/.claude and .codex, so recreate them — codex aborts when
# CODEX_HOME is missing and claude needs its config dir.
mkdir -p "${HOME}/.claude" "${CODEX_HOME:-${HOME}/.codex}"

exec node /app/dist/index.js "$@"
