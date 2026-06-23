import Fastify, { type FastifyInstance } from 'fastify'
import type { Logger } from '../shared/log.js'
import type { Provider, SessionStatus } from '../shared/types.js'
import { redactString } from '../shared/redact.js'
import type { SessionManager } from './session.js'

export interface WorkerServerDeps {
  sessions: SessionManager
  internalToken: string
  logger: Logger
}

function isProvider(v: unknown): v is Provider {
  return v === 'claude' || v === 'codex'
}

// The status payload never carries a captured credential — those go straight to
// Vault. authorizeUrl / verificationUrl / deviceCode are exactly what the UI
// must show the operator, and the OAuth `state` + PKCE `code_challenge` are long
// base64url strings the generic token redactor would otherwise mask, breaking
// the URL the operator has to open. Only the free-text message/error fields
// (which can echo PTY output) are scrubbed.
function safeStatus(status: SessionStatus): SessionStatus {
  return {
    ...status,
    message: status.message === undefined ? status.message : redactString(status.message),
    error: status.error === undefined ? status.error : redactString(status.error),
  }
}

export function buildWorkerServer(deps: WorkerServerDeps): FastifyInstance {
  const app = Fastify({ logger: false })

  // agents-api issues body-less POSTs (cancel) whose content-type Fastify has
  // no parser for, which it would otherwise reject with 415 — bricking the UI
  // Cancel button. Accept any unmatched content-type, treating a non-JSON body
  // as empty; the typed routes that need a body are always sent application/json
  // and keep using the built-in JSON parser.
  app.addContentTypeParser('*', { parseAs: 'string' }, (_req, body, done) => {
    done(null, body.length > 0 ? body : undefined)
  })

  app.addHook('onSend', async (_req, reply, payload) => {
    reply.header('cache-control', 'no-store')
    return payload
  })

  // Internal shared-token auth on every route except health.
  app.addHook('preHandler', async (req, reply) => {
    if (req.url === '/healthz') {
      return
    }
    const presented = req.headers['x-internal-token']
    if (presented !== deps.internalToken) {
      deps.logger.warn('worker rejected unauthenticated request', { url: req.url })
      reply.code(401).send({ error: 'unauthorized' })
    }
  })

  app.get('/healthz', async () => ({ ok: true }))

  app.post('/sessions', async (req, reply) => {
    const body = (req.body ?? {}) as { provider?: unknown; updatedBy?: unknown }
    if (!isProvider(body.provider)) {
      return reply.code(400).send({ error: 'provider must be "claude" or "codex"' })
    }
    const updatedBy = typeof body.updatedBy === 'string' && body.updatedBy ? body.updatedBy : 'unknown'
    try {
      const status = deps.sessions.start(body.provider, updatedBy)
      return reply.code(201).send(safeStatus(status))
    } catch (err) {
      return reply.code(409).send({ error: err instanceof Error ? err.message : 'cannot start session' })
    }
  })

  app.get('/sessions/:id', async (req, reply) => {
    const { id } = req.params as { id: string }
    const status = deps.sessions.status(id)
    if (!status) {
      return reply.code(404).send({ error: 'no matching session' })
    }
    return reply.send(safeStatus(status))
  })

  app.post('/sessions/:id/redirect', async (req, reply) => {
    const { id } = req.params as { id: string }
    const body = (req.body ?? {}) as { url?: unknown }
    if (typeof body.url !== 'string') {
      return reply.code(400).send({ error: 'url is required' })
    }
    const result = deps.sessions.submitRedirectUrl(id, body.url)
    if (!result.ok) {
      return reply.code(400).send({ error: result.error })
    }
    return reply.send({ ok: true })
  })

  app.post('/sessions/:id/cancel', async (req, reply) => {
    const { id } = req.params as { id: string }
    const result = deps.sessions.cancel(id)
    return reply.code(result.ok ? 200 : 404).send(result)
  })

  return app
}
