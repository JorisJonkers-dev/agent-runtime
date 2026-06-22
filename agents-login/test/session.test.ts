import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { SessionManager, type SessionDeps } from '../src/worker/session.js'
import { NoopLeaseLock } from '../src/worker/lease.js'
import { VaultClient } from '../src/worker/vaultClient.js'
import { createLogger } from '../src/shared/log.js'
import { fakeSpawner, type Action } from './helpers/fakePty.js'
import { FakeVault } from './helpers/fakeVault.js'

async function tick(times = 8): Promise<void> {
  for (let i = 0; i < times; i += 1) {
    await new Promise((r) => setTimeout(r, 5))
  }
}

// Poll until the session reaches one of the expected phases (handles the async
// finalize that awaits credential capture + the lease + the Vault round-trip).
async function waitPhase(
  mgr: { status: (id?: string) => { phase: string } | undefined },
  id: string,
  phases: string[],
  timeoutMs = 2000,
): Promise<string> {
  const start = Date.now()
  for (;;) {
    const phase = mgr.status(id)?.phase ?? 'unknown'
    if (phases.includes(phase) || Date.now() - start > timeoutMs) {
      return phase
    }
    await new Promise((r) => setTimeout(r, 10))
  }
}

describe('LoginSession state machine', () => {
  let root: string
  let home: string
  let codexHome: string
  let vault: FakeVault
  let vaultClient: VaultClient

  beforeEach(async () => {
    root = mkdtempSync(join(tmpdir(), 'sess-'))
    home = join(root, 'home')
    codexHome = join(home, '.codex')
    mkdirSync(join(home, '.claude'), { recursive: true })
    mkdirSync(codexHome, { recursive: true })
    vault = new FakeVault()
    await vault.start()
    vaultClient = new VaultClient(
      {
        addr: vault.url,
        k8sRole: 'r',
        k8sMount: 'kubernetes',
        kvMount: 'secret',
        saTokenPath: '/x',
        maxCasRetries: 3,
      },
      fetch,
      async () => 'jwt',
    )
    await vaultClient.login()
  })

  afterEach(async () => {
    await vault.stop()
    rmSync(root, { recursive: true, force: true })
  })

  function deps(
    scriptFor: (file: string, args: string[]) => Action[],
    lease = new NoopLeaseLock(),
  ): {
    deps: SessionDeps
    instances: ReturnType<typeof fakeSpawner>['instances']
  } {
    const { spawner, instances } = fakeSpawner(scriptFor)
    return {
      deps: {
        spawner,
        vault: vaultClient,
        lease,
        paths: { home, codexHome },
        vaultPaths: { claude: 'agents/claude-oauth', codex: 'agents/codex-oauth' },
        logger: createLogger(),
        ttlMs: 60_000,
      },
      instances,
    }
  }

  it('drives the full Claude flow: authorize URL → redirect paste-back → success → Vault write', async () => {
    writeFileSync(join(home, '.claude', '.credentials.json'), '{"accessToken":"secret"}')
    writeFileSync(join(home, '.claude.json'), '{"oauthAccount":{}}')

    const { deps: d } = deps(() => [
      { type: 'emit', data: 'Visit https://claude.ai/oauth/authorize?code=1\r\n' },
      {
        type: 'expectStdin',
        match: (i) => i.includes('https://claude.ai/redirect'),
        then: [
          { type: 'emit', data: 'Login successful.\r\n' },
          { type: 'exit', code: 0 },
        ],
      },
    ])
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')
    expect(started.phase).toBe('starting')

    await tick()
    const s = mgr.status(started.id)!
    expect(s.phase).toBe('awaiting_url')
    expect(s.authorizeUrl).toContain('claude.ai/oauth/authorize')
    expect(s.needsRedirectUrl).toBe(true)

    const sub = mgr.submitRedirectUrl(started.id, 'https://claude.ai/redirect?code=2')
    expect(sub.ok).toBe(true)

    expect(await waitPhase(mgr, started.id, ['succeeded', 'failed'])).toBe('succeeded')
    expect(vault.store.get('agents/claude-oauth')?.data['claude_json']).toBe('{"oauthAccount":{}}')
    expect(vault.store.get('agents/claude-oauth')?.data.updated_by).toBe('alice')
  })

  it('drives the Codex device flow with no paste-back', async () => {
    writeFileSync(join(codexHome, 'auth.json'), '{"tokens":{}}')
    writeFileSync(join(codexHome, 'config.toml'), 'model="x"\n')

    const { deps: d } = deps(() => [
      { type: 'emit', data: 'Open https://auth.openai.com/device\r\nEnter the code: WXYZ-1234\r\n' },
      { type: 'emit', data: 'Successfully logged in.\r\n' },
      { type: 'exit', code: 0 },
    ])
    const mgr = new SessionManager(d)
    const started = mgr.start('codex', 'bob')
    expect(await waitPhase(mgr, started.id, ['succeeded', 'failed'])).toBe('succeeded')
    expect(vault.store.get('agents/codex-oauth')?.data['auth_json']).toBe('{"tokens":{}}')
  })

  it('surfaces the device code before success', async () => {
    const { deps: d } = deps(() => [
      { type: 'emit', data: 'Open https://auth.openai.com/device\r\nEnter the code: ABCD-9999\r\n' },
      // never succeeds within this assertion window
    ])
    const mgr = new SessionManager(d)
    const started = mgr.start('codex', 'bob')
    await tick(2)
    const s = mgr.status(started.id)!
    expect(s.phase).toBe('awaiting_device')
    expect(s.deviceCode).toBe('ABCD-9999')
    expect(s.verificationUrl).toBe('https://auth.openai.com/device')
  })

  it('rejects an empty authorization code but accepts a non-URL code', async () => {
    const { deps: d } = deps(() => [
      { type: 'emit', data: 'Visit https://claude.ai/oauth/authorize?code=1\r\n' },
      { type: 'expectStdin', match: () => true, then: [] },
    ])
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')
    await tick()
    expect(mgr.submitRedirectUrl(started.id, '   ').ok).toBe(false)
    // setup-token returns a bare code, not a URL — it must be accepted.
    expect(mgr.submitRedirectUrl(started.id, 'ABCD-1234-token').ok).toBe(true)
  })

  it('rejects redirect submission for the codex provider', async () => {
    const { deps: d } = deps(() => [{ type: 'emit', data: 'code: ABCD\r\n' }])
    const mgr = new SessionManager(d)
    const started = mgr.start('codex', 'bob')
    await tick(2)
    const r = mgr.submitRedirectUrl(started.id, 'https://x')
    expect(r.ok).toBe(false)
  })

  it('fails when the CLI exits non-zero before login', async () => {
    const { deps: d } = deps(() => [
      { type: 'emit', data: 'starting...\r\n' },
      { type: 'exit', code: 1 },
    ])
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')
    await tick()
    const s = mgr.status(started.id)!
    expect(s.phase).toBe('failed')
    expect(s.error).toMatch(/exited with code 1/)
  })

  it('marks the session failed on an explicit CLI error line', async () => {
    const { deps: d } = deps(() => [{ type: 'emit', data: 'error: something broke\r\n' }])
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')
    await tick()
    expect(mgr.status(started.id)!.phase).toBe('failed')
  })

  it('cancels an in-flight session and kills the child', async () => {
    const { deps: d, instances } = deps(() => [
      { type: 'emit', data: 'Visit https://claude.ai/oauth/authorize?code=1\r\n' },
      { type: 'expectStdin', match: () => true, then: [] },
    ])
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')
    await tick()
    expect(mgr.cancel(started.id).ok).toBe(true)
    expect(mgr.status(started.id)!.phase).toBe('cancelled')
    expect(instances[0].killed).toBe(true)
  })

  it('times out a stalled session', async () => {
    const { deps: d } = deps(() => [{ type: 'emit', data: 'Visit https://claude.ai/oauth/authorize?code=1\r\n' }])
    d.ttlMs = 20
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')
    await tick(10)
    expect(mgr.status(started.id)!.phase).toBe('cancelled')
  })

  it('refuses a second concurrent session', async () => {
    const { deps: d } = deps(() => [{ type: 'emit', data: 'Visit https://claude.ai/oauth/authorize?code=1\r\n' }])
    const mgr = new SessionManager(d)
    mgr.start('claude', 'alice')
    await tick()
    expect(() => mgr.start('codex', 'alice')).toThrow(/already in progress/)
  })

  it('surfaces a persistent Vault CAS conflict as a failed session', async () => {
    writeFileSync(join(home, '.claude', '.credentials.json'), '{}')
    writeFileSync(join(home, '.claude.json'), '{}')
    await vault.stop()
    vault = new FakeVault({ conflictCount: 99 })
    await vault.start()
    const conflictClient = new VaultClient(
      { addr: vault.url, k8sRole: 'r', k8sMount: 'kubernetes', kvMount: 'secret', saTokenPath: '/x', maxCasRetries: 2 },
      fetch,
      async () => 'jwt',
    )
    await conflictClient.login()

    const { spawner } = fakeSpawner(() => [
      { type: 'emit', data: 'Visit https://claude.ai/oauth/authorize?code=1\r\n' },
      { type: 'expectStdin', match: () => true, then: [{ type: 'emit', data: 'Login successful.\r\n' }] },
    ])
    const mgr = new SessionManager({
      spawner,
      vault: conflictClient,
      lease: new NoopLeaseLock(),
      paths: { home, codexHome },
      vaultPaths: { claude: 'agents/claude-oauth', codex: 'agents/codex-oauth' },
      logger: createLogger(),
      ttlMs: 60_000,
    })
    const started = mgr.start('claude', 'alice')
    await tick()
    mgr.submitRedirectUrl(started.id, 'https://claude.ai/redirect')
    expect(await waitPhase(mgr, started.id, ['failed', 'succeeded'])).toBe('failed')
    expect(mgr.status(started.id)!.error).toMatch(/conflict/i)
  })

  it('returns undefined status for an unknown id', () => {
    const { deps: d } = deps(() => [])
    const mgr = new SessionManager(d)
    expect(mgr.status('nope')).toBeUndefined()
    expect(mgr.cancel('nope').ok).toBe(false)
    expect(mgr.submitRedirectUrl('nope', 'https://x').ok).toBe(false)
  })
})
