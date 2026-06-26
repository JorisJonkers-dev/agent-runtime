import type { Provider } from '../shared/types.js'

// PTY output arrives with ANSI escape codes and is chunked arbitrarily, so the
// parsers run over a stripped, accumulated buffer.

// CSI escape sequences: ESC [ ... final-byte.
// eslint-disable-next-line no-control-regex
const ANSI_PATTERN = /\x1b\[[0-9;?]*[ -/]*[@-~]/g
// OSC sequences: ESC ] ... terminated by BEL (\x07) or ST (ESC \). The CLIs
// wrap the authorize/verification URL in an OSC 8 hyperlink, so the wrapper
// must be removed wholesale before the plain-text fallback scan — otherwise its
// framing bytes are dropped as stray control chars (below) and fuse the real
// URL onto its display-text copy with no separator.
// eslint-disable-next-line no-control-regex
const OSC_PATTERN = /\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)/g
// OSC 8 hyperlink: ESC ] 8 ; params ; URI ST. Capture group 1 is the link
// target — the canonical, untruncated URL the CLI is pointing at.
// eslint-disable-next-line no-control-regex
const OSC8_HYPERLINK = /\x1b\]8;[^;]*;([^\x07\x1b]*)(?:\x07|\x1b\\)/g
// Remaining C0 control bytes except CR/LF/TAB, which carry layout meaning.
// eslint-disable-next-line no-control-regex
const OTHER_CONTROL = /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g

export function stripAnsi(input: string): string {
  return input.replace(OSC_PATTERN, '').replace(ANSI_PATTERN, '').replace(OTHER_CONTROL, '')
}

// OSC 8 hyperlink targets, in emission order, from the raw (pre-strip) buffer.
export function oscHyperlinkTargets(raw: string): string[] {
  const targets: string[] = []
  OSC8_HYPERLINK.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = OSC8_HYPERLINK.exec(raw)) !== null) {
    const uri = m[1]?.trim()
    if (uri) {
      targets.push(uri)
    }
  }
  return targets
}

// `claude setup-token` prints an OAuth authorize URL. The host has moved across
// claude.ai / claude.com / platform.claude.com / console.anthropic.com over CLI
// versions (2.1.x emits claude.com), so accept all of them.
const CLAUDE_URL_PATTERN =
  /(https:\/\/(?:claude\.ai|claude\.com|platform\.claude\.com|console\.anthropic\.com)\/[^\s"'<>`]+)/i

// Codex device login prints a verification URL and a one-time code. Newer
// Codex (`login --device-auth`, 0.141.x) prints "Enter this one-time code
// (expires in 15 minutes)" with the code on the *next* line, so the keyword and
// the code are no longer adjacent; allow prose/newlines between them. The
// digit lookahead keeps the match off pure-word tokens like "expires" that the
// case-insensitive class would otherwise accept.
const CODEX_URL_PATTERN = /(https:\/\/[^\s"'<>`]*(?:openai\.com|chatgpt\.com)[^\s"'<>`]*)/i
const DEVICE_CODE_PATTERN =
  /(?:one[-\s]?time\s*code|user\s*code|device\s*code|enter\s*the\s*code|code:)[\s\S]{0,80}?(?=[A-Z0-9-]*[0-9])([A-Z0-9]{4,}(?:-[A-Z0-9]{4,})*)/i

export interface ClaudeParse {
  authorizeUrl?: string
}

export function parseClaude(buffer: string): ClaudeParse {
  // Prefer the OSC 8 hyperlink target: it is the full URL even when the visible
  // copy is width-truncated, and it cannot be fused to adjacent text.
  for (const uri of oscHyperlinkTargets(buffer)) {
    const matched = uri.match(CLAUDE_URL_PATTERN)?.[1]?.trim()
    if (matched) {
      return { authorizeUrl: matched }
    }
  }
  const clean = stripAnsi(buffer)
  const m = clean.match(CLAUDE_URL_PATTERN)
  return { authorizeUrl: m?.[1]?.trim() }
}

export function detectClaudeCodePrompt(buffer: string): boolean {
  return /paste\s*code\s*here\s*if\s*prompted\s*>/i.test(stripAnsi(buffer))
}

export interface ClaudeRedirectCode {
  code: string
  state?: string
  source: 'url' | 'bare'
}

export function parseClaudeRedirectCode(input: string): ClaudeRedirectCode | undefined {
  const trimmed = input.trim()
  if (trimmed.length === 0) {
    return undefined
  }
  try {
    const url = new URL(trimmed)
    const code = url.searchParams.get('code')?.trim()
    if (!code) {
      return undefined
    }
    const state = url.searchParams.get('state')?.trim() || undefined
    return { code, state, source: 'url' }
  } catch {
    return { code: trimmed, source: 'bare' }
  }
}

// `claude setup-token`'s actual product is a long-lived OAuth token printed to
// stdout ("Your OAuth token (valid for 1 year): sk-ant-oat01-…", meant for
// CLAUDE_CODE_OAUTH_TOKEN). It is NOT persisted to a credentials file, so the
// token must be captured from the PTY output. The PTY is 400 cols wide, so the
// ~100-char token is emitted on one line and bounded by whitespace.
const CLAUDE_TOKEN_PATTERN = /(sk-ant-oat[0-9]{2}-[A-Za-z0-9_-]{20,})/

export function parseClaudeToken(buffer: string): string | undefined {
  for (const uri of oscHyperlinkTargets(buffer)) {
    const matched = uri.match(CLAUDE_TOKEN_PATTERN)?.[1]
    if (matched) {
      return matched
    }
  }
  return stripAnsi(buffer).match(CLAUDE_TOKEN_PATTERN)?.[1]
}

export interface CodexParse {
  deviceCode?: string
  verificationUrl?: string
}

export function parseCodex(buffer: string): CodexParse {
  let verificationUrl: string | undefined
  for (const uri of oscHyperlinkTargets(buffer)) {
    const matched = uri.match(CODEX_URL_PATTERN)?.[1]?.trim()
    if (matched) {
      verificationUrl = matched
      break
    }
  }
  const clean = stripAnsi(buffer)
  if (!verificationUrl) {
    verificationUrl = clean.match(CODEX_URL_PATTERN)?.[1]?.trim()
  }
  const code = clean.match(DEVICE_CODE_PATTERN)
  return { verificationUrl, deviceCode: code?.[1]?.trim() }
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
