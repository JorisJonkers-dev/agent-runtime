import type { Provider } from '../shared/types.js'

// PTY output arrives with ANSI escape codes and is chunked arbitrarily, so the
// parsers run over a stripped, accumulated buffer.

// CSI escape sequences: ESC [ ... final-byte.
// eslint-disable-next-line no-control-regex
const ANSI_PATTERN = /\x1b\[[0-9;?]*[ -/]*[@-~]/g
// Remaining C0 control bytes except CR/LF/TAB, which carry layout meaning.
// eslint-disable-next-line no-control-regex
const OTHER_CONTROL = /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g

export function stripAnsi(input: string): string {
  return input.replace(ANSI_PATTERN, '').replace(OTHER_CONTROL, '')
}

// `claude setup-token` prints an OAuth authorize URL. The host has moved across
// claude.ai / claude.com / platform.claude.com / console.anthropic.com over CLI
// versions (2.1.x emits claude.com), so accept all of them.
const CLAUDE_URL_PATTERN =
  /(https:\/\/(?:claude\.ai|claude\.com|platform\.claude\.com|console\.anthropic\.com)\/[^\s"'<>`]+)/i

// Codex device login prints a verification URL and a user code.
const CODEX_URL_PATTERN = /(https:\/\/[^\s"'<>`]*(?:openai\.com|chatgpt\.com)[^\s"'<>`]*)/i
const DEVICE_CODE_PATTERN =
  /(?:user\s*code|device\s*code|enter\s*the\s*code|code:)\s*:?\s*([A-Z0-9]{4,}(?:-[A-Z0-9]{4,})?)/i

export interface ClaudeParse {
  authorizeUrl?: string
}

export function parseClaude(buffer: string): ClaudeParse {
  const clean = stripAnsi(buffer)
  const m = clean.match(CLAUDE_URL_PATTERN)
  return { authorizeUrl: m?.[1]?.trim() }
}

export interface CodexParse {
  deviceCode?: string
  verificationUrl?: string
}

export function parseCodex(buffer: string): CodexParse {
  const clean = stripAnsi(buffer)
  const url = clean.match(CODEX_URL_PATTERN)
  const code = clean.match(DEVICE_CODE_PATTERN)
  return {
    verificationUrl: url?.[1]?.trim(),
    deviceCode: code?.[1]?.trim(),
  }
}

// The CLIs print an unambiguous success line once the login completes.
const CLAUDE_SUCCESS =
  /(login\s*success|successfully\s*logged\s*in|you\s*are\s*now\s*logged\s*in|authenticated|token\s*(?:saved|created|stored)|setup\s*complete)/i
const CODEX_SUCCESS = /(login\s*success|successfully\s*logged\s*in|signed\s*in|authentication\s*complete)/i

export function detectSuccess(provider: Provider, buffer: string): boolean {
  const clean = stripAnsi(buffer)
  return provider === 'claude' ? CLAUDE_SUCCESS.test(clean) : CODEX_SUCCESS.test(clean)
}

const FAILURE_PATTERN = /(login\s*failed|authentication\s*failed|error:|invalid\s*code|expired)/i

export function detectFailure(buffer: string): boolean {
  return FAILURE_PATTERN.test(stripAnsi(buffer))
}
