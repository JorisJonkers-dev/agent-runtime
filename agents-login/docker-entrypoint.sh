#!/usr/bin/env sh
set -eu

# Single-purpose image: the credential-login worker. The browser-facing half of
# the portal lives in agents-ui / agents-api, which proxy to this worker.
exec node /app/dist/index.js "$@"
