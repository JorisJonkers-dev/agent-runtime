import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createLogger } from '../src/shared/log.js'

describe('logger', () => {
  let stdout: string[]
  let stderr: string[]

  beforeEach(() => {
    stdout = []
    stderr = []
    vi.spyOn(process.stdout, 'write').mockImplementation((c: string | Uint8Array) => {
      stdout.push(c.toString())
      return true
    })
    vi.spyOn(process.stderr, 'write').mockImplementation((c: string | Uint8Array) => {
      stderr.push(c.toString())
      return true
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    delete process.env.AGENTS_LOGIN_DEBUG
  })

  it('writes info to stdout as redacted JSON', () => {
    createLogger().info('hello', { accessToken: 'sk-shouldnotappear1234567890' })
    const line = JSON.parse(stdout[0])
    expect(line.level).toBe('info')
    expect(line.msg).toBe('hello')
    expect(line.accessToken).toBe('«redacted»')
  })

  it('writes warn to stdout and error to stderr', () => {
    const log = createLogger()
    log.warn('warned')
    log.error('boom')
    expect(JSON.parse(stdout[0]).level).toBe('warn')
    expect(JSON.parse(stderr[0]).level).toBe('error')
  })

  it('suppresses debug unless AGENTS_LOGIN_DEBUG=1', () => {
    createLogger().debug('quiet')
    expect(stdout).toHaveLength(0)
    process.env.AGENTS_LOGIN_DEBUG = '1'
    createLogger().debug('loud')
    expect(JSON.parse(stdout[0]).level).toBe('debug')
  })

  it('handles no-fields calls', () => {
    createLogger().info('bare')
    expect(JSON.parse(stdout[0]).msg).toBe('bare')
  })
})
