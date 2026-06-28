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
  agentsApiInternalUrl: string
  agentsApiInternalBearer: string
  saTokenPath: string
  leaseName: string
  leaseNamespace: string
  sessionTtlMs: number
}

export function loadWorkerConfig(): WorkerConfig {
  const home = optEnv('HOME', homedir())
  return {
    port: intEnv('PORT', 8081),
    host: optEnv('HOST', '0.0.0.0'),
    internalToken: env('INTERNAL_TOKEN'),
    home,
    codexHome: optEnv('CODEX_HOME', join(home, '.codex')),
    agentsApiInternalUrl: optEnv('AGENTS_API_INTERNAL_URL', 'http://agents-api.agents-system.svc.cluster.local:8082'),
    agentsApiInternalBearer: env('AGENTS_API_INTERNAL_BEARER'),
    saTokenPath: optEnv('SA_TOKEN_PATH', '/var/run/secrets/kubernetes.io/serviceaccount/token'),
    leaseName: optEnv('LEASE_NAME', 'agents-login-write'),
    leaseNamespace: optEnv('LEASE_NAMESPACE', 'agents-system'),
    sessionTtlMs: intEnv('SESSION_TTL_MS', 15 * 60 * 1000),
  }
}
