import { describe, expect, it } from 'vitest'
import { AgentsApiClient, payloadForApi, providerForApi } from '../src/worker/agentsApiClient.js'

describe('AgentsApiClient', () => {
  it('posts credentials to the internal ingest endpoint', async () => {
    const calls: Array<{ url: string; init: RequestInit }> = []
    const client = new AgentsApiClient(
      { baseUrl: 'http://agents-api.local:8082/base', bearer: 'secret-bearer' },
      async (url, init) => {
        calls.push({ url: String(url), init: init ?? {} })
        return new Response(null, { status: 204 })
      },
    )

    await client.postCredentials({ userId: 'alice', provider: 'CLAUDE', payload: { oauth_token: 'tok' } })

    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('http://agents-api.local:8082/api/v1/internal/credentials')
    expect(calls[0].init.method).toBe('POST')
    expect(calls[0].init.headers).toEqual({
      authorization: 'Bearer secret-bearer',
      'content-type': 'application/json',
    })
    expect(JSON.parse(String(calls[0].init.body))).toEqual({
      userId: 'alice',
      provider: 'CLAUDE',
      payload: { oauth_token: 'tok' },
    })
  })

  it('fails loudly on non-2xx ingest responses', async () => {
    const client = new AgentsApiClient(
      { baseUrl: 'http://agents-api.local:8082', bearer: 'secret-bearer' },
      async () => new Response('nope', { status: 503 }),
    )

    await expect(
      client.postCredentials({ userId: 'alice', provider: 'CLAUDE', payload: { oauth_token: 'tok' } }),
    ).rejects.toThrow(/503 nope/)
  })

  it('maps provider names to the uppercase API enum', () => {
    expect(providerForApi('claude')).toBe('CLAUDE')
    expect(providerForApi('codex')).toBe('CODEX')
  })

  it('extracts only the required provider payload fields', () => {
    expect(
      payloadForApi('claude', {
        data: { oauth_token: 'tok', credentials_json: '{}', updated_by: 'alice' },
      }),
    ).toEqual({ oauth_token: 'tok' })

    expect(
      payloadForApi('codex', {
        data: { auth_json: '{}', config_toml: 'model="x"', updated_by: 'bob' },
      }),
    ).toEqual({ auth_json: '{}', config_toml: 'model="x"' })
  })

  it('rejects incomplete provider payloads before posting', () => {
    expect(() => payloadForApi('claude', { data: { credentials_json: '{}' } })).toThrow(/Claude OAuth token/)
    expect(() => payloadForApi('codex', { data: { auth_json: '{}' } })).toThrow(/Codex credential/)
  })

  it('reports only unknown local stored status', () => {
    const client = new AgentsApiClient({ baseUrl: 'http://agents-api.local:8082', bearer: 'secret-bearer' })
    expect(client.storedStatus()).toEqual({ status: 'unknown' })
  })
})
