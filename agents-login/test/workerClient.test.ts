import { describe, it, expect } from 'vitest'
import { HttpWorkerClient } from '../src/controller/workerClient.js'
import type { SessionStatus } from '../src/shared/types.js'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status })
}

describe('HttpWorkerClient', () => {
  const status: SessionStatus = {
    id: 'sess-1',
    provider: 'claude',
    phase: 'awaiting_url',
    needsRedirectUrl: true,
    updatedAt: '2026-06-21T00:00:00.000Z',
  }

  it('sends the internal token header and parses start', async () => {
    let seenHeader: string | undefined
    const fetchImpl = (async (_u: string, init?: RequestInit) => {
      seenHeader = (init?.headers as Record<string, string>)['x-internal-token']
      return jsonResponse(201, status)
    }) as unknown as typeof fetch
    const client = new HttpWorkerClient('http://worker', 'tok', fetchImpl)
    const s = await client.start('claude', 'alice')
    expect(seenHeader).toBe('tok')
    expect(s.id).toBe('sess-1')
  })

  it('throws the worker error message on a non-ok start', async () => {
    const fetchImpl = (async () => jsonResponse(409, { error: 'already in progress' })) as unknown as typeof fetch
    const client = new HttpWorkerClient('http://worker', 'tok', fetchImpl)
    await expect(client.start('claude', 'alice')).rejects.toThrow(/already in progress/)
  })

  it('returns undefined status on 404', async () => {
    const fetchImpl = (async () => jsonResponse(404, {})) as unknown as typeof fetch
    const client = new HttpWorkerClient('http://worker', 'tok', fetchImpl)
    expect(await client.status('x')).toBeUndefined()
  })

  it('parses a present status', async () => {
    const fetchImpl = (async () => jsonResponse(200, status)) as unknown as typeof fetch
    const client = new HttpWorkerClient('http://worker', 'tok', fetchImpl)
    expect((await client.status('sess-1'))?.phase).toBe('awaiting_url')
  })

  it('relays redirect ok/err', async () => {
    const okImpl = (async () => jsonResponse(200, { ok: true })) as unknown as typeof fetch
    expect(await new HttpWorkerClient('http://w', 't', okImpl).submitRedirect('s', 'https://x')).toEqual({
      ok: true,
      error: undefined,
    })
    const errImpl = (async () => jsonResponse(400, { ok: false, error: 'bad' })) as unknown as typeof fetch
    const r = await new HttpWorkerClient('http://w', 't', errImpl).submitRedirect('s', 'x')
    expect(r.ok).toBe(false)
    expect(r.error).toBe('bad')
  })

  it('relays cancel', async () => {
    const fetchImpl = (async () => jsonResponse(200, { ok: true })) as unknown as typeof fetch
    expect(await new HttpWorkerClient('http://w', 't', fetchImpl).cancel('s')).toEqual({ ok: true })
  })
})
