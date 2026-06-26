import type { Provider } from '../shared/types.js'
import type { Logger } from '../shared/log.js'
import { redactString } from '../shared/redact.js'
import { extractClaudeOauthToken, type CredentialBundle } from './credentials.js'

export type AgentsApiProvider = 'CLAUDE' | 'CODEX'

export interface AgentsApiClientOptions {
  baseUrl: string
  bearer: string
  logger?: Logger
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
    this.logger = opts.logger
  }

  private readonly bearer: string
  private readonly logger?: Logger

  async postCredentials(req: CredentialIngestRequest): Promise<void> {
    this.logger?.info('agents-api credential ingest POST starting', {
      url: this.endpoint,
      userId: req.userId,
      provider: req.provider,
      payloadKeys: Object.keys(req.payload),
    })
    const res = await this.fetchImpl(this.endpoint, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${this.bearer}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify(req),
    })
    this.logger?.info('agents-api credential ingest POST completed', {
      url: this.endpoint,
      status: res.status,
      ok: res.ok,
      provider: req.provider,
    })
    if (!res.ok) {
      throw new Error(`agents-api credential ingest failed: ${res.status} ${redactString(await safeText(res))}`)
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
    const credentialsJson = bundle.data.credentials_json
    if (!credentialsJson) {
      throw new Error('no Claude credentials_json captured')
    }
    const accountJson = bundle.data.account_json
    const oauthToken = bundle.data.oauth_token ?? extractClaudeOauthToken(credentialsJson)
    return {
      credentials_json: credentialsJson,
      ...(accountJson ? { account_json: accountJson } : {}),
      ...(oauthToken ? { oauth_token: oauthToken } : {}),
    }
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
