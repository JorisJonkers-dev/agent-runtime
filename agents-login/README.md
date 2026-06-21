# agents-login

Centralized, browser-driven credential-login portal for the agent runners.

Agent runner pods authenticate the Claude Code CLI and the Codex CLI via OAuth
(no API keys). Re-authentication previously required `kubectl exec` into a pod
and copy/pasting a long OAuth URL out of a terminal that cannot copy text. This
service replaces that: an operator opens the portal in a browser, clicks
through the OAuth flow, and the captured credential files are written to Vault
for fan-out to every runner.

## Two modes, one image

The same image runs in one of two modes, selected by `AGENTS_LOGIN_MODE`
(or the first CLI argument):

- **controller** — public-facing, behind the edge forward-auth/SSO. A thin UI
  and proxy. Holds no Vault token and stores no credential files. Reads the
  operator identity from forward-auth headers, enforces CSRF on every mutating
  endpoint, sets `Cache-Control: no-store` on every response, and talks to the
  worker over HTTP with a shared `INTERNAL_TOKEN`. Status is delivered by short
  HTTP polling (no WebSocket — the OAuth flow idles for well over 100 s and an
  upstream proxy kills idle WebSockets).
- **worker** — private, single instance. Owns a PTY, spawns `claude /login`
  and `codex login --device`, parses the authorize URL / device code out of the
  PTY output, accepts the Claude post-approval redirect URL on stdin, captures
  the credential files, and writes them to Vault under a Kubernetes Lease.

## Flow

### Claude (`claude /login`)

1. Worker spawns the CLI; the authorize URL is parsed from the PTY output.
2. Controller shows the URL in a copyable element. Operator opens it, approves.
3. Operator pastes the post-approval redirect URL back into the UI; the
   controller forwards it to the worker, which writes it to the child's stdin.
4. On the CLI success line, the worker captures the credentials and writes Vault.

### Codex (`codex login --device`)

1. Worker spawns the CLI; the verification URL and device code are parsed out.
2. Controller shows both. Operator enters the code in the browser (no paste-back).
3. On the CLI success line, the worker captures the credentials and writes Vault.

## Vault paths and fields

Written with KV v2 Compare-And-Set against `metadata.version`. The KV mount is
configurable (`VAULT_KV_MOUNT`, default `secret`, matching the rest of the
agents stack). All values are UTF-8 text.

| Path (relative to the KV mount) | Fields                                                                            |
| ------------------------------- | --------------------------------------------------------------------------------- |
| `agents/claude-oauth`           | `.credentials.json`, `.claude.json`, `schema_version`, `updated_at`, `updated_by` |
| `agents/codex-oauth`            | `auth.json`, `config.toml`, `schema_version`, `updated_at`, `updated_by`          |

Captured from the live HOME:

- `$HOME/.claude/.credentials.json`
- `$HOME/.claude.json` — a **sibling** of `.claude/`, at `$HOME/.claude.json`,
  **not** inside the `.claude/` directory.
- `$CODEX_HOME/auth.json` (`CODEX_HOME` defaults to `$HOME/.codex`)
- `$CODEX_HOME/config.toml`

The write critical section is guarded by a named Kubernetes Lease. On a CAS
conflict the worker re-reads and retries with bounded backoff; on a persistent
conflict it fails loudly (surfacing an error to the controller) rather than
blind-overwriting.

## Environment variables

### Shared

| Var                  | Default                          | Meaning                                                       |
| -------------------- | -------------------------------- | ------------------------------------------------------------- |
| `AGENTS_LOGIN_MODE`  | `controller` (image)             | `controller` or `worker`. Also accepted as the first CLI arg. |
| `PORT`               | controller `8080`, worker `8081` | Listen port.                                                  |
| `HOST`               | `0.0.0.0`                        | Listen address.                                               |
| `INTERNAL_TOKEN`     | _(required)_                     | Shared token for controller→worker HTTP.                      |
| `SESSION_TTL_MS`     | `900000`                         | Login-session timeout / CSRF-token TTL.                       |
| `AGENTS_LOGIN_DEBUG` | unset                            | `1` enables debug log lines.                                  |

