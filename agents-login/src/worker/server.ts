import Fastify, { type FastifyInstance } from 'fastify'
import type { Logger } from '../shared/log.js'
import type { Provider } from '../shared/types.js'
import { redactValue } from '../shared/redact.js'
import type { SessionManager } from './session.js'

export interface WorkerServerDeps {
  sessions: SessionManager
  internalToken: string
  logger: Logger
}

function isProvider(v: unknown): v is Provider {
  return v === 'claude' || v === 'codex'
}

export function buildWorkerServer(deps: WorkerServerDeps): FastifyInstance {
  const app = Fastify({ logger: false })

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
      return reply.code(201).send(redactValue(status))
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
    return reply.send(redactValue(status))
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
