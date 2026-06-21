import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from 'fastify'
import type { Logger } from '../shared/log.js'
import type { Provider } from '../shared/types.js'
import { redactValue } from '../shared/redact.js'
import { CsrfStore } from './csrf.js'
import { renderUi } from './ui.js'
import type { WorkerClient } from './workerClient.js'

export interface ControllerServerDeps {
  worker: WorkerClient
  logger: Logger
  userHeader: string
  rolesHeader: string
  requiredPermission: string
  sessionTtlMs: number
}

function isProvider(v: unknown): v is Provider {
  return v === 'claude' || v === 'codex'
}

interface Identity {
  user: string
  roles: string[]
}

export function buildControllerServer(deps: ControllerServerDeps): FastifyInstance {
  const app = Fastify({ logger: false })
  const csrf = new CsrfStore(deps.sessionTtlMs)

  app.addHook('onSend', async (_req, reply, payload) => {
    reply.header('cache-control', 'no-store')
    return payload
  })

  function identity(req: FastifyRequest): Identity | undefined {
    const user = req.headers[deps.userHeader.toLowerCase()]
    if (typeof user !== 'string' || user.length === 0) {
      return undefined
    }
    const rawRoles = req.headers[deps.rolesHeader.toLowerCase()]
    const roles =
      typeof rawRoles === 'string'
        ? rawRoles
            .split(',')
            .map((r) => r.trim())
            .filter(Boolean)
        : []
    return { user, roles }
  }

  // Gate every route except health on a present forward-auth identity and, when
  // configured, the required permission/role. The edge forward-auth is the
  // primary enforcement; this is defence in depth.
  function gate(req: FastifyRequest, reply: FastifyReply): Identity | undefined {
    const id = identity(req)
    if (!id) {
      deps.logger.warn('controller rejected request without identity header', { url: req.url })
      reply.code(401).send({ error: 'not authenticated' })
      return undefined
    }
    if (deps.requiredPermission && !id.roles.includes(deps.requiredPermission)) {
      deps.logger.warn('controller rejected request lacking permission', { user: id.user })
      reply.code(403).send({ error: 'forbidden' })
      return undefined
    }
    return id
  }

  function requireCsrf(id: Identity, req: FastifyRequest, reply: FastifyReply): boolean {
    const presented = req.headers['x-csrf-token']
    if (!csrf.verify(id.user, typeof presented === 'string' ? presented : undefined)) {
      deps.logger.warn('controller rejected request with bad CSRF token', { user: id.user })
      reply.code(403).send({ error: 'invalid csrf token' })
      return false
    }
    return true
  }

  app.get('/healthz', async () => ({ ok: true }))

  app.get('/', async (req, reply) => {
    const id = gate(req, reply)
    if (!id) {
      return reply
    }
    const token = csrf.issue(id.user)
    return reply.type('text/html; charset=utf-8').send(renderUi(token, id.user))
  })

  app.post('/api/login', async (req, reply) => {
    const id = gate(req, reply)
    if (!id) {
      return reply
    }
    if (!requireCsrf(id, req, reply)) {
      return reply
    }
    const body = (req.body ?? {}) as { provider?: unknown }
    if (!isProvider(body.provider)) {
      return reply.code(400).send({ error: 'provider must be "claude" or "codex"' })
    }
    try {
      const status = await deps.worker.start(body.provider, id.user)
      deps.logger.info('controller started login', { user: id.user, provider: body.provider })
      return reply.code(201).send(redactValue(status))
    } catch (err) {
      return reply.code(502).send({ error: err instanceof Error ? err.message : 'worker error' })
    }
  })

  app.get('/api/status', async (req, reply) => {
    const id = gate(req, reply)
    if (!id) {
      return reply
    }
    const sessionId = (req.query as { id?: string }).id
    if (!sessionId) {
      return reply.code(400).send({ error: 'id is required' })
    }
    const status = await deps.worker.status(sessionId)
    if (!status) {
      return reply.code(404).send({ error: 'no matching session' })
    }
    return reply.send(redactValue(status))
  })

  app.post('/api/redirect', async (req, reply) => {
    const id = gate(req, reply)
    if (!id) {
      return reply
    }
    if (!requireCsrf(id, req, reply)) {
      return reply
    }
    const body = (req.body ?? {}) as { id?: unknown; url?: unknown }
    if (typeof body.id !== 'string' || typeof body.url !== 'string') {
      return reply.code(400).send({ error: 'id and url are required' })
    }
    const result = await deps.worker.submitRedirect(body.id, body.url)
    if (!result.ok) {
      return reply.code(400).send({ error: result.error ?? 'redirect rejected' })
    }
    return reply.send({ ok: true })
  })

  app.post('/api/cancel', async (req, reply) => {
    const id = gate(req, reply)
    if (!id) {
      return reply
    }
    if (!requireCsrf(id, req, reply)) {
      return reply
    }
    const body = (req.body ?? {}) as { id?: unknown }
    if (typeof body.id !== 'string') {
      return reply.code(400).send({ error: 'id is required' })
    }
    const result = await deps.worker.cancel(body.id)
    return reply.send(result)
  })

  return app
}
