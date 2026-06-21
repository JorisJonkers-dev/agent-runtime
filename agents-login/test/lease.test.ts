import { describe, it, expect } from 'vitest'
import { K8sLeaseLock, NoopLeaseLock } from '../src/worker/lease.js'

type Lease = {
  metadata: { name: string; namespace: string; resourceVersion?: string }
  spec?: { holderIdentity?: string; renewTime?: string; leaseDurationSeconds?: number }
}

/** In-memory fake of the coordination.k8s.io REST endpoint. */
function fakeApi(initial?: Lease) {
  let lease: Lease | undefined = initial
  let rv = 1
  const fetchImpl = (async (_input: string | URL | Request, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const ok = (status: number, body: unknown) => new Response(JSON.stringify(body), { status })

    if (method === 'GET') {
      return lease ? ok(200, lease) : ok(404, {})
    }
    if (method === 'POST') {
      if (lease) {
        return ok(409, {})
      }
      const body = JSON.parse(init?.body as string) as Lease
      rv += 1
      lease = { ...body, metadata: { ...body.metadata, resourceVersion: String(rv) } }
      return ok(201, lease)
    }
    // PUT — takeover or release
    const body = JSON.parse(init?.body as string) as Lease
    rv += 1
    lease = { ...body, metadata: { ...body.metadata, resourceVersion: String(rv) } }
    return ok(200, lease)
  }) as unknown as typeof fetch
  return { fetchImpl, get: () => lease }
}

function lock(api: ReturnType<typeof fakeApi>, holder = 'me') {
  return new K8sLeaseLock(
    {
      name: 'agents-login-write',
      namespace: 'agents-system',
      holderIdentity: holder,
      apiServer: 'https://api.test',
      acquireTimeoutMs: 1000,
    },
    api.fetchImpl,
    async () => 'sa-token',
  )
}

describe('K8sLeaseLock', () => {
  it('creates the lease when none exists and releases after', async () => {
    const api = fakeApi()
    const result = await lock(api).withLock(async () => 'done')
    expect(result).toBe('done')
    // released: holder cleared
    expect(api.get()?.spec?.holderIdentity).toBeUndefined()
  })

  it('takes over an expired lease held by someone else', async () => {
    const api = fakeApi({
      metadata: { name: 'agents-login-write', namespace: 'agents-system', resourceVersion: '1' },
      spec: {
        holderIdentity: 'other',
        renewTime: new Date(Date.now() - 60_000).toISOString(),
        leaseDurationSeconds: 30,
      },
    })
    await expect(lock(api).withLock(async () => 'ok')).resolves.toBe('ok')
  })

  it('runs the critical section exactly once', async () => {
    const api = fakeApi()
    let calls = 0
    await lock(api).withLock(async () => {
      calls += 1
    })
    expect(calls).toBe(1)
  })

  it('NoopLeaseLock just runs the function', async () => {
    await expect(new NoopLeaseLock().withLock(async () => 7)).resolves.toBe(7)
  })

  it('renews/takes over a lease it already holds', async () => {
    const api = fakeApi({
      metadata: { name: 'agents-login-write', namespace: 'agents-system', resourceVersion: '1' },
      spec: { holderIdentity: 'me', renewTime: new Date().toISOString(), leaseDurationSeconds: 30 },
    })
    await expect(lock(api, 'me').withLock(async () => 'ok')).resolves.toBe('ok')
  })

  it('times out when another holder keeps a fresh lease', async () => {
    const api = fakeApi({
      metadata: { name: 'agents-login-write', namespace: 'agents-system', resourceVersion: '1' },
      spec: { holderIdentity: 'other', renewTime: new Date().toISOString(), leaseDurationSeconds: 30 },
    })
    const l = new K8sLeaseLock(
      {
        name: 'agents-login-write',
        namespace: 'agents-system',
        holderIdentity: 'me',
        apiServer: 'https://api.test',
        acquireTimeoutMs: 100,
      },
      api.fetchImpl,
      async () => 'sa-token',
    )
    await expect(l.withLock(async () => 'never')).rejects.toThrow(/timed out acquiring lease/)
  })

  it('propagates a non-conflict GET failure', async () => {
    const fetchImpl = (async () => new Response('boom', { status: 500 })) as unknown as typeof fetch
    const l = new K8sLeaseLock(
      { name: 'x', namespace: 'n', holderIdentity: 'me', apiServer: 'https://api.test' },
      fetchImpl,
      async () => 't',
    )
    await expect(l.withLock(async () => 1)).rejects.toThrow(/lease GET failed/)
  })

  it('retries after a 409 create race then acquires on takeover', async () => {
    // First GET: none. POST: 409 (raced). Next GET: expired lease → PUT 200.
    let calls = 0
    const fetchImpl = (async (_i: string | URL | Request, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      calls += 1
      if (method === 'GET') {
        return calls <= 1
          ? new Response('{}', { status: 404 })
          : new Response(
              JSON.stringify({
                metadata: { name: 'x', namespace: 'n', resourceVersion: '2' },
                spec: { holderIdentity: 'ghost', renewTime: new Date(0).toISOString(), leaseDurationSeconds: 30 },
              }),
              { status: 200 },
            )
      }
      if (method === 'POST') {
        return new Response('{}', { status: 409 })
      }
      return new Response('{}', { status: 200 })
    }) as unknown as typeof fetch
    const l = new K8sLeaseLock(
      { name: 'x', namespace: 'n', holderIdentity: 'me', apiServer: 'https://api.test', acquireTimeoutMs: 2000 },
      fetchImpl,
      async () => 't',
    )
    await expect(l.withLock(async () => 'acquired')).resolves.toBe('acquired')
  })

  it('derives the API server from KUBERNETES_SERVICE_HOST when not given', async () => {
    process.env.KUBERNETES_SERVICE_HOST = '10.0.0.1'
    process.env.KUBERNETES_SERVICE_PORT_HTTPS = '6443'
    let seenUrl = ''
    const fetchImpl = (async (input: string | URL | Request) => {
      seenUrl = input.toString()
      return new Response('{}', { status: 404 })
    }) as unknown as typeof fetch
    const l = new K8sLeaseLock(
      { name: 'x', namespace: 'n', holderIdentity: 'me', acquireTimeoutMs: 50 },
      fetchImpl,
      async () => 't',
    )
    await l.withLock(async () => 0).catch(() => undefined)
    expect(seenUrl).toContain('https://10.0.0.1:6443')
    delete process.env.KUBERNETES_SERVICE_HOST
    delete process.env.KUBERNETES_SERVICE_PORT_HTTPS
  })
})
