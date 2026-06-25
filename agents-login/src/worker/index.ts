import { createLogger } from '../shared/log.js'
import { loadWorkerConfig } from '../shared/config.js'
import { K8sLeaseLock } from './lease.js'
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
  })

  const lease = new K8sLeaseLock({
    name: cfg.leaseName,
    namespace: cfg.leaseNamespace,
    holderIdentity: `${process.env.HOSTNAME ?? 'agents-login-worker'}-${process.pid}`,
    saTokenPath: cfg.saTokenPath,
  })

  const spawner = await createNodePtySpawner()

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
