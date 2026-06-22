import { describe, it, expect } from 'vitest'
import { parseClaude, parseCodex, detectSuccess, detectFailure, stripAnsi } from '../src/worker/parse.js'

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

  it('detects failure lines', () => {
    expect(detectFailure('error: invalid code')).toBe(true)
    expect(detectFailure('all good')).toBe(false)
  })
})
