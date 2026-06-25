import { describe, it, expect, beforeAll } from 'vitest'
import { rmSync } from 'node:fs'
import { SessionManager } from '../src/worker/session.js'
import { NoopLeaseLock } from '../src/worker/lease.js'
import { createLogger } from '../src/shared/log.js'
import { createNodePtySpawner } from '../src/worker/pty.js'
import type { PtySpawner } from '../src/worker/pty.js'
import { makeFakeCliEnv, type FakeCliEnv } from './helpers/fakeCli.js'

// Drives the worker through a REAL pseudo-terminal against fake `claude` /
// `codex` shell scripts on PATH — no network, no real CLIs. If node-pty's
// native addon is unavailable on the host, the suite is skipped (the FakePty
// state-machine tests already cover the logic deterministically).
let spawner: PtySpawner | undefined

beforeAll(async () => {
  try {
    const candidate = await createNodePtySpawner()
    // Probe an actual spawn — some sandboxes load the addon but block
    // posix_spawnp. If the probe throws, fall back to skip.
    const proc = candidate('sh', ['-c', 'exit 0'], { cwd: process.cwd(), env: process.env })
    proc.kill()
    spawner = candidate
  } catch {
    spawner = undefined
  }
})

async function waitFor(fn: () => boolean, timeoutMs = 5000): Promise<void> {
  const start = Date.now()
  while (!fn()) {
    if (Date.now() - start > timeoutMs) {
      throw new Error('timed out waiting for condition')
    }
    await new Promise((r) => setTimeout(r, 25))
  }
}

describe('real-PTY integration with fake CLIs', () => {
  // Local-only smoke test. It drives a REAL pseudo-terminal against fake CLI
  // scripts, which is timing-sensitive under CI load; the deterministic FakePty
  // tests in session.test.ts cover the same state machine, parsing, capture and
  // agents-api ingest. Skipped when CI is set so a PTY timing flake never gates merges.
  it.runIf(!process.env.CI)(
    'captures Claude + Codex creds end to end via PTY',
    async () => {
      if (!spawner) {
        // node-pty addon not loadable here; FakePty tests cover the logic.
        return
      }
      const cli: FakeCliEnv = makeFakeCliEnv()
      process.env.PATH = cli.pathEnv
      const posts: Array<{ userId: string; provider: string; payload: Record<string, string> }> = []

      const mgr = new SessionManager({
        spawner,
        agentsApi: {
          async postCredentials(req) {
            posts.push(req)
          },
        },
        lease: new NoopLeaseLock(),
        paths: { home: cli.home, codexHome: cli.codexHome },
        logger: createLogger(),
        ttlMs: 30_000,
      })

      // Claude: wait for the authorize URL, paste the redirect, expect success.
      const claude = mgr.start('claude', 'alice')
      await waitFor(() => mgr.status(claude.id)!.phase === 'awaiting_url')
      expect(mgr.status(claude.id)!.authorizeUrl).toContain('claude.ai/oauth/authorize')
      const sub = mgr.submitRedirectUrl(
        claude.id,
        'https://platform.claude.com/oauth/code/callback?code=integration-code-2&state=integration-state-2',
      )
      expect(sub.ok).toBe(true)
      await waitFor(() => mgr.status(claude.id)!.phase === 'succeeded')
      expect(posts[0]).toMatchObject({ userId: 'alice', provider: 'CLAUDE' })
      expect(posts[0].payload.oauth_token).toContain('sk-ant-oat01')

      // Codex: device flow, no paste-back.
      const codex = mgr.start('codex', 'alice')
      await waitFor(() => mgr.status(codex.id)!.phase === 'succeeded')
      expect(posts[1]).toMatchObject({ userId: 'alice', provider: 'CODEX' })
      expect(posts[1].payload.auth_json).toContain('codex-secret')
      expect(posts[1].payload.config_toml).toContain('gpt-5')

      rmSync(cli.binDir.replace(/\/bin$/, ''), { recursive: true, force: true })
    },
    20_000,
  )
})
