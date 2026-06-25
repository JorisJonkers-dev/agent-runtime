import { randomUUID } from 'node:crypto'
import type { Logger } from '../shared/log.js'
import { redactString } from '../shared/redact.js'
import type { Provider, SessionPhase, SessionStatus } from '../shared/types.js'
import { isTerminal } from '../shared/types.js'
import { capture, readClaudeCredentialsToken, type CredentialPaths } from './credentials.js'
import {
  detectClaudeCodePrompt,
  detectFailure,
  detectSuccess,
  parseClaude,
  parseClaudeRedirectCode,
  parseClaudeToken,
  parseCodex,
} from './parse.js'
import type { PtyProcess, PtySpawner } from './pty.js'
import type { LeaseLock } from './lease.js'
import { payloadForApi, providerForApi, type AgentsApiClient } from './agentsApiClient.js'

export interface SessionDeps {
  spawner: PtySpawner
  agentsApi: Pick<AgentsApiClient, 'postCredentials'>
  lease: LeaseLock
  paths: CredentialPaths
  logger: Logger
  ttlMs: number
  redirectTimeoutMs?: number
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
const DEFAULT_REDIRECT_TIMEOUT_MS = 60_000
const CLAUDE_CREDENTIAL_POLL_MS = 250
const LOG_LINE_LIMIT = 512
const FAILURE_TAIL_LIMIT = 2_000

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
  private redirectTimer?: NodeJS.Timeout
  private credentialProbeTimer?: NodeJS.Timeout
  private redirectSubmitted = false
  private pendingClaudeCode?: string
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

  private setPhase(phase: SessionPhase, message?: string, reason = 'unspecified'): void {
    const previous = this.phase
    this.phase = phase
    if (message !== undefined) {
      this.message = message
    }
    this.touch()
    if (previous !== phase) {
      this.deps.logger.info('login session phase transition', {
        sessionId: this.id,
        provider: this.provider,
        from: previous,
        to: phase,
        reason,
      })
    }
  }

  status(): SessionStatus {
    return {
      id: this.id,
      provider: this.provider,
      phase: this.phase,
      authorizeUrl: this.authorizeUrl,
      deviceCode: this.deviceCode,
      verificationUrl: this.verificationUrl,
      needsRedirectUrl:
        this.provider === 'claude' && this.phase === 'awaiting_url' && !this.redirectSubmitted && !this.pendingClaudeCode,
      message: this.message,
      error: this.error,
      updatedAt: this.updatedAt,
    }
  }

  /** Spawn the CLI and begin parsing its PTY output. */
  start(updatedBy: string): void {
    this.updatedBy = updatedBy
    const cmd = (this.deps.command ?? defaultCommand)(this.provider)
    this.deps.logger.info('login session starting', {
      sessionId: this.id,
      provider: this.provider,
      updatedBy,
      command: cmd.file,
      args: cmd.args,
    })
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

  private outputTail(limit = FAILURE_TAIL_LIMIT): string {
    return redactString(this.buffer.slice(Math.max(0, this.buffer.length - limit)))
  }

  private scanLines(chunk: string): string[] {
    const lines = chunk.split(/\r?\n/).map((line) => redactString(line.trim()))
    return lines.filter((line) => line.length > 0).map((line) => line.slice(0, LOG_LINE_LIMIT))
  }

  private logClaudeTokenParseAttempt(source: string, matched: boolean, reason: string): void {
    this.deps.logger.info('Claude setup-token parse attempt', {
      sessionId: this.id,
      source,
      matched,
      reason,
    })
  }

  private onData(chunk: string, updatedBy: string): void {
    this.append(chunk)

    if (isTerminal(this.phase) || this.phase === 'finalizing') {
      return
    }

    if (this.provider === 'claude') {
      this.deps.logger.info('Claude setup-token output scanned', {
        sessionId: this.id,
        phase: this.phase,
        bytes: chunk.length,
        lines: this.scanLines(chunk),
      })
    }

    if (detectFailure(this.buffer) && (this.phase === 'starting' || this.redirectSubmitted)) {
      this.fail('CLI reported a login failure')
      return
    }

    if (this.provider === 'claude') {
      if (!this.authorizeUrl) {
        const { authorizeUrl } = parseClaude(this.buffer)
        if (authorizeUrl) {
          this.authorizeUrl = authorizeUrl
          this.setPhase(
            'awaiting_url',
            'Open the authorize URL, approve, then paste the authorization code.',
            'Claude authorize URL detected',
          )
        }
      }
      this.maybeSubmitPendingClaudeCode()
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
          this.setPhase('awaiting_device', 'Enter the device code at the verification URL.', 'Codex device code detected')
        }
      }
    }

