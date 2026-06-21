import type { Provider, SessionStatus } from '../shared/types.js'

export interface WorkerClient {
  start(provider: Provider, updatedBy: string): Promise<SessionStatus>
  status(id: string): Promise<SessionStatus | undefined>
  submitRedirect(id: string, url: string): Promise<{ ok: boolean; error?: string }>
  cancel(id: string): Promise<{ ok: boolean }>
}

export class HttpWorkerClient implements WorkerClient {
  constructor(
    private readonly baseUrl: string,
    private readonly internalToken: string,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  private headers(): Record<string, string> {
    return { 'content-type': 'application/json', 'x-internal-token': this.internalToken }
  }

  async start(provider: Provider, updatedBy: string): Promise<SessionStatus> {
    const res = await this.fetchImpl(`${this.baseUrl}/sessions`, {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify({ provider, updatedBy }),
    })
    if (!res.ok) {
      const body = (await res.json().catch(() => ({}))) as { error?: string }
      throw new Error(body.error ?? `worker start failed: ${res.status}`)
    }
    return (await res.json()) as SessionStatus
  }

  async status(id: string): Promise<SessionStatus | undefined> {
    const res = await this.fetchImpl(`${this.baseUrl}/sessions/${encodeURIComponent(id)}`, {
      headers: this.headers(),
    })
    if (res.status === 404) {
      return undefined
    }
    if (!res.ok) {
      throw new Error(`worker status failed: ${res.status}`)
    }
    return (await res.json()) as SessionStatus
  }

  async submitRedirect(id: string, url: string): Promise<{ ok: boolean; error?: string }> {
    const res = await this.fetchImpl(`${this.baseUrl}/sessions/${encodeURIComponent(id)}/redirect`, {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify({ url }),
    })
    const body = (await res.json().catch(() => ({}))) as { ok?: boolean; error?: string }
    return { ok: res.ok && body.ok !== false, error: body.error }
  }

  async cancel(id: string): Promise<{ ok: boolean }> {
    const res = await this.fetchImpl(`${this.baseUrl}/sessions/${encodeURIComponent(id)}/cancel`, {
      method: 'POST',
      headers: this.headers(),
      body: '{}',
    })
    return { ok: res.ok }
  }
}
