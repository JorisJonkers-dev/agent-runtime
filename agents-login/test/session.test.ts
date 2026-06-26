import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { LoginSession, SessionManager, type SessionDeps } from '../src/worker/session.js'
import { NoopLeaseLock } from '../src/worker/lease.js'
import { createLogger, type Logger } from '../src/shared/log.js'
import { fakeSpawner, type Action } from './helpers/fakePty.js'

async function tick(times = 8): Promise<void> {
  for (let i = 0; i < times; i += 1) {
    await new Promise((r) => setTimeout(r, 5))
  }
}

async function waitForWrites(writes: () => string[], count: number, timeoutMs = 500): Promise<string[]> {
  const start = Date.now()
  for (;;) {
    const current = writes()
    if (current.length >= count || Date.now() - start > timeoutMs) {
      return current
    }
    await new Promise((r) => setTimeout(r, 10))
  }
}

interface PostedCredential {
  userId: string
  provider: 'CLAUDE' | 'CODEX'
  payload: Record<string, string>
}

interface LogEntry {
  level: 'info' | 'warn' | 'error' | 'debug'
  msg: string
  fields?: Record<string, unknown>
}

interface ClaudeArtifacts {
  token: string
  credentialsJson: string
  accountJson: string
  claudeJson: string
}

function memoryLogger(entries: LogEntry[]): Logger {
  return {
    info: (msg, fields) => entries.push({ level: 'info', msg, fields }),
    warn: (msg, fields) => entries.push({ level: 'warn', msg, fields }),
    error: (msg, fields) => entries.push({ level: 'error', msg, fields }),
    debug: (msg, fields) => entries.push({ level: 'debug', msg, fields }),
  }
}

function fakeAgentsApi(fail?: Error): {
  postCredentials: (req: PostedCredential) => Promise<void>
  posts: PostedCredential[]
} {
  const posts: PostedCredential[] = []
  return {
    posts,
    async postCredentials(req: PostedCredential) {
      if (fail) {
        throw fail
      }
      posts.push(req)
    },
  }
}

function claudeArtifacts(token = 'sk-ant-oat01-SubscriptionToken1234567890_-abcXYZ'): ClaudeArtifacts {
  const account = { emailAddress: 'alice@example.com', accountUuid: 'acct-1' }
  const credentials = {
    claudeAiOauth: {
      accessToken: token,
      refreshToken: 'refresh-token',
      scopes: ['user:profile', 'user:inference', 'user:sessions:claude_code'],
      subscriptionType: 'max',
    },
  }
  return {
    token,
    credentialsJson: JSON.stringify(credentials),
    accountJson: JSON.stringify(account),
    claudeJson: JSON.stringify({ installMethod: 'global', oauthAccount: account }),
  }
}

function writeClaudeArtifacts(homeDir: string, token?: string): ClaudeArtifacts {
  const artifacts = claudeArtifacts(token)
  writeFileSync(join(homeDir, '.claude', '.credentials.json'), artifacts.credentialsJson)
  writeFileSync(join(homeDir, '.claude.json'), artifacts.claudeJson)
  return artifacts
}

function claudePayload(artifacts: ClaudeArtifacts): Record<string, string> {
  return {
    credentials_json: artifacts.credentialsJson,
    account_json: artifacts.accountJson,
    oauth_token: artifacts.token,
  }
}

