import { randomBytes, timingSafeEqual } from 'node:crypto'

export interface CsrfSession {
  token: string
  expiresAt: number
}

/**
 * Per-user CSRF token store with a short TTL. The double-submit token is handed
 * to the UI in the bootstrap HTML and echoed back in an X-CSRF-Token header on
 * every mutating request.
 */
export class CsrfStore {
  private readonly sessions = new Map<string, CsrfSession>()

  constructor(private readonly ttlMs: number) {}

  issue(user: string, now = Date.now()): string {
    const token = randomBytes(32).toString('hex')
    this.sessions.set(user, { token, expiresAt: now + this.ttlMs })
    return token
  }

  verify(user: string, presented: string | undefined, now = Date.now()): boolean {
    const session = this.sessions.get(user)
    if (!session || !presented) {
      return false
    }
    if (session.expiresAt < now) {
      this.sessions.delete(user)
      return false
    }
    const a = Buffer.from(session.token)
    const b = Buffer.from(presented)
    if (a.length !== b.length) {
      return false
    }
    return timingSafeEqual(a, b)
  }
}
