import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { loadWorkerConfig } from '../src/shared/config.js'

describe('config', () => {
  const saved = { ...process.env }
  beforeEach(() => {
    for (const k of Object.keys(process.env)) {
      if (
        k.startsWith('AGENTS_LOGIN') ||
        [
          'INTERNAL_TOKEN',
          'PORT',
          'HOST',
          'VAULT_ADDR',
          'VAULT_K8S_ROLE',
          'SESSION_TTL_MS',
          'CODEX_HOME',
          'LEASE_NAME',
          'VAULT_CAS_MAX_RETRIES',
        ].includes(k)
      ) {
        delete process.env[k]
      }
    }
  })
  afterEach(() => {
    process.env = { ...saved }
  })

  it('loads worker config with derived CODEX_HOME default', () => {
    process.env.INTERNAL_TOKEN = 'tok'
    process.env.HOME = '/home/agent'
    const cfg = loadWorkerConfig()
    expect(cfg.codexHome).toBe('/home/agent/.codex')
    expect(cfg.vaultKvMount).toBe('secret')
    expect(cfg.vaultClaudePath).toBe('agents/claude-oauth')
    expect(cfg.vaultCodexPath).toBe('agents/codex-oauth')
    expect(cfg.leaseName).toBe('agents-login-write')
    expect(cfg.leaseNamespace).toBe('agents-system')
  })

  it('honours an explicit CODEX_HOME and vault overrides', () => {
    process.env.INTERNAL_TOKEN = 'tok'
    process.env.HOME = '/home/agent'
    process.env.CODEX_HOME = '/custom/codex'
    process.env.VAULT_K8S_ROLE = 'role-x'
    process.env.VAULT_CAS_MAX_RETRIES = '9'
    const cfg = loadWorkerConfig()
    expect(cfg.codexHome).toBe('/custom/codex')
    expect(cfg.vaultK8sRole).toBe('role-x')
    expect(cfg.casMaxRetries).toBe(9)
  })

  it('throws when INTERNAL_TOKEN is missing', () => {
    process.env.HOME = '/home/agent'
    expect(() => loadWorkerConfig()).toThrow(/INTERNAL_TOKEN/)
  })

  it('throws on a non-integer port', () => {
    process.env.INTERNAL_TOKEN = 'tok'
    process.env.HOME = '/home/agent'
    process.env.PORT = 'abc'
    expect(() => loadWorkerConfig()).toThrow(/integer/)
  })
})
