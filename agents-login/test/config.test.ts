import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { resolveMode, loadControllerConfig, loadWorkerConfig } from '../src/shared/config.js'

describe('config', () => {
  const saved = { ...process.env }
  beforeEach(() => {
    for (const k of Object.keys(process.env)) {
      if (
        k.startsWith('AGENTS_LOGIN') ||
        [
          'INTERNAL_TOKEN',
          'WORKER_URL',
          'PORT',
          'HOST',
          'VAULT_ADDR',
          'VAULT_K8S_ROLE',
          'REQUIRED_PERMISSION',
          'FORWARD_AUTH_USER_HEADER',
          'FORWARD_AUTH_ROLES_HEADER',
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

  it('resolves mode from AGENTS_LOGIN_MODE', () => {
    process.env.AGENTS_LOGIN_MODE = 'worker'
    expect(resolveMode()).toBe('worker')
  })

  it('resolves mode from argv when env unset', () => {
    expect(resolveMode(['controller'])).toBe('controller')
  })

  it('throws when no mode is selectable', () => {
    expect(() => resolveMode([])).toThrow(/mode not selected/)
  })

  it('loads controller config with defaults and required INTERNAL_TOKEN', () => {
    process.env.INTERNAL_TOKEN = 'tok'
    const cfg = loadControllerConfig()
    expect(cfg.internalToken).toBe('tok')
    expect(cfg.userHeader).toBe('x-user-id')
    expect(cfg.rolesHeader).toBe('x-user-roles')
    expect(cfg.port).toBe(8080)
    expect(cfg.requiredPermission).toBe('')
  })

  it('honours overrides in controller config', () => {
    process.env.INTERNAL_TOKEN = 'tok'
    process.env.PORT = '9000'
    process.env.FORWARD_AUTH_USER_HEADER = 'remote-user'
    process.env.REQUIRED_PERMISSION = 'AGENTS_LOGIN'
    const cfg = loadControllerConfig()
    expect(cfg.port).toBe(9000)
    expect(cfg.userHeader).toBe('remote-user')
    expect(cfg.requiredPermission).toBe('AGENTS_LOGIN')
  })

  it('throws when INTERNAL_TOKEN is missing', () => {
    expect(() => loadControllerConfig()).toThrow(/INTERNAL_TOKEN/)
  })

  it('throws on a non-integer port', () => {
    process.env.INTERNAL_TOKEN = 'tok'
    process.env.PORT = 'abc'
    expect(() => loadControllerConfig()).toThrow(/integer/)
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
})
