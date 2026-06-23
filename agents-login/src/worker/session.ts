import { randomUUID } from 'node:crypto'
import type { Logger } from '../shared/log.js'
import type { Provider, SessionPhase, SessionStatus } from '../shared/types.js'
import { isTerminal } from '../shared/types.js'
import { capture, type CredentialPaths } from './credentials.js'
import { detectFailure, detectSuccess, parseClaude, parseClaudeToken, parseCodex } from './parse.js'
import type { PtyProcess, PtySpawner } from './pty.js'
import type { LeaseLock } from './lease.js'
import { CasConflictError, type VaultClient } from './vaultClient.js'

export interface SessionDeps {
  spawner: PtySpawner
  vault: VaultClient
  lease: LeaseLock
  paths: CredentialPaths
  vaultPaths: { claude: string; codex: string }
  logger: Logger
  ttlMs: number
  now?: () => Date
  // override the launched command for tests / alternate CLIs
  command?: (provider: Provider) => { file: string; args: string[] }
}

function defaultCommand(provider: Provider): { file: string; args: string[] } {
  // `claude /login` is an interactive REPL slash command ("not available in this
  // environment"); `claude setup-token` is the headless OAuth flow that prints an
  // authorize URL and takes a pasted code back, writing the standard credentials.
  return provider === 'claude'
    ? { file: 'claude', args: ['setup-token'] }
    : { file: 'codex', args: ['login', '--device'] }
}

const MAX_BUFFER = 256 * 1024

export class LoginSession {
  readonly id = randomUUID()
  readonly provider: Provider
  private phase: SessionPhase = 'starting'
  private authorizeUrl?: string
  private deviceCode?: string
  private verificationUrl?: string
  private message?: string
  private error?: string
  private updatedAt: string
  private buffer = ''
  private proc?: PtyProcess
  private timer?: NodeJS.Timeout
  private redirectSubmitted = false
  private updatedBy = 'unknown'

  constructor(
    provider: Provider,
    private readonly deps: SessionDeps,
  ) {
    this.provider = provider
    this.updatedAt = this.nowIso()
  }

  private get now(): () => Date {
    return this.deps.now ?? (() => new Date())
  }

  private nowIso(): string {
    return this.now().toISOString()
  }

  private touch(): void {
    this.updatedAt = this.nowIso()
  }

  private setPhase(phase: SessionPhase, message?: string): void {
    this.phase = phase
    if (message !== undefined) {
      this.message = message
    }
    this.touch()
  }

  status(): SessionStatus {
    return {
      id: this.id,
      provider: this.provider,
      phase: this.phase,
      authorizeUrl: this.authorizeUrl,
      deviceCode: this.deviceCode,
      verificationUrl: this.verificationUrl,
      needsRedirectUrl: this.provider === 'claude' && this.phase === 'awaiting_url' && !this.redirectSubmitted,
      message: this.message,
      error: this.error,
      updatedAt: this.updatedAt,
    }
  }

  /** Spawn the CLI and begin parsing its PTY output. */
  start(updatedBy: string): void {
    this.updatedBy = updatedBy
    const cmd = (this.deps.command ?? defaultCommand)(this.provider)
    const env = {
      ...process.env,
      HOME: this.deps.paths.home,
      CODEX_HOME: this.deps.paths.codexHome,
    }
    this.proc = this.deps.spawner(cmd.file, cmd.args, {
      cwd: this.deps.paths.home,
      env,
      // Wide enough that the ~350-char authorize URL is emitted on one line;
      // the default 80 cols wraps it and breaks URL extraction.
      cols: 400,
      rows: 50,
    })
    this.proc.onData((chunk) => this.onData(chunk, updatedBy))
    this.proc.onExit((info) => this.onExit(info.exitCode))
    this.timer = setTimeout(() => this.cancel('timed out'), this.deps.ttlMs)
    if (typeof this.timer.unref === 'function') {
      this.timer.unref()
    }
  }

  private append(chunk: string): void {
    this.buffer += chunk
    if (this.buffer.length > MAX_BUFFER) {
      this.buffer = this.buffer.slice(this.buffer.length - MAX_BUFFER)
    }
  }

  private onData(chunk: string, updatedBy: string): void {
    this.append(chunk)

    if (isTerminal(this.phase) || this.phase === 'finalizing') {
      return
    }

    if (detectFailure(this.buffer) && this.phase === 'starting') {
      this.fail('CLI reported a login failure')
      return
    }

    if (this.provider === 'claude') {
      if (!this.authorizeUrl) {
        const { authorizeUrl } = parseClaude(this.buffer)
        if (authorizeUrl) {
          this.authorizeUrl = authorizeUrl
          this.setPhase('awaiting_url', 'Open the authorize URL, approve, then paste the authorization code.')
        }
      }
    } else {
      if (!this.deviceCode || !this.verificationUrl) {
        const parsed = parseCodex(this.buffer)
        if (parsed.deviceCode) {
          this.deviceCode = parsed.deviceCode
        }
        if (parsed.verificationUrl) {
          this.verificationUrl = parsed.verificationUrl
        }
        if (this.deviceCode && this.phase === 'starting') {
          this.setPhase('awaiting_device', 'Enter the device code at the verification URL.')
        }
      }
    }

    if (detectSuccess(this.provider, this.buffer)) {
      void this.finalize(updatedBy)
    }
  }

