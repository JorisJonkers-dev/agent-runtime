import { createLogger } from '../shared/log.js'
import { loadControllerConfig } from '../shared/config.js'
import { buildControllerServer } from './server.js'
import { HttpWorkerClient } from './workerClient.js'

export async function runController(): Promise<void> {
  const cfg = loadControllerConfig()
  const logger = createLogger()
  const worker = new HttpWorkerClient(cfg.workerUrl, cfg.internalToken)
  const app = buildControllerServer({
    worker,
    logger,
    userHeader: cfg.userHeader,
    rolesHeader: cfg.rolesHeader,
    requiredPermission: cfg.requiredPermission,
    sessionTtlMs: cfg.sessionTtlMs,
  })
  await app.listen({ host: cfg.host, port: cfg.port })
  logger.info('controller listening', { host: cfg.host, port: cfg.port })
}