    const claudeTokenCaptured = this.provider === 'claude' && this.redirectSubmitted ? parseClaudeToken(this.buffer) : undefined
    if (this.provider === 'claude' && this.redirectSubmitted) {
      this.logClaudeTokenParseAttempt('pty-output', claudeTokenCaptured !== undefined, 'PTY output received after redirect')
    }
    if (claudeTokenCaptured) {
      void this.finalize(updatedBy, claudeTokenCaptured, 'Claude OAuth token parsed from PTY output')
      return
    }
    if (detectSuccess(this.provider, this.buffer)) {
      void this.finalize(updatedBy, undefined, 'CLI success output detected')
      return
    }
    if (this.provider === 'claude' && this.redirectSubmitted) {
      void this.probeClaudeCredentialFile('PTY output received after redirect')
    }
  }

  private maybeSubmitPendingClaudeCode(): void {
    if (!this.pendingClaudeCode || this.redirectSubmitted || !detectClaudeCodePrompt(this.buffer)) {
      return
    }
    if (!this.proc) {
      this.deps.logger.error('Claude authorization code submission failed: PTY process handle missing', {
        sessionId: this.id,
        provider: this.provider,
      })
      this.fail('cannot submit Claude authorization code: PTY process handle missing')
      return
    }
    const code = this.pendingClaudeCode
    this.pendingClaudeCode = undefined
    this.redirectSubmitted = true
    this.deps.logger.info('Claude authorization code submitted to PTY', {
      sessionId: this.id,
      provider: this.provider,
      codeBytes: code.length,
    })
    this.proc.write(`${code}\r`)
    // Stay in awaiting_url so the success line emitted by the CLI after the
    // paste-back still drives finalize(); only finalize() flips to finalizing.
    this.message = 'Submitted the authorization code; completing login…'
    this.touch()
    this.startRedirectTimeout()
    void this.probeClaudeCredentialFile('redirect submitted')
  }

  private onExit(exitCode: number): void {
    if (isTerminal(this.phase) || this.phase === 'finalizing') {
      return
    }
    this.deps.logger.info('login session CLI exited', {
      sessionId: this.id,
      provider: this.provider,
      exitCode,
      redirectSubmitted: this.redirectSubmitted,
      phase: this.phase,
    })
    // A clean exit after the operator submitted the code (Claude setup-token) or
    // after the device prompt was shown and approved (Codex) means the CLI wrote
    // its credentials — capture them even if no success line was matched.
    const completed =
      detectSuccess(this.provider, this.buffer) ||
      this.redirectSubmitted ||
      (this.provider === 'codex' && this.phase === 'awaiting_device')
    if (exitCode === 0 && completed) {
      const claudeToken = this.provider === 'claude' ? parseClaudeToken(this.buffer) : undefined
      if (this.provider === 'claude') {
        this.logClaudeTokenParseAttempt('pty-output', claudeToken !== undefined, 'CLI exited cleanly after login flow')
      }
      void this.finalize(this.updatedBy, claudeToken, 'CLI exited cleanly after login flow')
      return
    }
    this.fail(`CLI exited with code ${exitCode} before login completed`)
  }

  /** Claude only: feed the post-approval authorization code to the child's stdin. */
  submitRedirectUrl(url: string): { ok: boolean; error?: string } {
    if (this.provider !== 'claude') {
      return { ok: false, error: 'redirect URL only applies to the Claude flow' }
    }
    if (this.phase !== 'awaiting_url') {
      return { ok: false, error: `session is not awaiting a redirect URL (phase=${this.phase})` }
    }
    if (this.redirectSubmitted || this.pendingClaudeCode) {
      return { ok: false, error: 'authorization code already submitted' }
    }
    const parsed = parseClaudeRedirectCode(url)
    if (!parsed) {
      return { ok: false, error: 'authorization code is required' }
    }
    if (!this.proc) {
      const reason = 'cannot submit Claude authorization code: PTY process handle missing'
      this.deps.logger.error('Claude redirect submission failed: PTY process handle missing', {
        sessionId: this.id,
        provider: this.provider,
        inputBytes: url.trim().length,
        codeBytes: parsed.code.length,
        inputSource: parsed.source,
      })
      this.fail(reason)
      return { ok: false, error: reason }
    }
    this.deps.logger.info('Claude redirect submission received', {
      sessionId: this.id,
      provider: this.provider,
      inputBytes: url.trim().length,
      codeBytes: parsed.code.length,
      inputSource: parsed.source,
      statePresent: parsed.state !== undefined,
    })
    this.pendingClaudeCode = parsed.code
    this.message = detectClaudeCodePrompt(this.buffer)
      ? 'Submitted the authorization code; completing login…'
      : 'Authorization code received; waiting for Claude prompt…'
    this.touch()
    this.maybeSubmitPendingClaudeCode()
    return { ok: true }
  }

  private startRedirectTimeout(): void {
    const timeoutMs = this.deps.redirectTimeoutMs ?? DEFAULT_REDIRECT_TIMEOUT_MS
    if (this.redirectTimer) {
      clearTimeout(this.redirectTimer)
    }
    this.redirectTimer = setTimeout(() => {
      if (isTerminal(this.phase) || this.phase === 'finalizing') {
        return
      }
      this.fail(`timed out waiting for Claude credential capture after redirect submission; output_tail=${this.outputTail()}`)
    }, timeoutMs)
    if (typeof this.redirectTimer.unref === 'function') {
      this.redirectTimer.unref()
    }
    this.scheduleClaudeCredentialProbe(Math.min(CLAUDE_CREDENTIAL_POLL_MS, Math.max(10, Math.floor(timeoutMs / 2))))
  }

  private scheduleClaudeCredentialProbe(delayMs: number): void {
    if (this.provider !== 'claude' || !this.redirectSubmitted || isTerminal(this.phase) || this.phase === 'finalizing') {
      return
    }
    if (this.credentialProbeTimer) {
      clearTimeout(this.credentialProbeTimer)
      this.credentialProbeTimer = undefined
    }
    this.credentialProbeTimer = setTimeout(() => {
      void this.probeClaudeCredentialFile('credential file poll')
    }, delayMs)
    if (typeof this.credentialProbeTimer.unref === 'function') {
      this.credentialProbeTimer.unref()
    }
  }

  private async probeClaudeCredentialFile(reason: string): Promise<void> {
    if (this.provider !== 'claude' || !this.redirectSubmitted || isTerminal(this.phase) || this.phase === 'finalizing') {
      return
    }
    try {
      const token = await readClaudeCredentialsToken(this.deps.paths)
      this.deps.logger.info('Claude credential file parse attempt', {
        sessionId: this.id,
        source: '$HOME/.claude/.credentials.json',
        matched: token !== undefined,
        reason,
      })
      if (token) {
        await this.finalize(this.updatedBy, token, 'Claude OAuth token parsed from credentials file')
        return
      }
    } catch (err) {
      this.deps.logger.warn('Claude credential file parse attempt failed', {
        sessionId: this.id,
        reason,
        error: err instanceof Error ? err.message : String(err),
      })
    }
    this.scheduleClaudeCredentialProbe(CLAUDE_CREDENTIAL_POLL_MS)
  }

  private clearTimer(): void {
    if (this.timer) {
      clearTimeout(this.timer)
      this.timer = undefined
    }
    if (this.redirectTimer) {
      clearTimeout(this.redirectTimer)
      this.redirectTimer = undefined
    }
    if (this.credentialProbeTimer) {
      clearTimeout(this.credentialProbeTimer)
      this.credentialProbeTimer = undefined
    }
  }

  private async finalize(updatedBy: string, claudeTokenOverride?: string, reason = 'completion detected'): Promise<void> {
    if (this.phase === 'finalizing' || isTerminal(this.phase)) {
      return
    }
    this.setPhase('finalizing', 'Capturing credentials…', reason)
    try {
      // For Claude the canonical credential is the OAuth token setup-token
      // prints to stdout, not a file; parse it from the accumulated buffer.
      const claudeToken = this.provider === 'claude' ? (claudeTokenOverride ?? parseClaudeToken(this.buffer)) : undefined
      this.deps.logger.info('login session finalize starting', {
        sessionId: this.id,
        provider: this.provider,
        reason,
        claudeTokenMatched: this.provider === 'claude' ? claudeToken !== undefined : undefined,
      })
      const bundle = await capture(this.provider, this.deps.paths, updatedBy, this.now, claudeToken)
      this.deps.logger.info('login session credential capture completed', {
        sessionId: this.id,
        provider: this.provider,
        dataKeys: Object.keys(bundle.data),
      })
      const provider = providerForApi(this.provider)
      const payload = payloadForApi(this.provider, bundle)
      await this.deps.lease.withLock(async () => {
        this.deps.logger.info('login session posting credentials to agents-api', {
          sessionId: this.id,
          provider: this.provider,
          apiProvider: provider,
          payloadKeys: Object.keys(payload),
        })
        await this.deps.agentsApi.postCredentials({ userId: updatedBy, provider, payload })
      })
      this.clearTimer()
      this.proc?.kill()
      this.setPhase('succeeded', 'Credentials written.', 'credentials captured and posted to agents-api')
      this.deps.logger.info('login session succeeded', { sessionId: this.id, provider: this.provider })
    } catch (err) {
      const error = redactString(err instanceof Error ? err.message : 'failed to finalize login')
      this.deps.logger.warn('login session finalize failed', {
        sessionId: this.id,
        provider: this.provider,
        reason,
        error,
      })
      this.fail(error)
    }
  }

  private fail(reason: string): void {
    const safeReason = redactString(reason)
    this.clearTimer()
    this.error = safeReason
    this.setPhase('failed', undefined, safeReason)
    this.proc?.kill()
    this.deps.logger.warn('login session failed', { sessionId: this.id, provider: this.provider, reason: safeReason })
  }

  cancel(reason = 'cancelled by operator'): void {
    if (isTerminal(this.phase)) {
      return
    }
    this.clearTimer()
    this.message = reason
    this.setPhase('cancelled', undefined, reason)
    this.proc?.kill()
  }
}

/**
 * Holds at most one active session per provider. Claude and Codex run their
 * CLIs against separate HOME subtrees (`.claude` vs `CODEX_HOME`) and the
 * ingest write is serialized by the Lease, so the two providers can log in
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
