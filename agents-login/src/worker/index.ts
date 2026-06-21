import { createLogger } from '../shared/log.js'
import { loadWorkerConfig } from '../shared/config.js'
import { K8sLeaseLock } from './lease.js'
import { createNodePtySpawner } from './pty.js'
import { buildWorkerServer } from './server.js'
import { SessionManager } from './session.js'
import { VaultClient } from './vaultClient.js'

export async function runWorker(): Promise<void> {
  const cfg = loadWorkerConfig()
  const logger = createLogger()

  const vault = new VaultClient({
    addr: cfg.vaultAddr,
    k8sRole: cfg.vaultK8sRole,
    k8sMount: cfg.vaultK8sMount,
    kvMount: cfg.vaultKvMount,
    saTokenPath: cfg.saTokenPath,
    maxCasRetries: cfg.casMaxRetries,
  })
  await vault.login()

  const lease = new K8sLeaseLock({
    name: cfg.leaseName,
    namespace: cfg.leaseNamespace,
    holderIdentity: `${process.env.HOSTNAME ?? 'agents-login-worker'}-${process.pid}`,
    saTokenPath: cfg.saTokenPath,
  })

  const spawner = await createNodePtySpawner()

  const sessions = new SessionManager({
    spawner,
    vault,
    lease,
    paths: { home: cfg.home, codexHome: cfg.codexHome },
    vaultPaths: { claude: cfg.vaultClaudePath, codex: cfg.vaultCodexPath },
    logger,
    ttlMs: cfg.sessionTtlMs,
  })

  const app = buildWorkerServer({ sessions, internalToken: cfg.internalToken, logger })
  await app.listen({ host: cfg.host, port: cfg.port })
  logger.info('worker listening', { host: cfg.host, port: cfg.port })
}
