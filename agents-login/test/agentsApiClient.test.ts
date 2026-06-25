import { describe, expect, it } from 'vitest'
import { AgentsApiClient, payloadForApi, providerForApi } from '../src/worker/agentsApiClient.js'
import type { Logger } from '../src/shared/log.js'

function testLogger(lines: Array<{ msg: string; fields?: Record<string, unknown> }>): Logger {
  return {
    info: (msg, fields) => lines.push({ msg, fields }),
    warn: (msg, fields) => lines.push({ msg, fields }),
    error: (msg, fields) => lines.push({ msg, fields }),
    debug: (msg, fields) => lines.push({ msg, fields }),
  }
}

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

  it('logs the ingest POST URL and response status without payload values', async () => {
    const lines: Array<{ msg: string; fields?: Record<string, unknown> }> = []
    const client = new AgentsApiClient(
      { baseUrl: 'http://agents-api.local:8082', bearer: 'secret-bearer', logger: testLogger(lines) },
      async () => new Response(null, { status: 204 }),
    )

    await client.postCredentials({
      userId: 'alice',
      provider: 'CLAUDE',
      payload: { oauth_token: 'sk-ant-oat01-ShouldNotAppear1234567890' },
    })

    expect(lines).toEqual([
      {
        msg: 'agents-api credential ingest POST starting',
        fields: {
          url: 'http://agents-api.local:8082/api/v1/internal/credentials',
          userId: 'alice',
          provider: 'CLAUDE',
          payloadKeys: ['oauth_token'],
        },
      },
      {
        msg: 'agents-api credential ingest POST completed',
        fields: {
          url: 'http://agents-api.local:8082/api/v1/internal/credentials',
          status: 204,
          ok: true,
          provider: 'CLAUDE',
        },
      },
    ])
    expect(JSON.stringify(lines)).not.toContain('ShouldNotAppear')
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
