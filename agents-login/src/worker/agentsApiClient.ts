import type { Provider } from '../shared/types.js'
import type { CredentialBundle } from './credentials.js'

export type AgentsApiProvider = 'CLAUDE' | 'CODEX'

export interface AgentsApiClientOptions {
  baseUrl: string
  bearer: string
}

export interface CredentialIngestRequest {
  userId: string
  provider: AgentsApiProvider
  payload: Record<string, string>
}

export interface StoredCredentialStatus {
  status: 'unknown'
}

export class AgentsApiClient {
  private readonly endpoint: string

  constructor(
    opts: AgentsApiClientOptions,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {
    this.endpoint = new URL('/api/v1/internal/credentials', opts.baseUrl).toString()
    this.bearer = opts.bearer
  }

  private readonly bearer: string

  async postCredentials(req: CredentialIngestRequest): Promise<void> {
    const res = await this.fetchImpl(this.endpoint, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${this.bearer}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify(req),
    })
    if (!res.ok) {
      throw new Error(`agents-api credential ingest failed: ${res.status} ${await safeText(res)}`)
    }
  }

  storedStatus(): StoredCredentialStatus {
    return { status: 'unknown' }
  }
}

export function providerForApi(provider: Provider): AgentsApiProvider {
  return provider === 'claude' ? 'CLAUDE' : 'CODEX'
}

export function payloadForApi(provider: Provider, bundle: CredentialBundle): Record<string, string> {
  if (provider === 'claude') {
    const oauthToken = bundle.data.oauth_token
    if (!oauthToken) {
      throw new Error('no Claude OAuth token captured')
    }
    return { oauth_token: oauthToken }
  }

  const authJson = bundle.data.auth_json
  const configToml = bundle.data.config_toml
  if (!authJson || !configToml) {
    throw new Error('no Codex credential captured')
  }
  return { auth_json: authJson, config_toml: configToml }
}

async function safeText(res: Response): Promise<string> {
  try {
    return await res.text()
  } catch {
    return ''
  }
}
