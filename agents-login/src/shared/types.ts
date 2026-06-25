// Shared domain types for both controller and worker.

export type Provider = 'claude' | 'codex'

/**
 * Login session lifecycle.
 *  starting     — child process spawned, no prompt parsed yet
 *  awaiting_url — Claude only: authorize URL emitted, waiting for the operator
 *                 to paste the post-approval redirect URL back
 *  awaiting_device — Codex only: device code emitted, operator approves in the
 *                 browser; no paste-back needed
 *  finalizing   — login accepted by the CLI, capturing + writing credentials
 *  succeeded    — credentials posted to agents-api
 *  failed       — error (parse failure, child crash, ingest failure…)
 *  cancelled    — operator cancelled or session timed out
 */
export type SessionPhase =
  | 'starting'
  | 'awaiting_url'
  | 'awaiting_device'
  | 'finalizing'
  | 'succeeded'
  | 'failed'
  | 'cancelled'

export interface SessionStatus {
  id: string
  provider: Provider
  phase: SessionPhase
  // Claude: the authorize URL to open in a browser.
  authorizeUrl?: string
  // Codex: the device code + verification URL to display.
  deviceCode?: string
  verificationUrl?: string
  // Whether the UI should present the redirect-URL paste-back input.
  needsRedirectUrl: boolean
  message?: string
  error?: string
  updatedAt: string
}

export const TERMINAL_PHASES: ReadonlySet<SessionPhase> = new Set<SessionPhase>(['succeeded', 'failed', 'cancelled'])

export function isTerminal(phase: SessionPhase): boolean {
  return TERMINAL_PHASES.has(phase)
}
