import { describe, it, expect } from 'vitest'
import {
  parseClaude,
  parseClaudeRedirectCode,
  parseClaudeToken,
  parseCodex,
  detectClaudeCodePrompt,
  detectSuccess,
  detectFailure,
  stripAnsi,
} from '../src/worker/parse.js'

describe('PTY output parsing', () => {
  it('strips ANSI escape and control sequences', () => {
    const raw = '[31mhello[0m\r\nworld'
    expect(stripAnsi(raw)).toBe('hello\r\nworld')
  })

  it('extracts the Claude authorize URL from chunked, coloured output', () => {
    const buf = 'Visit:\r\n[36mhttps://claude.ai/oauth/authorize?code=abc&state=xyz[0m\r\n'
    expect(parseClaude(buf).authorizeUrl).toBe('https://claude.ai/oauth/authorize?code=abc&state=xyz')
  })

  it('also matches console.anthropic.com authorize URLs', () => {
    const buf = 'open https://console.anthropic.com/oauth/authorize?x=1 now'
    expect(parseClaude(buf).authorizeUrl).toBe('https://console.anthropic.com/oauth/authorize?x=1')
  })

  it('matches the claude.com host that setup-token emits', () => {
    const buf = 'Use the url below to sign in\r\nhttps://claude.com/oauth/authorize?code=true&client_id=x&state=y\r\n'
    expect(parseClaude(buf).authorizeUrl).toBe('https://claude.com/oauth/authorize?code=true&client_id=x&state=y')
  })

  it('returns undefined when no Claude URL present', () => {
    expect(parseClaude('nothing here').authorizeUrl).toBeUndefined()
  })

  it('extracts the clean target from an OSC 8 hyperlink, not the fused display copy', () => {
    // `claude setup-token` prints the authorize URL as an OSC 8 hyperlink
    // (ESC ] 8 ; id ; URL BEL <visible copy> ESC ] 8 ; ; BEL). The framing
    // bytes used to be dropped as stray control chars, fusing the real URL to
    // the visible copy and corrupting the `state` param -> claude.com
    // "Invalid request format".
    const url =
      'https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e' +
      '&response_type=code&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback' +
      '&scope=user%3Ainference&code_challenge=bqZmsNJOxO90O4o5X9obQ9vZkiqszKKv6MVdh2ATZ-E' +
      '&code_challenge_method=S256&state=Sd4AzJmXYk19-_QoCD_XMnCfnMWTJ55N3ej4ZdGF3-c'
    const raw = `Use the url below to sign in (c to copy)\r\n\x1b]8;id=sk1awg;${url}\x07${url}\x1b]8;;\x07\r\nPaste code here if prompted >`
    expect(parseClaude(raw).authorizeUrl).toBe(url)
  })

  it('handles an ST-terminated OSC 8 hyperlink', () => {
    const url = 'https://claude.com/cai/oauth/authorize?code=true&state=abc'
    const raw = `\x1b]8;;${url}\x1b\\${url}\x1b]8;;\x1b\\`
    expect(parseClaude(raw).authorizeUrl).toBe(url)
  })

  it('detects the Claude setup-token code prompt', () => {
    expect(detectClaudeCodePrompt('Paste code here if prompted >')).toBe(true)
    expect(detectClaudeCodePrompt('Opening browser to sign in...')).toBe(false)
  })

  it('extracts the Claude authorization code from a callback URL and preserves bare codes', () => {
    expect(
      parseClaudeRedirectCode('https://platform.claude.com/oauth/code/callback?code=AUTH-CODE-123&state=STATE-456'),
    ).toEqual({ code: 'AUTH-CODE-123', state: 'STATE-456', source: 'url' })
    expect(parseClaudeRedirectCode('AUTH-CODE-123')).toEqual({ code: 'AUTH-CODE-123', source: 'bare' })
    expect(parseClaudeRedirectCode('https://platform.claude.com/oauth/code/callback?state=STATE-456')).toBeUndefined()
  })

  it('extracts a Codex verification URL wrapped in an OSC 8 hyperlink', () => {
    const url = 'https://auth.openai.com/device?code=WXYZ-1234'
    const raw = `Enter the code: WXYZ-1234\r\n\x1b]8;id=dev;${url}\x07${url}\x1b]8;;\x07`
    const parsed = parseCodex(raw)
    expect(parsed.verificationUrl).toBe(url)
    expect(parsed.deviceCode).toBe('WXYZ-1234')
  })

  it('extracts the Codex device code and verification URL', () => {
    const buf = 'Open https://auth.openai.com/device\r\nEnter the code: WXYZ-1234\r\n'
    const parsed = parseCodex(buf)
    expect(parsed.verificationUrl).toBe('https://auth.openai.com/device')
    expect(parsed.deviceCode).toBe('WXYZ-1234')
  })

  it('parses a device code without a separator word', () => {
    expect(parseCodex('code: AB12CD').deviceCode).toBe('AB12CD')
  })

  it('detects per-provider success lines', () => {
    expect(detectSuccess('claude', 'Login successful. You are now logged in.')).toBe(true)
    expect(detectSuccess('codex', 'Successfully logged in.')).toBe(true)
    expect(detectSuccess('claude', 'still working')).toBe(false)
  })

  it('parses the setup-token OAuth token from stdout, bounded by whitespace', () => {
    const token = 'sk-ant-oat01-Dx3Q7rrCNsYQi3h2HLUKZK4-KDiCJ_XCKEcSYx0X3H-O0'
    const buf =
      `\x1b[32m✓\x1b[0m Long-lived authentication token created successfully!\r\n` +
      `Your OAuth token (valid for 1 year): ${token}\r\nStore this token securely.`
    expect(parseClaudeToken(buf)).toBe(token)
  })

  it('parses a bare setup-token OAuth token line', () => {
    const token = 'sk-ant-oat01-BareLineToken1234567890_-abcXYZ'
    expect(parseClaudeToken(`${token}\r\n`)).toBe(token)
  })

  it('parses the setup-token OAuth token from an OSC 8 hyperlink target', () => {
    const token = 'sk-ant-oat01-Osc8TokenTarget1234567890_-abcXYZ'
    const raw = `Your OAuth token: \x1b]8;;${token}\x07open token\x1b]8;;\x07\r\n`
    expect(parseClaudeToken(raw)).toBe(token)
  })

  it('returns undefined when no token was printed', () => {
    expect(parseClaudeToken('Visit https://claude.com/cai/oauth/authorize?code=true')).toBeUndefined()
  })

  it('detects failure lines', () => {
    expect(detectFailure('error: invalid code')).toBe(true)
    expect(detectFailure('all good')).toBe(false)
  })
})
