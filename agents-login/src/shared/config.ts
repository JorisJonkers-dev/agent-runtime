import { homedir } from 'node:os'
import { join } from 'node:path'

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
