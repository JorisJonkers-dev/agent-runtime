import { describe, it, expect, beforeEach } from 'vitest'
import { buildWorkerServer } from '../src/worker/server.js'
import { SessionManager } from '../src/worker/session.js'
import { createLogger } from '../src/shared/log.js'
import { fakeSpawner } from './helpers/fakePty.js'

function manager() {
  const { spawner } = fakeSpawner(() => [
    { type: 'emit', data: 'Visit https://claude.ai/oauth/authorize?code=1\r\n' },
    { type: 'expectStdin', match: () => true, then: [] },
  ])
  // vault/lease are never reached in these route tests (no success emitted).
  return new SessionManager({
    spawner,
    vault: {} as never,
    lease: {} as never,
    paths: { home: '/tmp', codexHome: '/tmp/.codex' },
    vaultPaths: { claude: 'agents/claude-oauth', codex: 'agents/codex-oauth' },
    logger: createLogger(),
    ttlMs: 60_000,
  })
}

const TOKEN = 'internal-secret'

describe('worker HTTP server', () => {
  let app: ReturnType<typeof buildWorkerServer>

  beforeEach(() => {
    app = buildWorkerServer({ sessions: manager(), internalToken: TOKEN, logger: createLogger() })
  })

  it('serves health without auth', async () => {
    const res = await app.inject({ method: 'GET', url: '/healthz' })
    expect(res.statusCode).toBe(200)
    expect(res.headers['cache-control']).toBe('no-store')
  })

  it('rejects requests without the internal token', async () => {
    const res = await app.inject({ method: 'POST', url: '/sessions', payload: { provider: 'claude' } })
    expect(res.statusCode).toBe(401)
  })

  it('starts a session with the internal token', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/sessions',
      headers: { 'x-internal-token': TOKEN },
      payload: { provider: 'claude', updatedBy: 'alice' },
    })
    expect(res.statusCode).toBe(201)
    expect(res.json().provider).toBe('claude')
  })

  it('400s on an invalid provider', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/sessions',
      headers: { 'x-internal-token': TOKEN },
      payload: { provider: 'bogus' },
    })
    expect(res.statusCode).toBe(400)
  })

  it('404s status for an unknown session', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/sessions/nope',
      headers: { 'x-internal-token': TOKEN },
    })
    expect(res.statusCode).toBe(404)
  })

  it('409s when a session is already in progress', async () => {
    const headers = { 'x-internal-token': TOKEN }
    await app.inject({ method: 'POST', url: '/sessions', headers, payload: { provider: 'claude' } })
    await new Promise((r) => setTimeout(r, 20))
    const res = await app.inject({ method: 'POST', url: '/sessions', headers, payload: { provider: 'codex' } })
    expect(res.statusCode).toBe(409)
  })

  it('400s a redirect submit without a url', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/sessions/x/redirect',
      headers: { 'x-internal-token': TOKEN },
      payload: {},
    })
    expect(res.statusCode).toBe(400)
  })

  it('cancels via the cancel route', async () => {
    const headers = { 'x-internal-token': TOKEN }
    const start = await app.inject({ method: 'POST', url: '/sessions', headers, payload: { provider: 'claude' } })
    const id = start.json().id
    await new Promise((r) => setTimeout(r, 20))
    const res = await app.inject({ method: 'POST', url: `/sessions/${id}/cancel`, headers, payload: {} })
    expect(res.statusCode).toBe(200)
    expect(res.json().ok).toBe(true)
  })

  it('returns the authorize URL with its long state/code_challenge intact', async () => {
    // The generic token redactor masks 40+ char base64url runs; applied to the
    // status it used to scrub the OAuth `state` + PKCE `code_challenge` out of
    // the authorize URL, leaving the operator a literal «redacted» they cannot
    // open. The URL fields must pass through verbatim.
    const url =
      'https://claude.ai/oauth/authorize?response_type=code&code_challenge=' +
      'a'.repeat(43) +
      '&code_challenge_method=S256&state=' +
      'b'.repeat(43)
    const { spawner } = fakeSpawner(() => [
      { type: 'emit', data: `Visit ${url}\r\n` },
      { type: 'expectStdin', match: () => true, then: [] },
    ])
    const sessions = new SessionManager({
      spawner,
      vault: {} as never,
      lease: {} as never,
      paths: { home: '/tmp', codexHome: '/tmp/.codex' },
      vaultPaths: { claude: 'agents/claude-oauth', codex: 'agents/codex-oauth' },
      logger: createLogger(),
      ttlMs: 60_000,
    })
    const local = buildWorkerServer({ sessions, internalToken: TOKEN, logger: createLogger() })
    const headers = { 'x-internal-token': TOKEN }
    const start = await local.inject({ method: 'POST', url: '/sessions', headers, payload: { provider: 'claude' } })
    const id = start.json().id
    await new Promise((r) => setTimeout(r, 20))
    const res = await local.inject({ method: 'GET', url: `/sessions/${id}`, headers })
    expect(res.statusCode).toBe(200)
    expect(res.json().authorizeUrl).toBe(url)
    expect(res.json().authorizeUrl).not.toContain('redacted')
  })

  it('cancels a body-less POST (the shape agents-api sends)', async () => {
    // agents-api's RestClient issues cancel with no body and a content-type
    // Fastify has no parser for; that used to 415 and brick the UI Cancel.
    const headers = { 'x-internal-token': TOKEN }
    const start = await app.inject({ method: 'POST', url: '/sessions', headers, payload: { provider: 'claude' } })
    const id = start.json().id
    await new Promise((r) => setTimeout(r, 20))
    const res = await app.inject({
      method: 'POST',
      url: `/sessions/${id}/cancel`,
      headers: { ...headers, 'content-type': 'application/x-www-form-urlencoded' },
    })
    expect(res.statusCode).toBe(200)
    expect(res.json().ok).toBe(true)
  })
})