  private onExit(exitCode: number): void {
    if (isTerminal(this.phase) || this.phase === 'finalizing') {
      return
    }
    // A clean exit after the operator submitted the code (Claude setup-token) or
    // after the device prompt was shown and approved (Codex) means the CLI wrote
    // its credentials — capture them even if no success line was matched.
    const completed =
      detectSuccess(this.provider, this.buffer) ||
      this.redirectSubmitted ||
      (this.provider === 'codex' && this.phase === 'awaiting_device')
    if (exitCode === 0 && completed) {
      void this.finalize(this.updatedBy)
      return
    }
    this.fail(`CLI exited with code ${exitCode} before login completed`)
  }

  /** Claude only: feed the post-approval redirect URL to the child's stdin. */
  submitRedirectUrl(url: string): { ok: boolean; error?: string } {
    if (this.provider !== 'claude') {
      return { ok: false, error: 'redirect URL only applies to the Claude flow' }
    }
    if (this.phase !== 'awaiting_url') {
      return { ok: false, error: `session is not awaiting a redirect URL (phase=${this.phase})` }
    }
    if (this.redirectSubmitted) {
      return { ok: false, error: 'authorization code already submitted' }
    }
    const trimmed = url.trim()
    // setup-token expects the authorization code copied from the approval page
    // (not a URL); accept any non-empty value and let the CLI validate it.
    if (trimmed.length === 0) {
      return { ok: false, error: 'authorization code is required' }
    }
    this.redirectSubmitted = true
    this.proc?.write(trimmed + '\r')
    // Stay in awaiting_url so the success line emitted by the CLI after the
    // paste-back still drives finalize(); only finalize() flips to finalizing.
    this.message = 'Submitted the authorization code; completing login…'
    this.touch()
    return { ok: true }
  }

  private clearTimer(): void {
    if (this.timer) {
      clearTimeout(this.timer)
      this.timer = undefined
    }
  }

  private async finalize(updatedBy: string): Promise<void> {
    if (this.phase === 'finalizing' || isTerminal(this.phase)) {
      return
    }
    this.setPhase('finalizing', 'Capturing credentials…')
    try {
      // For Claude the canonical credential is the OAuth token setup-token
      // prints to stdout, not a file; parse it from the accumulated buffer.
      const claudeToken = this.provider === 'claude' ? parseClaudeToken(this.buffer) : undefined
      const bundle = await capture(this.provider, this.deps.paths, updatedBy, this.now, claudeToken)
      const path = this.provider === 'claude' ? this.deps.vaultPaths.claude : this.deps.vaultPaths.codex
      await this.deps.lease.withLock(async () => {
        await this.deps.vault.writeCas(path, bundle.data)
      })
      this.clearTimer()
      this.proc?.kill()
      this.setPhase('succeeded', 'Credentials written to Vault.')
      this.deps.logger.info('login session succeeded', { sessionId: this.id, provider: this.provider })
    } catch (err) {
      if (err instanceof CasConflictError) {
        this.fail(`Vault write conflict — another writer is active. ${err.message}`)
      } else {
        this.fail(err instanceof Error ? err.message : 'failed to finalize login')
      }
    }
  }

  private fail(reason: string): void {
    this.clearTimer()
    this.error = reason
    this.setPhase('failed')
    this.proc?.kill()
    this.deps.logger.warn('login session failed', { sessionId: this.id, provider: this.provider })
  }

  cancel(reason = 'cancelled by operator'): void {
    if (isTerminal(this.phase)) {
      return
    }
    this.clearTimer()
    this.message = reason
    this.setPhase('cancelled')
    this.proc?.kill()
  }
}

/**
 * Holds at most one active session per provider. Claude and Codex run their
 * CLIs against separate HOME subtrees (`.claude` vs `CODEX_HOME`) and the Vault
 * write is serialized by the Lease, so the two providers can log in
 * concurrently; a second start for a provider whose session is still live
 * re-attaches to it rather than spawning a duplicate.
 */
export class SessionManager {
  private readonly byProvider = new Map<Provider, LoginSession>()

  constructor(private readonly deps: SessionDeps) {}

  start(provider: Provider, updatedBy: string): SessionStatus {
    const existing = this.byProvider.get(provider)
    if (existing && !isTerminal(existing.status().phase)) {
      // Resume the in-progress login (its authorize URL / code prompt) instead
      // of starting a duplicate.
      return existing.status()
    }
    const session = new LoginSession(provider, this.deps)
    this.byProvider.set(provider, session)
    session.start(updatedBy)
    return session.status()
  }

  private find(id: string): LoginSession | undefined {
    for (const session of this.byProvider.values()) {
      if (session.id === id) {
        return session
      }
    }
    return undefined
  }

  status(id: string): SessionStatus | undefined {
    return this.find(id)?.status()
  }

  submitRedirectUrl(id: string, url: string): { ok: boolean; error?: string } {
    const session = this.find(id)
    if (!session) {
      return { ok: false, error: 'no matching session' }
    }
    return session.submitRedirectUrl(url)
  }

  cancel(id: string): { ok: boolean } {
    const session = this.find(id)
    if (session) {
      session.cancel()
      return { ok: true }
    }
    return { ok: false }
  }
}
