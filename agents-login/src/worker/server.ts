import Fastify, { type FastifyInstance } from 'fastify'
import type { Logger } from '../shared/log.js'
import type { Provider, SessionStatus } from '../shared/types.js'
import { redactString } from '../shared/redact.js'
import type { SessionManager } from './session.js'
import type { AgentsApiClient } from './agentsApiClient.js'

export interface WorkerServerDeps {
  sessions: SessionManager
  internalToken: string
  logger: Logger
  agentsApi: Pick<AgentsApiClient, 'storedStatus'>
}

function isProvider(v: unknown): v is Provider {
  return v === 'claude' || v === 'codex'
}

// The status payload never carries a captured credential — those go straight to
// agents-api. authorizeUrl / verificationUrl / deviceCode are exactly what the UI
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

  // There is no read-side agents-api credential endpoint. Report only a local
  // placeholder so the UI can remain explicit that stored state is unknown.
  app.get('/status', async () => {
    const claude = deps.agentsApi.storedStatus()
    const codex = deps.agentsApi.storedStatus()
    return { claude, codex }
  })

  app.post('/sessions', async (req, reply) => {
    const body = (req.body ?? {}) as { provider?: unknown; updatedBy?: unknown }
    deps.logger.info('worker session start request received', {
      provider: typeof body.provider === 'string' ? body.provider : 'invalid',
      updatedBy: typeof body.updatedBy === 'string' && body.updatedBy ? body.updatedBy : 'unknown',
    })
    if (!isProvider(body.provider)) {
      return reply.code(400).send({ error: 'provider must be "claude" or "codex"' })
    }
    const updatedBy = typeof body.updatedBy === 'string' && body.updatedBy ? body.updatedBy : 'unknown'
    try {
      const status = deps.sessions.start(body.provider, updatedBy)
      deps.logger.info('worker session start request completed', {
        sessionId: status.id,
        provider: status.provider,
        phase: status.phase,
      })
      return reply.code(201).send(safeStatus(status))
    } catch (err) {
      deps.logger.warn('worker session start request failed', {
        provider: body.provider,
        error: err instanceof Error ? err.message : 'cannot start session',
      })
      return reply.code(409).send({ error: err instanceof Error ? err.message : 'cannot start session' })
    }
  })

  app.get('/sessions/:id', async (req, reply) => {
    const { id } = req.params as { id: string }
    const status = deps.sessions.status(id)
    if (!status) {
      deps.logger.warn('worker session status request missed', { sessionId: id })
      return reply.code(404).send({ error: 'no matching session' })
    }
    deps.logger.info('worker session status request completed', {
      sessionId: id,
      provider: status.provider,
      phase: status.phase,
    })
    return reply.send(safeStatus(status))
  })

  app.post('/sessions/:id/redirect', async (req, reply) => {
    const { id } = req.params as { id: string }
    const body = (req.body ?? {}) as { url?: unknown }
    if (typeof body.url !== 'string') {
      return reply.code(400).send({ error: 'url is required' })
    }
    deps.logger.info('worker redirect submission request received', {
      sessionId: id,
      inputBytes: body.url.length,
    })
    const result = deps.sessions.submitRedirectUrl(id, body.url)
    if (!result.ok) {
      deps.logger.warn('worker redirect submission request rejected', { sessionId: id, error: result.error })
      return reply.code(400).send({ error: result.error })
    }
    deps.logger.info('worker redirect submission request completed', { sessionId: id })
    return reply.send({ ok: true })
  })

  app.post('/sessions/:id/cancel', async (req, reply) => {
    const { id } = req.params as { id: string }
    const result = deps.sessions.cancel(id)
    deps.logger.info('worker cancel request completed', { sessionId: id, ok: result.ok })
    return reply.code(result.ok ? 200 : 404).send(result)
  })

  return app
}