// Poll until the session reaches one of the expected phases (handles the async
// finalize that awaits credential capture + the lease + the agents-api POST).
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
  let agentsApi: ReturnType<typeof fakeAgentsApi>

  beforeEach(() => {
    root = mkdtempSync(join(tmpdir(), 'sess-'))
    home = join(root, 'home')
    codexHome = join(home, '.codex')
    mkdirSync(join(home, '.claude'), { recursive: true })
    mkdirSync(codexHome, { recursive: true })
    agentsApi = fakeAgentsApi()
  })

  afterEach(() => {
    rmSync(root, { recursive: true, force: true })
  })

  function deps(
    scriptFor: (file: string, args: string[]) => Action[],
    lease = new NoopLeaseLock(),
    logger: Logger = createLogger(),
  ): {
    deps: SessionDeps
    instances: ReturnType<typeof fakeSpawner>['instances']
  } {
    const { spawner, instances } = fakeSpawner(scriptFor)
    return {
      deps: {
        spawner,
        agentsApi,
        lease,
        paths: { home, codexHome },
        logger,
        ttlMs: 60_000,
      },
      instances,
    }
  }

  it('seeds Claude subscription login settings and drives bounded onboarding Enter-through', async () => {
    vi.useFakeTimers()
    try {
      const logs: LogEntry[] = []
      const { deps: d, instances } = deps(
        (file, args) => {
          expect(file).toBe('claude')
          expect(args).toEqual([])
          return [
            { type: 'emit', data: 'Choose the look for Claude Code\r\nDark mode\r\n' },
            {
              type: 'expectStdin',
              match: (i) => i === '\r',
              then: [
                {
                  type: 'emit',
                  data:
                    'Login method pre-selected: Subscription Plan (Claude Pro/Max)\r\n' +
                    "Browser didn't open? Use the url below to sign in\r\n" +
                    'https://claude.com/cai/oauth/authorize?code=true&state=nav\r\n' +
                    'Paste code here if prompted >',
                },
              ],
            },
          ]
        },
        new NoopLeaseLock(),
        memoryLogger(logs),
      )
      const mgr = new SessionManager(d)
      const started = mgr.start('claude', 'alice')
      await Promise.resolve()

      expect(readFileSync(join(home, '.claude.json'), 'utf8')).toBe(
        JSON.stringify({
          theme: 'dark',
          hasCompletedOnboarding: true,
          bypassPermissionsModeAccepted: true,
        }),
      )
      expect(readFileSync(join(home, 'managed-settings.json'), 'utf8')).toBe(
        JSON.stringify({ forceLoginMethod: 'claudeai' }),
      )

      await vi.advanceTimersByTimeAsync(1_500)
      await Promise.resolve()
      await Promise.resolve()

      expect(instances[0].writes).toEqual(['\r'])
      expect(mgr.status(started.id)!.phase).toBe('awaiting_url')
      expect(mgr.status(started.id)!.authorizeUrl).toContain('claude.com/cai/oauth/authorize')
      expect(logs.some((entry) => entry.msg === 'Claude onboarding Enter written to PTY')).toBe(true)
      mgr.cancel(started.id)
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not clobber an existing Claude account file when seeding onboarding', async () => {
    const existing = '{"oauthAccount":{"emailAddress":"keep@example.com"}}'
    writeFileSync(join(home, '.claude.json'), existing)
    const { deps: d } = deps(() => [])
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')

    expect(readFileSync(join(home, '.claude.json'), 'utf8')).toBe(existing)
    expect(readFileSync(join(home, 'managed-settings.json'), 'utf8')).toBe(
      JSON.stringify({ forceLoginMethod: 'claudeai' }),
    )
    mgr.cancel(started.id)
  })

  it('drives the full Claude flow: authorize URL → redirect paste-back → success → agents-api POST', async () => {
    const token = 'sk-ant-oat01-FullFlowToken1234567890_-abcXYZ'
    const artifacts = claudeArtifacts(token)

    const { deps: d, instances } = deps(() => [
      { type: 'emit', data: 'Visit https://claude.ai/oauth/authorize?code=1\r\nPaste code here if prompted >' },
      {
        type: 'expectStdin',
        match: (i) => i === 'redirect-code-2',
        then: [
          {
            type: 'expectStdin',
            match: (i) => i === '\r',
            then: [
              { type: 'effect', run: () => writeClaudeArtifacts(home, token) },
              { type: 'emit', data: 'Logged in as alice@example.com\r\n' },
              { type: 'exit', code: 0 },
            ],
          },
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

    const sub = mgr.submitRedirectUrl(
      started.id,
      'https://platform.claude.com/oauth/code/callback?code=redirect-code-2&state=redirect-state-2',
    )
    expect(sub.ok).toBe(true)

    expect(await waitPhase(mgr, started.id, ['succeeded', 'failed'])).toBe('succeeded')
    expect(instances[0].writes).toEqual(['redirect-code-2', '\r'])
    expect(agentsApi.posts).toEqual([{ userId: 'alice', provider: 'CLAUDE', payload: claudePayload(artifacts) }])
  })

  it('writes the Claude authorization code and submit Enter as distinct PTY writes before finalizing', async () => {
    const token = 'sk-ant-oat01-SeparateEnterToken1234567890_-abcXYZ'
    const artifacts = claudeArtifacts(token)
    const { deps: d, instances } = deps(() => [
      { type: 'emit', data: 'Open https://claude.com/cai/oauth/authorize?code=true\r\nPaste code here if prompted >' },
      {
        type: 'expectStdin',
        match: (i) => i === 'bulk-paste-sensitive-code',
        then: [
          {
            type: 'expectStdin',
            match: (i) => i === '\r',
            then: [
              { type: 'effect', run: () => writeClaudeArtifacts(home, token) },
              { type: 'emit', data: 'Credential file was updated.\r\n' },
            ],
          },
        ],
      },
    ])
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')
    await tick()

    expect(mgr.submitRedirectUrl(started.id, 'bulk-paste-sensitive-code').ok).toBe(true)

    expect(await waitPhase(mgr, started.id, ['succeeded', 'failed'], 1000)).toBe('succeeded')
    expect(instances[0].writes).toEqual(['bulk-paste-sensitive-code', '\r'])
    expect(agentsApi.posts).toEqual([{ userId: 'alice', provider: 'CLAUDE', payload: claudePayload(artifacts) }])
  })

  it('waits for the Claude code prompt before writing the extracted authorization code', async () => {
    const writes: string[] = []
    const dataCbs: Array<(chunk: string) => void> = []
    const exitCbs: Array<(info: { exitCode: number }) => void> = []
    const proc = {
      onData(cb: (chunk: string) => void) {
        dataCbs.push(cb)
      },
      onExit(cb: (info: { exitCode: number }) => void) {
        exitCbs.push(cb)
      },
      write(data: string) {
        writes.push(data)
      },
      kill() {
        // no-op
      },
    }
    const mgr = new SessionManager({
      spawner: () => proc,
      agentsApi,
      lease: new NoopLeaseLock(),
      paths: { home, codexHome },
      logger: createLogger(),
      ttlMs: 60_000,
    })
    const started = mgr.start('claude', 'alice')
    dataCbs.forEach((cb) => cb('Visit https://claude.com/cai/oauth/authorize?code=true&state=state-1\r\n'))
    await tick()

    const sub = mgr.submitRedirectUrl(
      started.id,
      'https://platform.claude.com/oauth/code/callback?code=queued-code-123&state=callback-state-456',
    )
    expect(sub.ok).toBe(true)
    expect(writes).toEqual([])
    expect(mgr.status(started.id)!.needsRedirectUrl).toBe(false)
    expect(mgr.submitRedirectUrl(started.id, 'other-code').ok).toBe(false)

    dataCbs.forEach((cb) => cb('Paste code here if prompted >'))
    await waitForWrites(() => writes, 2)

    expect(writes).toEqual(['queued-code-123', '\r'])
    expect(exitCbs.length).toBe(1)
    expect(mgr.cancel(started.id).ok).toBe(true)
  })

  it('writes Claude authorization codes to the live PTY and fails if the handle is missing', async () => {
    const { deps: liveDeps, instances } = deps(() => [
      { type: 'emit', data: 'Visit https://claude.ai/oauth/authorize?code=1\r\nPaste code here if prompted >' },
      { type: 'expectStdin', match: () => true, then: [] },
    ])
    const live = new LoginSession('claude', liveDeps)
    live.start('alice')
    await tick()

    expect(
      live.submitRedirectUrl('https://platform.claude.com/oauth/code/callback?code=live-code-123&state=state-1').ok,
    ).toBe(true)
    expect(await waitForWrites(() => instances[0].writes, 2)).toEqual(['live-code-123', '\r'])
    live.cancel()

    const logs: LogEntry[] = []
    const { deps: missingDeps } = deps(
      () => [{ type: 'emit', data: 'Visit https://claude.ai/oauth/authorize?code=1\r\nPaste code here if prompted >' }],
      new NoopLeaseLock(),
      memoryLogger(logs),
    )
    const missing = new LoginSession('claude', missingDeps)
    missing.start('alice')
    await tick()
    ;(missing as unknown as { proc?: undefined }).proc = undefined

    const result = missing.submitRedirectUrl('https://platform.claude.com/oauth/code/callback?code=lost-code-456')

    expect(result).toEqual({
      ok: false,
      error: 'cannot submit Claude authorization code: PTY process handle missing',
    })
    expect(missing.status().phase).toBe('failed')
    expect(missing.status().error).toBe('cannot submit Claude authorization code: PTY process handle missing')
    expect(logs.some((entry) => entry.level === 'error' && entry.msg.includes('PTY process handle missing'))).toBe(true)
  })

  it('fails Claude capture when only a stdout OAuth token is available', async () => {
    const token = 'sk-ant-oat01-StdoutOnlyToken1234567890_-abcXYZ'
    const { deps: d } = deps(() => [
      {
        type: 'emit',
        data: 'Use the url below\r\nhttps://claude.com/cai/oauth/authorize?code=true\r\nPaste code here if prompted >',
      },
      {
        type: 'expectStdin',
        match: () => true,
        then: [
          {
            type: 'emit',
            data: `Long-lived authentication token created successfully!\r\nYour OAuth token (valid for 1 year): ${token}\r\n`,
          },
          { type: 'exit', code: 0 },
        ],
      },
    ])
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')
    await tick()
    expect(mgr.submitRedirectUrl(started.id, 'paste-code-xyz').ok).toBe(true)
    expect(await waitPhase(mgr, started.id, ['succeeded', 'failed'])).toBe('failed')
    expect(mgr.status(started.id)!.error).toMatch(/credentials\.json was not written/)
    expect(agentsApi.posts).toEqual([])
  })

  it('does not finalize Claude from a stdout OAuth token while waiting for credential files', async () => {
    const token = 'sk-ant-oat01-NoExitToken1234567890_-abcXYZ'
    const { deps: d } = deps(() => [
      { type: 'emit', data: 'Open https://claude.com/cai/oauth/authorize?code=true\r\nPaste code here if prompted >' },
      {
        type: 'expectStdin',
        match: () => true,
        then: [{ type: 'emit', data: `CLAUDE_CODE_OAUTH_TOKEN=${token}\r\n` }],
      },
    ])
    d.redirectTimeoutMs = 20
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')
    await tick()

    expect(mgr.submitRedirectUrl(started.id, 'paste-code-xyz').ok).toBe(true)

    expect(await waitPhase(mgr, started.id, ['succeeded', 'failed'], 500)).toBe('failed')
    expect(mgr.status(started.id)!.error).toMatch(/timed out waiting for Claude credential capture/)
    expect(agentsApi.posts).toEqual([])
  })

  it('requires credential files even when an OSC8-linked OAuth token is printed after paste-back', async () => {
    const token = 'sk-ant-oat01-Osc8SessionToken1234567890_-abcXYZ'
    const { deps: d } = deps(() => [
      { type: 'emit', data: 'Open https://claude.com/cai/oauth/authorize?code=true\r\nPaste code here if prompted >' },
      {
        type: 'expectStdin',
        match: () => true,
        then: [{ type: 'emit', data: `Token: \x1b]8;;${token}\x07copy token\x1b]8;;\x07\r\n` }],
      },
    ])
    d.redirectTimeoutMs = 20
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')
    await tick()

    expect(mgr.submitRedirectUrl(started.id, 'paste-code-xyz').ok).toBe(true)

    expect(await waitPhase(mgr, started.id, ['succeeded', 'failed'], 500)).toBe('failed')
    expect(agentsApi.posts).toEqual([])
  })

  it('finalizes Claude from credential files when interactive login prints no token and stays open', async () => {
    const token = 'sk-ant-oat01-CredentialsFileToken1234567890_-abcXYZ'
    const artifacts = claudeArtifacts(token)
    const { deps: d } = deps(() => [
      { type: 'emit', data: 'Open https://claude.com/cai/oauth/authorize?code=true\r\nPaste code here if prompted >' },
      {
        type: 'expectStdin',
        match: () => true,
        then: [
          {
            type: 'emit',
            data: 'Credential file was updated.\r\n',
          },
        ],
      },
    ])
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')
    await tick()

    writeClaudeArtifacts(home, token)
    expect(mgr.submitRedirectUrl(started.id, 'paste-code-xyz').ok).toBe(true)

    expect(await waitPhase(mgr, started.id, ['succeeded', 'failed'], 500)).toBe('succeeded')
    expect(agentsApi.posts).toEqual([{ userId: 'alice', provider: 'CLAUDE', payload: claudePayload(artifacts) }])
  })

  it('redacts Claude login output and records lifecycle log decisions without logging the token', async () => {
    const token = 'sk-ant-oat01-LogRedactionToken1234567890_-abcXYZ'
    const artifacts = claudeArtifacts(token)
    const logs: LogEntry[] = []
    const { deps: d } = deps(
      () => [
        {
          type: 'emit',
          data: 'Open https://claude.com/cai/oauth/authorize?code=true\r\nPaste code here if prompted >',
        },
        {
          type: 'expectStdin',
          match: () => true,
          then: [
            { type: 'effect', run: () => writeClaudeArtifacts(home, token) },
            { type: 'emit', data: `Your OAuth token: ${token}\r\nLogged in as alice@example.com\r\n` },
          ],
        },
      ],
      new NoopLeaseLock(),
      memoryLogger(logs),
    )
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')
    await tick()

    expect(mgr.submitRedirectUrl(started.id, 'paste-code-xyz').ok).toBe(true)
    expect(await waitPhase(mgr, started.id, ['succeeded', 'failed'], 500)).toBe('succeeded')

    const serialized = JSON.stringify(logs)
    expect(serialized).not.toContain(token)
    expect(serialized).toContain('«redacted»')
    expect(logs.some((entry) => entry.msg === 'Claude login output scanned')).toBe(true)
    expect(logs.some((entry) => entry.msg === 'Claude OAuth token parse attempt')).toBe(true)
    expect(logs.some((entry) => entry.msg === 'login session phase transition')).toBe(true)
    expect(agentsApi.posts[0]).toEqual({ userId: 'alice', provider: 'CLAUDE', payload: claudePayload(artifacts) })
  })

  it('fails Claude after a bounded post-redirect timeout with redacted output context', async () => {
    const { deps: d } = deps(() => [
      { type: 'emit', data: 'Open https://claude.com/cai/oauth/authorize?code=true\r\nPaste code here if prompted >' },
      {
        type: 'expectStdin',
        match: () => true,
        then: [{ type: 'emit', data: 'still waiting for opaque-secret-timeout-value-12345678901234567890\r\n' }],
      },
    ])
    d.redirectTimeoutMs = 20
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')
    await tick()

    expect(mgr.submitRedirectUrl(started.id, 'paste-code-xyz').ok).toBe(true)

    expect(await waitPhase(mgr, started.id, ['succeeded', 'failed'], 500)).toBe('failed')
    expect(mgr.status(started.id)!.error).toMatch(/timed out waiting for Claude credential capture/)
    expect(mgr.status(started.id)!.error).toContain('«redacted»')
    expect(agentsApi.posts).toEqual([])
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
    expect(agentsApi.posts).toEqual([
      { userId: 'bob', provider: 'CODEX', payload: { auth_json: '{"tokens":{}}', config_toml: 'model="x"\n' } },
    ])
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
    const { deps: d, instances } = deps(() => [
      { type: 'emit', data: 'Visit https://claude.ai/oauth/authorize?code=1\r\nPaste code here if prompted >' },
      { type: 'expectStdin', match: () => true, then: [] },
    ])
    const mgr = new SessionManager(d)
    const started = mgr.start('claude', 'alice')
    await tick()
    expect(mgr.submitRedirectUrl(started.id, '   ').ok).toBe(false)
    // Claude's manual flow returns a bare code, not a URL — it must be accepted.
    expect(mgr.submitRedirectUrl(started.id, 'ABCD-1234-token').ok).toBe(true)
    expect(await waitForWrites(() => instances[0].writes, 2)).toEqual(['ABCD-1234-token', '\r'])
    expect(mgr.cancel(started.id).ok).toBe(true)
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

  it('runs Claude and Codex sessions concurrently', async () => {
    const { deps: d, instances } = deps(() => [
      { type: 'emit', data: 'Visit https://claude.ai/oauth/authorize?code=1\r\n' },
    ])
    const mgr = new SessionManager(d)
    const claude = mgr.start('claude', 'alice')
    await tick()
    // A different provider starts its own independent session, not a 409.
    const codex = mgr.start('codex', 'alice')
    expect(codex.id).not.toBe(claude.id)
    expect(codex.provider).toBe('codex')
    expect(instances.length).toBe(2)
    // Both remain individually addressable by id.
    expect(mgr.status(claude.id)?.provider).toBe('claude')
    expect(mgr.status(codex.id)?.provider).toBe('codex')
  })

  it('re-attaches to an in-progress session of the same provider instead of refusing', async () => {
    const { deps: d, instances } = deps(() => [
      { type: 'emit', data: 'Visit https://claude.ai/oauth/authorize?code=1\r\n' },
      { type: 'expectStdin', match: () => true, then: [] },
    ])
    const mgr = new SessionManager(d)
    const first = mgr.start('claude', 'alice')
    await tick()
    // Clicking "Start login" again must resume the existing flow, not 409.
    const again = mgr.start('claude', 'alice')
    expect(again.id).toBe(first.id)
    expect(again.phase).toBe('awaiting_url')
    expect(again.authorizeUrl).toContain('claude.ai/oauth/authorize')
    // No second CLI child is spawned.
    expect(instances.length).toBe(1)
  })

  it('surfaces an agents-api ingest failure as a failed session', async () => {
    const failingAgentsApi = fakeAgentsApi(new Error('agents-api credential ingest failed: 503 unavailable'))
    const token = 'sk-ant-oat01-FailureToken1234567890'

    const { spawner } = fakeSpawner(() => [
      { type: 'emit', data: 'Visit https://claude.ai/oauth/authorize?code=1\r\nPaste code here if prompted >' },
      {
        type: 'expectStdin',
        match: (i) => i === 'ingest-failure-code',
        then: [
          {
            type: 'expectStdin',
            match: (i) => i === '\r',
            then: [
              { type: 'effect', run: () => writeClaudeArtifacts(home, token) },
              { type: 'emit', data: 'Logged in as alice@example.com\r\n' },
            ],
          },
        ],
      },
    ])
    const mgr = new SessionManager({
      spawner,
      agentsApi: failingAgentsApi,
      lease: new NoopLeaseLock(),
      paths: { home, codexHome },
      logger: createLogger(),
      ttlMs: 60_000,
    })
    const started = mgr.start('claude', 'alice')
    await tick()
    mgr.submitRedirectUrl(started.id, 'https://platform.claude.com/oauth/code/callback?code=ingest-failure-code')
    expect(await waitPhase(mgr, started.id, ['failed', 'succeeded'])).toBe('failed')
    expect(mgr.status(started.id)!.error).toMatch(/agents-api credential ingest failed/)
  })

  it('returns undefined status for an unknown id', () => {
    const { deps: d } = deps(() => [])
    const mgr = new SessionManager(d)
    expect(mgr.status('nope')).toBeUndefined()
    expect(mgr.cancel('nope').ok).toBe(false)
    expect(mgr.submitRedirectUrl('nope', 'https://x').ok).toBe(false)
  })
})
