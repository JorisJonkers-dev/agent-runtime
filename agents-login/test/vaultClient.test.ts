import { describe, it, expect, afterEach } from 'vitest'
import { VaultClient, CasConflictError } from '../src/worker/vaultClient.js'
import { FakeVault } from './helpers/fakeVault.js'

function client(vault: FakeVault, maxCasRetries = 5): VaultClient {
  return new VaultClient(
    {
      addr: vault.url,
      k8sRole: 'agents-login',
      k8sMount: 'kubernetes',
      kvMount: 'secret',
      saTokenPath: '/unused',
      maxCasRetries,
    },
    fetch,
    async () => 'fake-sa-jwt',
  )
}

describe('VaultClient', () => {
  let vault: FakeVault
  afterEach(async () => {
    await vault.stop()
  })

  it('authenticates via the kubernetes auth backend', async () => {
    vault = new FakeVault()
    await vault.start()
    const c = client(vault)
    await c.login()
    expect(vault.logins).toHaveLength(1)
    expect(vault.logins[0]).toEqual({ role: 'agents-login', jwt: 'fake-sa-jwt' })
  })

  it('throws when k8s login is rejected', async () => {
    vault = new FakeVault({ loginStatus: 403 })
    await vault.start()
    await expect(client(vault).login()).rejects.toThrow(/login failed/)
  })

  it('writes a fresh secret with CAS version 0', async () => {
    vault = new FakeVault()
    await vault.start()
    const c = client(vault)
    await c.login()
    await c.writeCas('agents/claude-oauth', { '.claude.json': '{}', schema_version: '1' })
    expect(vault.store.get('agents/claude-oauth')?.version).toBe(1)
  })

  it('retries on a transient CAS conflict then succeeds', async () => {
    vault = new FakeVault({ conflictCount: 2 })
    await vault.start()
    const c = client(vault)
    await c.login()
    await c.writeCas('agents/codex-oauth', { 'auth.json': '{}' })
    expect(vault.store.get('agents/codex-oauth')?.version).toBe(1)
  })

  it('fails loudly with CasConflictError on persistent conflict', async () => {
    vault = new FakeVault({ conflictCount: 99 })
    await vault.start()
    const c = client(vault, 3)
    await c.login()
    await expect(c.writeCas('agents/claude-oauth', { a: 'b' })).rejects.toBeInstanceOf(CasConflictError)
  })

  it('reports current version 0 for a missing secret', async () => {
    vault = new FakeVault()
    await vault.start()
    const c = client(vault)
    await c.login()
    expect(await c.currentVersion('agents/missing')).toBe(0)
  })

  it('refuses to write before login', async () => {
    vault = new FakeVault()
    await vault.start()
    const c = client(vault)
    await expect(c.writeCas('x', { a: 'b' })).rejects.toThrow(/not authenticated/)
  })

  it('throws on a non-CAS 400 write error', async () => {
    vault = new FakeVault()
    await vault.start()
    const badFetch = (async (input: string | URL | Request) => {
      const url = input.toString()
      if (url.endsWith('/login')) {
        return new Response(JSON.stringify({ auth: { client_token: 't', lease_duration: 1 } }), {
          status: 200,
        })
      }
      if (url.includes('/metadata/')) {
        return new Response('{}', { status: 404 })
      }
      return new Response('{"errors":["malformed request"]}', { status: 400 })
    }) as unknown as typeof fetch
    const c = new VaultClient(
      { addr: vault.url, k8sRole: 'r', k8sMount: 'kubernetes', kvMount: 'secret', saTokenPath: '/x', maxCasRetries: 2 },
      badFetch,
      async () => 'jwt',
    )
    await c.login()
    await expect(c.writeCas('p', { a: 'b' })).rejects.toThrow(/400/)
  })

  it('throws when metadata read fails non-404', async () => {
    vault = new FakeVault()
    await vault.start()
    const badFetch = (async (input: string | URL | Request) => {
      const url = input.toString()
      if (url.endsWith('/login')) {
        return new Response(JSON.stringify({ auth: { client_token: 't', lease_duration: 1 } }), {
          status: 200,
        })
      }
      return new Response('boom', { status: 500 })
    }) as unknown as typeof fetch
    const c = new VaultClient(
      { addr: vault.url, k8sRole: 'r', k8sMount: 'kubernetes', kvMount: 'secret', saTokenPath: '/x', maxCasRetries: 2 },
      badFetch,
      async () => 'jwt',
    )
    await c.login()
    await expect(c.currentVersion('p')).rejects.toThrow(/metadata read failed/)
  })

  it('reads non-secret status fields and never returns the secret blob', async () => {
    vault = new FakeVault()
    await vault.start()
    const stubFetch = (async (input: string | URL | Request) => {
      const url = input.toString()
      if (url.endsWith('/login')) {
        return new Response(JSON.stringify({ auth: { client_token: 't', lease_duration: 1 } }), { status: 200 })
      }
      if (url.includes('/data/agents/claude-oauth')) {
        return new Response(
          JSON.stringify({
            data: {
              data: {
                credentials_json: 'TOP-SECRET-BLOB',
                updated_at: '2026-06-23T10:00:00Z',
                updated_by: 'ExtraToast',
                schema_version: '1',
              },
              metadata: { version: 4 },
            },
          }),
          { status: 200 },
        )
      }
      return new Response('{}', { status: 404 })
    }) as unknown as typeof fetch
    const c = new VaultClient(
      { addr: vault.url, k8sRole: 'r', k8sMount: 'kubernetes', kvMount: 'secret', saTokenPath: '/x', maxCasRetries: 2 },
      stubFetch,
      async () => 'jwt',
    )
    await c.login()
    const st = await c.readStatus('agents/claude-oauth')
    expect(st).toEqual({
      exists: true,
      version: 4,
      updatedAt: '2026-06-23T10:00:00Z',
      updatedBy: 'ExtraToast',
      schemaVersion: '1',
    })
    expect(JSON.stringify(st)).not.toContain('TOP-SECRET-BLOB')
    expect(await c.readStatus('agents/missing')).toEqual({ exists: false, version: 0 })
  })

  it('throws when login returns no client_token', async () => {
    vault = new FakeVault()
    await vault.start()
    const badFetch = (async () =>
      new Response(JSON.stringify({ auth: {} }), { status: 200 })) as unknown as typeof fetch
    const c = new VaultClient(
      { addr: vault.url, k8sRole: 'r', k8sMount: 'kubernetes', kvMount: 'secret', saTokenPath: '/x', maxCasRetries: 2 },
      badFetch,
      async () => 'jwt',
    )
    await expect(c.login()).rejects.toThrow(/no client_token/)
  })
})
