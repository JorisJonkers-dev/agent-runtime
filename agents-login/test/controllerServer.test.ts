import { describe, it, expect, beforeEach } from 'vitest'
import { buildControllerServer } from '../src/controller/server.js'
import type { WorkerClient } from '../src/controller/workerClient.js'
import { createLogger } from '../src/shared/log.js'
import type { Provider, SessionStatus } from '../src/shared/types.js'

function fakeWorker(overrides: Partial<WorkerClient> = {}): WorkerClient {
  const base: WorkerClient = {
    async start(provider: Provider, _updatedBy: string): Promise<SessionStatus> {
      return {
        id: 'sess-1',
        provider,
        phase: 'awaiting_url',
        authorizeUrl: 'https://claude.ai/oauth/authorize?code=1',
        needsRedirectUrl: provider === 'claude',
        updatedAt: '2026-06-21T00:00:00.000Z',
      }
    },
    async status(id: string) {
      return id === 'sess-1'
        ? {
            id,
            provider: 'claude' as Provider,
            phase: 'awaiting_url',
            needsRedirectUrl: true,
            updatedAt: '2026-06-21T00:00:00.000Z',
          }
        : undefined
    },
    async submitRedirect() {
      return { ok: true }
    },
    async cancel() {
      return { ok: true }
    },
  }
  return { ...base, ...overrides }
}

const USER_HEADER = 'x-user-id'
const ROLES_HEADER = 'x-user-roles'
const ID_HEADERS = { [USER_HEADER]: 'alice@example.com', [ROLES_HEADER]: 'AGENTS_LOGIN,USER' }

function build(worker = fakeWorker(), requiredPermission = '') {
  return buildControllerServer({
    worker,
    logger: createLogger(),
    userHeader: USER_HEADER,
    rolesHeader: ROLES_HEADER,
    requiredPermission,
    sessionTtlMs: 60_000,
  })
}

async function csrfToken(app: ReturnType<typeof build>): Promise<string> {
  const res = await app.inject({ method: 'GET', url: '/', headers: ID_HEADERS })
  const m = res.body.match(/const CSRF = "([a-f0-9]+)"/)
  return m![1]
}

describe('controller HTTP server', () => {
  let app: ReturnType<typeof build>
  beforeEach(() => {
    app = build()
  })

  it('serves health without identity', async () => {
    const res = await app.inject({ method: 'GET', url: '/healthz' })
    expect(res.statusCode).toBe(200)
  })

  it('401s the UI without a forward-auth identity header', async () => {
    const res = await app.inject({ method: 'GET', url: '/' })
    expect(res.statusCode).toBe(401)
  })

  it('renders the UI with a CSRF token and no-store header', async () => {
    const res = await app.inject({ method: 'GET', url: '/', headers: ID_HEADERS })
    expect(res.statusCode).toBe(200)
    expect(res.headers['cache-control']).toBe('no-store')
    expect(res.body).toContain('const CSRF = "')
    expect(res.body).toContain('alice@example.com')
  })

  it('403s when the required permission is missing from roles', async () => {
    const gated = build(fakeWorker(), 'AGENTS_ADMIN')
    const res = await gated.inject({ method: 'GET', url: '/', headers: ID_HEADERS })
    expect(res.statusCode).toBe(403)
  })

  it('allows when the required permission is present', async () => {
    const gated = build(fakeWorker(), 'AGENTS_LOGIN')
    const res = await gated.inject({ method: 'GET', url: '/', headers: ID_HEADERS })
    expect(res.statusCode).toBe(200)
  })

  it('rejects a mutating request without a CSRF token', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/login',
      headers: ID_HEADERS,
      payload: { provider: 'claude' },
    })
    expect(res.statusCode).toBe(403)
    expect(res.json().error).toMatch(/csrf/i)
  })

  it('rejects a mutating request with a wrong CSRF token', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/login',
      headers: { ...ID_HEADERS, 'x-csrf-token': 'deadbeef' },
      payload: { provider: 'claude' },
    })
    expect(res.statusCode).toBe(403)
  })

  it('starts a login with a valid CSRF token', async () => {
    const token = await csrfToken(app)
    const res = await app.inject({
      method: 'POST',
      url: '/api/login',
      headers: { ...ID_HEADERS, 'x-csrf-token': token },
      payload: { provider: 'claude' },
    })
    expect(res.statusCode).toBe(201)
    expect(res.json().id).toBe('sess-1')
  })

  it('400s a login with an invalid provider', async () => {
    const token = await csrfToken(app)
    const res = await app.inject({
      method: 'POST',
      url: '/api/login',
      headers: { ...ID_HEADERS, 'x-csrf-token': token },
      payload: { provider: 'bad' },
    })
    expect(res.statusCode).toBe(400)
  })

  it('502s when the worker errors', async () => {
    const errApp = build(
      fakeWorker({
        async start() {
          throw new Error('worker down')
        },
      }),
    )
    const token = await csrfToken(errApp)
    const res = await errApp.inject({
      method: 'POST',
      url: '/api/login',
      headers: { ...ID_HEADERS, 'x-csrf-token': token },
      payload: { provider: 'claude' },
    })
    expect(res.statusCode).toBe(502)
  })

  it('proxies status (GET needs no CSRF)', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/status?id=sess-1', headers: ID_HEADERS })
    expect(res.statusCode).toBe(200)
    expect(res.json().phase).toBe('awaiting_url')
  })

  it('404s status for an unknown session', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/status?id=nope', headers: ID_HEADERS })
    expect(res.statusCode).toBe(404)
  })

  it('400s status without an id', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/status', headers: ID_HEADERS })
    expect(res.statusCode).toBe(400)
  })

  it('submits a redirect URL with CSRF', async () => {
    const token = await csrfToken(app)
    const res = await app.inject({
      method: 'POST',
      url: '/api/redirect',
      headers: { ...ID_HEADERS, 'x-csrf-token': token },
      payload: { id: 'sess-1', url: 'https://claude.ai/redirect' },
    })
    expect(res.statusCode).toBe(200)
  })

  it('cancels with CSRF', async () => {
    const token = await csrfToken(app)
    const res = await app.inject({
      method: 'POST',
      url: '/api/cancel',
      headers: { ...ID_HEADERS, 'x-csrf-token': token },
      payload: { id: 'sess-1' },
    })
    expect(res.statusCode).toBe(200)
    expect(res.json().ok).toBe(true)
  })
})
