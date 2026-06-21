import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'

interface SecretEntry {
  version: number
  data: Record<string, string>
}

export interface FakeVaultOptions {
  // Force the first N writes to a path to fail with a CAS mismatch (simulating
  // a racing writer), to exercise the retry / persistent-conflict paths.
  conflictCount?: number
  // Reject the k8s login with this status (default 200 OK).
  loginStatus?: number
}

/**
 * Minimal in-process Vault KV v2 server speaking just enough of the API for the
 * client: k8s login, metadata read, and CAS data write.
 */
export class FakeVault {
  private server: Server
  readonly store = new Map<string, SecretEntry>()
  private remainingConflicts: number
  readonly logins: Array<{ role: string; jwt: string }> = []
  url = ''

  constructor(private readonly opts: FakeVaultOptions = {}) {
    this.remainingConflicts = opts.conflictCount ?? 0
    this.server = createServer((req, res) => this.handle(req, res))
  }

  async start(): Promise<void> {
    await new Promise<void>((resolve) => this.server.listen(0, '127.0.0.1', resolve))
    const addr = this.server.address() as AddressInfo
    this.url = `http://127.0.0.1:${addr.port}`
  }

  async stop(): Promise<void> {
    await new Promise<void>((resolve) => this.server.close(() => resolve()))
  }

  private async body(req: import('node:http').IncomingMessage): Promise<string> {
    const chunks: Buffer[] = []
    for await (const c of req) {
      chunks.push(c as Buffer)
    }
    return Buffer.concat(chunks).toString('utf8')
  }

  private handle(req: import('node:http').IncomingMessage, res: import('node:http').ServerResponse): void {
    void this.route(req, res).catch(() => {
      res.statusCode = 500
      res.end('{"errors":["internal"]}')
    })
  }

  private async route(
    req: import('node:http').IncomingMessage,
    res: import('node:http').ServerResponse,
  ): Promise<void> {
    const url = req.url ?? ''
    res.setHeader('content-type', 'application/json')

    if (url.endsWith('/login')) {
      if (this.opts.loginStatus && this.opts.loginStatus !== 200) {
        res.statusCode = this.opts.loginStatus
        res.end('{"errors":["denied"]}')
        return
      }
      const parsed = JSON.parse((await this.body(req)) || '{}')
      this.logins.push({ role: parsed.role, jwt: parsed.jwt })
      res.statusCode = 200
      res.end(JSON.stringify({ auth: { client_token: 'fake-token', lease_duration: 3600 } }))
      return
    }

    // /v1/<mount>/metadata/<path>
    const metaMatch = url.match(/\/v1\/[^/]+\/metadata\/(.+)$/)
    if (metaMatch && req.method === 'GET') {
      const entry = this.store.get(metaMatch[1])
      if (!entry) {
        res.statusCode = 404
        res.end('{"errors":[]}')
        return
      }
      res.statusCode = 200
      res.end(JSON.stringify({ data: { current_version: entry.version } }))
      return
    }

    // /v1/<mount>/data/<path>
    const dataMatch = url.match(/\/v1\/[^/]+\/data\/(.+)$/)
    if (dataMatch && req.method === 'POST') {
      const path = dataMatch[1]
      const payload = JSON.parse((await this.body(req)) || '{}')
      const cas = payload.options?.cas
      const existing = this.store.get(path)
      const currentVersion = existing?.version ?? 0

      if (this.remainingConflicts > 0) {
        this.remainingConflicts -= 1
        res.statusCode = 400
        res.end('{"errors":["check-and-set parameter did not match the current version"]}')
        return
      }
      if (cas !== currentVersion) {
        res.statusCode = 400
        res.end('{"errors":["check-and-set parameter did not match the current version"]}')
        return
      }
      this.store.set(path, { version: currentVersion + 1, data: payload.data })
      res.statusCode = 200
      res.end(JSON.stringify({ data: { version: currentVersion + 1 } }))
      return
    }

    res.statusCode = 404
    res.end('{"errors":["not found"]}')
  }
}
