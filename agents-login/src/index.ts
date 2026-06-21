import { resolveMode } from './shared/config.js'
import { runController } from './controller/index.js'
import { runWorker } from './worker/index.js'

async function main(): Promise<void> {
  const mode = resolveMode()
  if (mode === 'controller') {
    await runController()
  } else {
    await runWorker()
  }
}

main().catch((err) => {
  process.stderr.write(`fatal: ${err instanceof Error ? (err.stack ?? err.message) : String(err)}\n`)
  process.exit(1)
})