### Controller

| Var                         | Default                           | Meaning                                                                                                                                                              |
| --------------------------- | --------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `WORKER_URL`                | `http://agents-login-worker:8081` | Worker base URL.                                                                                                                                                     |
| `FORWARD_AUTH_USER_HEADER`  | `x-user-id`                       | Header carrying the authenticated operator. Matches the repo forward-auth middleware (`authResponseHeaders: X-User-Id`).                                             |
| `FORWARD_AUTH_ROLES_HEADER` | `x-user-roles`                    | Header carrying comma-separated roles.                                                                                                                               |
| `REQUIRED_PERMISSION`       | _(empty)_                         | Role/permission the operator must hold. Empty = any authenticated identity passes the controller's own gate (the edge forward-auth remains the primary enforcement). |

### Worker

| Var                     | Default                                               | Meaning                                 |
| ----------------------- | ----------------------------------------------------- | --------------------------------------- |
| `HOME`                  | OS home                                               | Base for Claude credential capture.     |
| `CODEX_HOME`            | `$HOME/.codex`                                        | Base for Codex credential capture.      |
| `VAULT_ADDR`            | `http://vault.data-system.svc.cluster.local:8200`     | Vault address.                          |
| `VAULT_K8S_ROLE`        | `agents-login`                                        | Vault Kubernetes-auth role.             |
| `VAULT_K8S_MOUNT`       | `kubernetes`                                          | Vault Kubernetes-auth mount.            |
| `VAULT_KV_MOUNT`        | `secret`                                              | KV v2 mount.                            |
| `VAULT_CLAUDE_PATH`     | `agents/claude-oauth`                                 | KV path for Claude.                     |
| `VAULT_CODEX_PATH`      | `agents/codex-oauth`                                  | KV path for Codex.                      |
| `VAULT_SA_TOKEN_PATH`   | `/var/run/secrets/kubernetes.io/serviceaccount/token` | SA JWT path for Vault + Lease auth.     |
| `VAULT_CAS_MAX_RETRIES` | `5`                                                   | CAS retry budget before failing loudly. |
| `LEASE_NAME`            | `agents-login-write`                                  | Coordination Lease name.                |
| `LEASE_NAMESPACE`       | `agents-system`                                       | Coordination Lease namespace.           |

## Security posture

- Controller holds no Vault token and no credential files.
- CSRF token (double-submit, per-operator, short TTL) on every mutating route.
- `Cache-Control: no-store` on every response.
- Logs and HTTP responses are deep-redacted of token/credential-shaped material.
- Worker rejects any request lacking the `INTERNAL_TOKEN`.

## Develop and test

```bash
npm install          # generates the standalone lockfile
npm run lint         # eslint
npm run typecheck    # tsc --noEmit (strict)
npm test             # vitest + v8 coverage (>= 80% lines)
npm run build        # tsc -> dist/
```

Tests use **fake CLI binaries** (shell scripts created in a temp dir and put on
`PATH`) and a fake in-process Vault HTTP server, so the suite runs with no
network and no real CLIs. The PTY logic is covered both by a deterministic fake
PTY (state machine, parsing, cancellation, timeout, CAS retry/conflict) and,
where the host supports it, an end-to-end pass over a real pseudo-terminal
driving the fake CLI scripts.

## Container

`Dockerfile` builds a single Node 22 image that installs pinned versions of
`@anthropic-ai/claude-code` and `@openai/codex` globally (keep them in lockstep
with the agent-runner image), runs as a non-root user (UID 1000) with
`HOME=/home/agent` and `CODEX_HOME=/home/agent/.codex`, and selects the mode
from `AGENTS_LOGIN_MODE`.

> This service is intentionally **not** a pnpm-workspace member: it is fully
> standalone with its own `package.json`, lockfile, and `Dockerfile`.
