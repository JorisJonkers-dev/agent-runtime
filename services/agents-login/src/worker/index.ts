import { createLogger } from '../shared/log.js'
import { loadWorkerConfig } from '../shared/config.js'
import { NoopLeaseLock } from './lease.js'
import { createNodePtySpawner } from './pty.js'
import { buildWorkerServer } from './server.js'
import { SessionManager } from './session.js'
import { AgentsApiClient } from './agentsApiClient.js'

export async function runWorker(): Promise<void> {
  const cfg = loadWorkerConfig()
  const logger = createLogger()

  const agentsApi = new AgentsApiClient({
    baseUrl: cfg.agentsApiInternalUrl,
    bearer: cfg.agentsApiInternalBearer,
    logger,
  })

  // Credentials are persisted by agents-api as a per-(user, provider) Postgres
  // upsert, and this worker runs as a single replica with at most one session
  // per provider, so concurrent writers cannot collide. The previous
  // coordination.k8s.io lease only served the old Vault CAS write; it reached
  // the kube-apiserver over HTTPS without the cluster CA, so finalize threw
  // "fetch failed" before the captured token was ever posted. No distributed
  // lock is needed now.
  const lease = new NoopLeaseLock()

  const spawner = await createNodePtySpawner(logger)

  const sessions = new SessionManager({
    spawner,
    agentsApi,
    lease,
    paths: { home: cfg.home, codexHome: cfg.codexHome },
    logger,
    ttlMs: cfg.sessionTtlMs,
  })

  const app = buildWorkerServer({
    sessions,
    internalToken: cfg.internalToken,
    logger,
    agentsApi,
  })
  await app.listen({ host: cfg.host, port: cfg.port })
  logger.info('worker listening', { host: cfg.host, port: cfg.port })
}
