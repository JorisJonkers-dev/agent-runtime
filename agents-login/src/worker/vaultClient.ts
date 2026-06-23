// Vault KV v2 client with Kubernetes auth and Compare-And-Set writes.

export interface VaultClientOptions {
  addr: string
  k8sRole: string
  k8sMount: string
  kvMount: string
  saTokenPath: string
  maxCasRetries: number
}

export class CasConflictError extends Error {
  constructor(
    readonly path: string,
    readonly attempts: number,
  ) {
    super(`persistent CAS conflict writing ${path} after ${attempts} attempts`)
    this.name = 'CasConflictError'
  }
}

interface AuthResponse {
  auth: { client_token: string; lease_duration: number }
}

interface MetadataResponse {
  data: { current_version: number }
}

interface DataResponse {
  data: { data: Record<string, string>; metadata: { version: number } }
}

/** Non-secret summary of a stored credential, for the UI's "check" panel. */
export interface CredentialStatus {
  exists: boolean
  version: number
  updatedAt?: string
  updatedBy?: string
  schemaVersion?: string
}

export class VaultClient {
  private token?: string

  constructor(
    private readonly opts: VaultClientOptions,
    private readonly fetchImpl: typeof fetch = fetch,
    private readonly readToken: (p: string) => Promise<string> = defaultReadToken,
  ) {}

  /** Authenticate via the Kubernetes auth backend; caches the client token. */
  async login(): Promise<void> {
    const jwt = await this.readToken(this.opts.saTokenPath)
    const url = `${this.opts.addr}/v1/auth/${this.opts.k8sMount}/login`
    const res = await this.fetchImpl(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ role: this.opts.k8sRole, jwt }),
    })
    if (!res.ok) {
      throw new Error(`vault k8s login failed: ${res.status} ${await safeText(res)}`)
    }
    const body = (await res.json()) as AuthResponse
    if (!body.auth?.client_token) {
      throw new Error('vault k8s login returned no client_token')
    }
    this.token = body.auth.client_token
  }

  private headers(): Record<string, string> {
    if (!this.token) {
      throw new Error('vault client not authenticated; call login() first')
    }
    return { 'x-vault-token': this.token, 'content-type': 'application/json' }
  }

  /** Current metadata version of a KV v2 secret, or 0 if it does not exist. */
  async currentVersion(path: string): Promise<number> {
    const url = `${this.opts.addr}/v1/${this.opts.kvMount}/metadata/${path}`
    const res = await this.fetchImpl(url, { headers: this.headers() })
    if (res.status === 404) {
      return 0
    }
    if (!res.ok) {
      throw new Error(`vault metadata read failed: ${res.status} ${await safeText(res)}`)
    }
    const body = (await res.json()) as MetadataResponse
    return body.data?.current_version ?? 0
  }

  /**
   * Read the non-secret bookkeeping fields (version + who/when last wrote) for
   * the UI's credential-status check. The credential blobs in `data` are never
   * returned. Reports `exists: false` when the path has no live secret.
   */
  async readStatus(path: string): Promise<CredentialStatus> {
    const url = `${this.opts.addr}/v1/${this.opts.kvMount}/data/${path}`
    const res = await this.fetchImpl(url, { headers: this.headers() })
    if (res.status === 404) {
      return { exists: false, version: 0 }
    }
    if (!res.ok) {
      throw new Error(`vault data read failed: ${res.status} ${await safeText(res)}`)
    }
    const body = (await res.json()) as DataResponse
    const data = body.data?.data ?? {}
    return {
      exists: true,
      version: body.data?.metadata?.version ?? 0,
      updatedAt: data.updated_at,
      updatedBy: data.updated_by,
      schemaVersion: data.schema_version,
    }
  }

  private async putOnce(path: string, data: Record<string, string>, casVersion: number): Promise<boolean> {
    const url = `${this.opts.addr}/v1/${this.opts.kvMount}/data/${path}`
    const res = await this.fetchImpl(url, {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify({ options: { cas: casVersion }, data }),
    })
    // Vault returns 400 with check-and-set messaging on a CAS mismatch.
    if (res.status === 400) {
      const text = await safeText(res)
      if (/check-and-set|cas/i.test(text)) {
        return false
      }
      throw new Error(`vault write failed: 400 ${text}`)
    }
    if (!res.ok) {
      throw new Error(`vault write failed: ${res.status} ${await safeText(res)}`)
    }
    return true
  }

  /**
   * Write a KV v2 secret using Compare-And-Set against the current metadata
   * version. On CAS conflict, re-read and retry with bounded backoff; on
   * persistent conflict, throw CasConflictError rather than blind-overwrite.
   */
  async writeCas(path: string, data: Record<string, string>): Promise<void> {
    const max = Math.max(1, this.opts.maxCasRetries)
    for (let attempt = 1; attempt <= max; attempt += 1) {
      const version = await this.currentVersion(path)
      const ok = await this.putOnce(path, data, version)
      if (ok) {
        return
      }
      await sleep(Math.min(2000, 50 * 2 ** (attempt - 1)))
    }
    throw new CasConflictError(path, max)
  }
}

async function defaultReadToken(path: string): Promise<string> {
  const { readFile } = await import('node:fs/promises')
  return (await readFile(path, 'utf8')).trim()
}

async function safeText(res: Response): Promise<string> {
  try {
    return await res.text()
  } catch {
    return ''
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}
