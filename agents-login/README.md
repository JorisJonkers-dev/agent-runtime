# agents-login

The credential-login **worker** for the agent runners.

Agent runner pods authenticate the Claude Code CLI and the Codex CLI via OAuth
(no API keys). Re-authentication previously required `kubectl exec` into a pod
and copy/pasting a long OAuth URL out of a terminal that cannot copy text. The
browser-driven replacement is the **Credentials** page in agents-ui (served at
`agents.jorisjonkers.dev`), which calls **agents-api**, which proxies to this
worker. The worker is the only privileged piece: it drives the CLI logins over
a PTY and writes the captured credentials to Vault for fan-out to every runner.

This service is internal (ClusterIP only) and has no public surface — the UI,
forward-auth gating, and CSRF all live in agents-ui / agents-api. agents-api
authenticates to the worker with a shared `INTERNAL_TOKEN`.

## Flow

### Claude (`claude /login`)

1. Worker spawns the CLI; the authorize URL is parsed from the PTY output.
2. agents-ui shows the URL in a copyable element. The operator opens it, approves.
3. The operator pastes the post-approval redirect URL back in the page; agents-api
   forwards it to the worker, which writes it to the child's stdin.
4. On the CLI success line, the worker captures the credentials and writes Vault.

### Codex (`codex login --device`)

1. Worker spawns the CLI; the verification URL and device code are parsed out.
2. agents-ui shows both. The operator enters the code in the browser (no paste-back).
3. On the CLI success line, the worker captures the credentials and writes Vault.

## Vault paths and fields

Written with KV v2 Compare-And-Set against `metadata.version`. The KV mount is
configurable (`VAULT_KV_MOUNT`, default `secret`, matching the rest of the
agents stack). All values are UTF-8 text.

| Path (relative to the KV mount) | Fields                                                                          |
| ------------------------------- | ------------------------------------------------------------------------------- |
| `agents/claude-oauth`           | `credentials_json`, `claude_json`, `schema_version`, `updated_at`, `updated_by` |
| `agents/codex-oauth`            | `auth_json`, `config_toml`, `schema_version`, `updated_at`, `updated_by`        |

Field keys are underscored because `vault kv put` with leading-dot keys is
awkward; the VaultStaticSecret transformation templates re-emit the exact CLI
filenames into the projected Secrets. Captured from the live HOME:

- `$HOME/.claude/.credentials.json`
- `$HOME/.claude.json` — a **sibling** of `.claude/`, at `$HOME/.claude.json`,
  **not** inside the `.claude/` directory.
- `$CODEX_HOME/auth.json` (`CODEX_HOME` defaults to `$HOME/.codex`)
- `$CODEX_HOME/config.toml`

The write critical section is guarded by a named Kubernetes Lease. On a CAS
conflict the worker re-reads and retries with bounded backoff; on a persistent
conflict it fails loudly (surfacing an error to agents-api) rather than
blind-overwriting.

## Environment variables

| Var                     | Default                                               | Meaning                                 |
| ----------------------- | ----------------------------------------------------- | --------------------------------------- |
| `PORT`                  | `8081`                                                | Listen port.                            |
| `HOST`                  | `0.0.0.0`                                             | Listen address.                         |
| `INTERNAL_TOKEN`        | _(required)_                                          | Shared token agents-api presents.       |
| `SESSION_TTL_MS`        | `900000`                                              | Login-session timeout.                  |
| `HOME`                  | OS home                                               | Base for Claude credential capture.     |
| `CODEX_HOME`            | `$HOME/.codex`                                        | Base for Codex credential capture.      |
| `VAULT_ADDR`            | `http://vault.data-system.svc.cluster.local:8200`     | Vault address.                          |
| `VAULT_K8S_ROLE`        | `agents-login-worker`                                 | Vault Kubernetes-auth role.             |
| `VAULT_K8S_MOUNT`       | `kubernetes`                                          | Vault Kubernetes-auth mount.            |
| `VAULT_KV_MOUNT`        | `secret`                                              | KV v2 mount.                            |
| `VAULT_CLAUDE_PATH`     | `agents/claude-oauth`                                 | KV path for Claude.                     |
| `VAULT_CODEX_PATH`      | `agents/codex-oauth`                                  | KV path for Codex.                      |
| `VAULT_SA_TOKEN_PATH`   | `/var/run/secrets/kubernetes.io/serviceaccount/token` | SA JWT path for Vault + Lease auth.     |
| `VAULT_CAS_MAX_RETRIES` | `5`                                                   | CAS retry budget before failing loudly. |
| `LEASE_NAME`            | `agents-login-write`                                  | Coordination Lease name.                |
| `LEASE_NAMESPACE`       | `agents-system`                                       | Coordination Lease namespace.           |
| `AGENTS_LOGIN_DEBUG`    | unset                                                 | `1` enables debug log lines.            |

## Security posture

- The worker holds the only Vault-writer token in the flow; agents-api holds none.
- Logs and HTTP responses are deep-redacted of token/credential-shaped material.
- The worker rejects any request lacking the `INTERNAL_TOKEN`, and its
  NetworkPolicy admits ingress only from the agents-api pods.

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
with the agent-runner image) and runs as a non-root user (UID 1000) with
`HOME=/home/agent` and `CODEX_HOME=/home/agent/.codex`.

> This service is intentionally **not** a pnpm-workspace member: it is fully
> standalone with its own `package.json`, lockfile, and `Dockerfile`.
