import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  capture,
  captureClaude,
  captureCodex,
  extractClaudeOauthToken,
  SCHEMA_VERSION,
} from '../src/worker/credentials.js'

describe('credential capture', () => {
  let root: string
  let home: string
  let codexHome: string
  const fixedNow = () => new Date('2026-06-21T00:00:00.000Z')

  beforeEach(() => {
    root = mkdtempSync(join(tmpdir(), 'creds-'))
    home = join(root, 'home')
    codexHome = join(home, '.codex')
    mkdirSync(join(home, '.claude'), { recursive: true })
    mkdirSync(codexHome, { recursive: true })
  })

  afterEach(() => {
    rmSync(root, { recursive: true, force: true })
  })

  it('captures Claude credentials and extracts oauthAccount from the .claude.json sibling path', async () => {
    // .credentials.json lives inside .claude/, but .claude.json is a sibling.
    const credentialsJson =
      '{"claudeAiOauth":{"accessToken":"sk-ant-oat01-CaptureToken1234567890","refreshToken":"r","scopes":["user:profile","user:inference"],"subscriptionType":"max"}}'
    const accountJson = '{"emailAddress":"alice@example.com","accountUuid":"acct-1"}'
    writeFileSync(join(home, '.claude', '.credentials.json'), credentialsJson)
    writeFileSync(join(home, '.claude.json'), `{"installMethod":"global","oauthAccount":${accountJson}}`)

    const bundle = await captureClaude({ home, codexHome }, 'alice@example.com', fixedNow)
    expect(bundle.data['credentials_json']).toBe(credentialsJson)
    expect(bundle.data['claude_json']).toBe(`{"installMethod":"global","oauthAccount":${accountJson}}`)
    expect(bundle.data['account_json']).toBe(accountJson)
    expect(bundle.data.oauth_token).toBe('sk-ant-oat01-CaptureToken1234567890')
    expect(bundle.data.schema_version).toBe(SCHEMA_VERSION)
    expect(bundle.data.updated_by).toBe('alice@example.com')
    expect(bundle.data.updated_at).toBe('2026-06-21T00:00:00.000Z')
  })

  it('rejects stdout-only Claude OAuth tokens without the full credentials file', async () => {
    const token = 'sk-ant-oat01-abcDEF123456_-789ghiJKLmnop'
    await expect(captureClaude({ home, codexHome }, 'alice', fixedNow, token)).rejects.toThrow(
      /credentials\.json was not written/,
    )
  })

  it('parses the optional back-compat token from credentials_json', async () => {
    writeFileSync(join(home, '.claude', '.credentials.json'), '{"accessToken":"sk-ant-oat01-zzzzzzzzzzzzzzzzzzzz"}')
    writeFileSync(join(home, '.claude.json'), '{"oauthAccount":{}}')
    const bundle = await captureClaude({ home, codexHome }, 'alice', fixedNow)
    expect(bundle.data.oauth_token).toBe('sk-ant-oat01-zzzzzzzzzzzzzzzzzzzz')
    expect(bundle.data['credentials_json']).toBe('{"accessToken":"sk-ant-oat01-zzzzzzzzzzzzzzzzzzzz"}')
  })

  it('extracts a nested Claude OAuth accessToken when credentials_json has no raw sk-ant token', () => {
    expect(
      extractClaudeOauthToken(
        JSON.stringify({
          profiles: { default: true },
          claudeAiOauth: {
            refreshToken: 'refresh-token',
            accessToken: 'oauth-access-token-from-json',
          },
        }),
      ),
    ).toBe('oauth-access-token-from-json')
  })

  it('returns undefined when credentials_json cannot be parsed as JSON', () => {
    expect(extractClaudeOauthToken('{"claudeAiOauth":')).toBeUndefined()
  })

  it('omits account_json when .claude.json has no oauthAccount', async () => {
    const credentialsJson = '{"claudeAiOauth":{"refreshToken":"refresh-token"}}'
    const dotClaudeJson = '{"installMethod":"global"}'
    writeFileSync(join(home, '.claude', '.credentials.json'), credentialsJson)
    writeFileSync(join(home, '.claude.json'), dotClaudeJson)

    const bundle = await captureClaude({ home, codexHome }, 'alice', fixedNow)

    expect(bundle.data['credentials_json']).toBe(credentialsJson)
    expect(bundle.data['claude_json']).toBe(dotClaudeJson)
    expect(bundle.data).not.toHaveProperty('account_json')
    expect(bundle.data).not.toHaveProperty('oauth_token')
  })

  it('fails when no credentials file is available', async () => {
    await expect(captureClaude({ home, codexHome }, 'x', fixedNow)).rejects.toThrow(/no Claude credential/)
  })

  it('captures Codex credentials from CODEX_HOME', async () => {
    writeFileSync(join(codexHome, 'auth.json'), '{"tokens":{}}')
    writeFileSync(join(codexHome, 'config.toml'), 'model = "gpt-5"\n')
    const bundle = await captureCodex({ home, codexHome }, 'bob', fixedNow)
    expect(bundle.data['auth_json']).toBe('{"tokens":{}}')
    expect(bundle.data['config_toml']).toBe('model = "gpt-5"\n')
    expect(bundle.data.updated_by).toBe('bob')
  })

  it('dispatches by provider', async () => {
    writeFileSync(join(codexHome, 'auth.json'), '{}')
    writeFileSync(join(codexHome, 'config.toml'), '')
    const bundle = await capture('codex', { home, codexHome }, 'c', fixedNow)
    expect(bundle.data['auth_json']).toBe('{}')
  })
})
