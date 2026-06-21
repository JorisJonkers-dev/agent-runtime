import { homedir } from 'node:os'
import { join } from 'node:path'

export type Mode = 'controller' | 'worker'

function env(name: string, fallback?: string): string {
  const v = process.env[name]
  if (v === undefined || v === '') {
    if (fallback !== undefined) {
      return fallback
    }
    throw new Error(`required env ${name} is not set`)
  }
  return v
}

function optEnv(name: string, fallback: string): string {
  const v = process.env[name]
  return v === undefined || v === '' ? fallback : v
}

function intEnv(name: string, fallback: number): number {
  const v = process.env[name]
  if (v === undefined || v === '') {
    return fallback
  }
  const n = Number.parseInt(v, 10)
  if (Number.isNaN(n)) {
    throw new Error(`env ${name} must be an integer, got ${v}`)
  }
  return n
}

export function resolveMode(argv: string[] = process.argv.slice(2)): Mode {
  const fromArg = argv.find((a) => a === 'controller' || a === 'worker')
  const raw = (process.env.AGENTS_LOGIN_MODE ?? fromArg ?? '').toLowerCase()
  if (raw === 'controller' || raw === 'worker') {
    return raw
  }
  throw new Error('mode not selected: set AGENTS_LOGIN_MODE=controller|worker or pass it as an argument')
}

export interface ControllerConfig {
  port: number
  host: string
  workerUrl: string
  internalToken: string
  // forward-auth identity headers. Defaults match the repo's forward-auth
  // middleware (authResponseHeaders: X-User-Id, X-User-Roles).
  userHeader: string
  rolesHeader: string
  // Comma-separated role/permission required to use the portal. Empty = any
  // authenticated user passes the controller's own gate (the edge forward-auth
  // is still the primary enforcement point).
  requiredPermission: string
  sessionTtlMs: number
}

export function loadControllerConfig(): ControllerConfig {
  return {
    port: intEnv('PORT', 8080),
    host: optEnv('HOST', '0.0.0.0'),
    workerUrl: env('WORKER_URL', 'http://agents-login-worker:8081'),
    internalToken: env('INTERNAL_TOKEN'),
    userHeader: optEnv('FORWARD_AUTH_USER_HEADER', 'x-user-id'),
    rolesHeader: optEnv('FORWARD_AUTH_ROLES_HEADER', 'x-user-roles'),
    requiredPermission: optEnv('REQUIRED_PERMISSION', ''),
    sessionTtlMs: intEnv('SESSION_TTL_MS', 15 * 60 * 1000),
  }
}

export interface WorkerConfig {
  port: number
  host: string
  internalToken: string
  home: string
  codexHome: string
  vaultAddr: string
  vaultK8sRole: string
  vaultK8sMount: string
  vaultKvMount: string
  vaultClaudePath: string
  vaultCodexPath: string
  saTokenPath: string
  leaseName: string
  leaseNamespace: string
  sessionTtlMs: number
  casMaxRetries: number
}

export function loadWorkerConfig(): WorkerConfig {
  const home = optEnv('HOME', homedir())
  return {
    port: intEnv('PORT', 8081),
    host: optEnv('HOST', '0.0.0.0'),
    internalToken: env('INTERNAL_TOKEN'),
    home,
    codexHome: optEnv('CODEX_HOME', join(home, '.codex')),
    vaultAddr: optEnv('VAULT_ADDR', 'http://vault.data-system.svc.cluster.local:8200'),
    vaultK8sRole: env('VAULT_K8S_ROLE', 'agents-login-worker'),
    vaultK8sMount: optEnv('VAULT_K8S_MOUNT', 'kubernetes'),
    vaultKvMount: optEnv('VAULT_KV_MOUNT', 'secret'),
    vaultClaudePath: optEnv('VAULT_CLAUDE_PATH', 'agents/claude-oauth'),
    vaultCodexPath: optEnv('VAULT_CODEX_PATH', 'agents/codex-oauth'),
    saTokenPath: optEnv('VAULT_SA_TOKEN_PATH', '/var/run/secrets/kubernetes.io/serviceaccount/token'),
    leaseName: optEnv('LEASE_NAME', 'agents-login-write'),
    leaseNamespace: optEnv('LEASE_NAMESPACE', 'agents-system'),
    sessionTtlMs: intEnv('SESSION_TTL_MS', 15 * 60 * 1000),
    casMaxRetries: intEnv('VAULT_CAS_MAX_RETRIES', 5),
  }
}
