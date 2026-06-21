// Named-lock abstraction backing the Vault write critical section. The real
// implementation acquires/renews a coordination.k8s.io Lease via the Kubernetes
// API; tests inject a fake.

export interface LeaseLock {
  /** Run `fn` while holding the lock; release on completion or error. */
  withLock<T>(fn: () => Promise<T>): Promise<T>
}

export interface K8sLeaseOptions {
  name: string
  namespace: string
  holderIdentity: string
  // Lease validity window; renewed for long critical sections.
  leaseDurationSeconds?: number
  acquireTimeoutMs?: number
  saTokenPath?: string
  apiServer?: string
  caCertPath?: string
}

interface LeaseSpec {
  holderIdentity?: string
  leaseDurationSeconds?: number
  acquireTime?: string
  renewTime?: string
}

interface LeaseObject {
  metadata: { name: string; namespace: string; resourceVersion?: string }
  spec?: LeaseSpec
}

const DEFAULT_CA = '/var/run/secrets/kubernetes.io/serviceaccount/ca.crt'

/**
 * Minimal Kubernetes Lease lock using the API server's REST endpoint and the
 * pod's service-account token. Acquires by creating or taking over an expired
 * Lease (optimistic-concurrency via resourceVersion), then releases by clearing
 * the holder. Deliberately small — no external client library.
 */
export class K8sLeaseLock implements LeaseLock {
  constructor(
    private readonly opts: K8sLeaseOptions,
    private readonly fetchImpl: typeof fetch = fetch,
    private readonly readToken: (p: string) => Promise<string> = defaultReadToken,
  ) {}

  private async base(): Promise<{ url: string; headers: Record<string, string> }> {
    const host = this.opts.apiServer ?? defaultApiServer()
    const token = await this.readToken(this.opts.saTokenPath ?? '/var/run/secrets/kubernetes.io/serviceaccount/token')
    const ns = this.opts.namespace
    return {
      url: `${host}/apis/coordination.k8s.io/v1/namespaces/${ns}/leases`,
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': 'application/json',
        accept: 'application/json',
      },
    }
  }

  private async get(): Promise<LeaseObject | null> {
    const { url, headers } = await this.base()
    const res = await this.fetchImpl(`${url}/${this.opts.name}`, { headers })
    if (res.status === 404) {
      return null
    }
    if (!res.ok) {
      throw new Error(`lease GET failed: ${res.status} ${await safeText(res)}`)
    }
    return (await res.json()) as LeaseObject
  }

  private nowIso(): string {
    return new Date().toISOString()
  }

  private isExpired(lease: LeaseObject): boolean {
    const spec = lease.spec
    if (!spec || !spec.holderIdentity) {
      return true
    }
    const renew = spec.renewTime ? Date.parse(spec.renewTime) : 0
    const dur = (spec.leaseDurationSeconds ?? this.opts.leaseDurationSeconds ?? 30) * 1000
    return Date.now() - renew > dur
  }

  private async create(): Promise<void> {
    const { url, headers } = await this.base()
    const body: LeaseObject = {
      metadata: { name: this.opts.name, namespace: this.opts.namespace },
      spec: {
        holderIdentity: this.opts.holderIdentity,
        leaseDurationSeconds: this.opts.leaseDurationSeconds ?? 30,
        acquireTime: this.nowIso(),
        renewTime: this.nowIso(),
      },
    }
    const res = await this.fetchImpl(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
    })
    if (res.status === 409) {
      throw new ConflictError('lease already created by another holder')
    }
    if (!res.ok) {
      throw new Error(`lease CREATE failed: ${res.status} ${await safeText(res)}`)
    }
  }

  private async takeOver(existing: LeaseObject): Promise<void> {
    const { url, headers } = await this.base()
    const body: LeaseObject = {
      metadata: {
        name: this.opts.name,
        namespace: this.opts.namespace,
        resourceVersion: existing.metadata.resourceVersion,
      },
      spec: {
        holderIdentity: this.opts.holderIdentity,
        leaseDurationSeconds: this.opts.leaseDurationSeconds ?? 30,
        acquireTime: this.nowIso(),
        renewTime: this.nowIso(),
      },
    }
    const res = await this.fetchImpl(`${url}/${this.opts.name}`, {
      method: 'PUT',
      headers,
      body: JSON.stringify(body),
    })
    if (res.status === 409) {
      throw new ConflictError('lease taken over by another holder')
    }
    if (!res.ok) {
      throw new Error(`lease PUT failed: ${res.status} ${await safeText(res)}`)
    }
  }

  private async release(): Promise<void> {
    const existing = await this.get()
    if (!existing || existing.spec?.holderIdentity !== this.opts.holderIdentity) {
      return
    }
    const { url, headers } = await this.base()
    const body: LeaseObject = {
      metadata: {
        name: this.opts.name,
        namespace: this.opts.namespace,
        resourceVersion: existing.metadata.resourceVersion,
      },
      spec: { leaseDurationSeconds: existing.spec?.leaseDurationSeconds, holderIdentity: undefined },
    }
    await this.fetchImpl(`${url}/${this.opts.name}`, {
      method: 'PUT',
      headers,
      body: JSON.stringify(body),
    }).catch(() => undefined)
  }

  private async tryAcquire(): Promise<boolean> {
    const existing = await this.get()
    try {
      if (!existing) {
        await this.create()
        return true
      }
      if (existing.spec?.holderIdentity === this.opts.holderIdentity || this.isExpired(existing)) {
        await this.takeOver(existing)
        return true
      }
      return false
    } catch (err) {
      if (err instanceof ConflictError) {
        return false
      }
      throw err
    }
  }

  async withLock<T>(fn: () => Promise<T>): Promise<T> {
    const deadline = Date.now() + (this.opts.acquireTimeoutMs ?? 30_000)
    let acquired = false
    while (!acquired) {
      acquired = await this.tryAcquire()
      if (acquired) {
        break
      }
      if (Date.now() >= deadline) {
        throw new Error(`timed out acquiring lease ${this.opts.name}`)
      }
      await sleep(250)
    }
    try {
      return await fn()
    } finally {
      await this.release()
    }
  }
}

/** No-op lock for single-instance local runs and tests. */
export class NoopLeaseLock implements LeaseLock {
  async withLock<T>(fn: () => Promise<T>): Promise<T> {
    return fn()
  }
}

class ConflictError extends Error {}

function defaultApiServer(): string {
  const host = process.env.KUBERNETES_SERVICE_HOST
  const port = process.env.KUBERNETES_SERVICE_PORT_HTTPS ?? process.env.KUBERNETES_SERVICE_PORT ?? '443'
  if (!host) {
    throw new Error('KUBERNETES_SERVICE_HOST not set; cannot reach the API server')
  }
  return `https://${host}:${port}`
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

export { DEFAULT_CA }
