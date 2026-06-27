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
          'AGENTS_API_INTERNAL_URL',
          'AGENTS_API_INTERNAL_BEARER',
          'PORT',
          'HOST',
          'SESSION_TTL_MS',
          'CODEX_HOME',
          'LEASE_NAME',
          'SA_TOKEN_PATH',
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
    process.env.AGENTS_API_INTERNAL_BEARER = 'bearer'
    process.env.HOME = '/home/agent'
    const cfg = loadWorkerConfig()
    expect(cfg.codexHome).toBe('/home/agent/.codex')
    expect(cfg.agentsApiInternalUrl).toBe('http://agents-api.agents-system.svc.cluster.local:8082')
    expect(cfg.agentsApiInternalBearer).toBe('bearer')
    expect(cfg.leaseName).toBe('agents-login-write')
    expect(cfg.leaseNamespace).toBe('agents-system')
  })

  it('honours an explicit CODEX_HOME and agents-api URL override', () => {
    process.env.INTERNAL_TOKEN = 'tok'
    process.env.AGENTS_API_INTERNAL_BEARER = 'bearer'
    process.env.HOME = '/home/agent'
    process.env.CODEX_HOME = '/custom/codex'
    process.env.AGENTS_API_INTERNAL_URL = 'http://agents-api.local:8082'
    const cfg = loadWorkerConfig()
    expect(cfg.codexHome).toBe('/custom/codex')
    expect(cfg.agentsApiInternalUrl).toBe('http://agents-api.local:8082')
  })

  it('throws when INTERNAL_TOKEN is missing', () => {
    process.env.AGENTS_API_INTERNAL_BEARER = 'bearer'
    process.env.HOME = '/home/agent'
    expect(() => loadWorkerConfig()).toThrow(/INTERNAL_TOKEN/)
  })

  it('throws when AGENTS_API_INTERNAL_BEARER is missing', () => {
    process.env.INTERNAL_TOKEN = 'tok'
    process.env.HOME = '/home/agent'
    expect(() => loadWorkerConfig()).toThrow(/AGENTS_API_INTERNAL_BEARER/)
  })

  it('throws on a non-integer port', () => {
    process.env.INTERNAL_TOKEN = 'tok'
    process.env.AGENTS_API_INTERNAL_BEARER = 'bearer'
    process.env.HOME = '/home/agent'
    process.env.PORT = 'abc'
    expect(() => loadWorkerConfig()).toThrow(/integer/)
  })
})
