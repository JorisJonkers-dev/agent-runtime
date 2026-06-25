import { runWorker } from './worker/index.js'

// The credential-login worker: the only process this image runs. It owns the
// PTY that drives `claude /login` / `codex login --device`, captures the
// credential files, and posts them to agents-api under a Lease. The browser
// UI and its auth live in agents-ui / agents-api, which proxy to this worker
// over the internal token.
runWorker().catch((err) => {
  process.stderr.write(`fatal: ${err instanceof Error ? (err.stack ?? err.message) : String(err)}\n`)
  process.exit(1)
})
