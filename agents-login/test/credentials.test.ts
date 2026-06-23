import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { capture, captureClaude, captureCodex, SCHEMA_VERSION } from '../src/worker/credentials.js'

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

  it('captures Claude credentials including the .claude.json SIBLING path', async () => {
    // .credentials.json lives inside .claude/, but .claude.json is a sibling.
    writeFileSync(join(home, '.claude', '.credentials.json'), '{"accessToken":"a"}')
    writeFileSync(join(home, '.claude.json'), '{"oauthAccount":{}}')

    const bundle = await captureClaude({ home, codexHome }, 'alice@example.com', fixedNow)
    expect(bundle.data['credentials_json']).toBe('{"accessToken":"a"}')
    expect(bundle.data['claude_json']).toBe('{"oauthAccount":{}}')
    expect(bundle.data.schema_version).toBe(SCHEMA_VERSION)
    expect(bundle.data.updated_by).toBe('alice@example.com')
    expect(bundle.data.updated_at).toBe('2026-06-21T00:00:00.000Z')
  })

  it('captures the setup-token OAuth token from stdout when no files are written', async () => {
    // `claude setup-token` prints the token and persists nothing; the token is
    // the canonical credential.
    const token = 'sk-ant-oat01-abcDEF123456_-789ghiJKLmnop'
    const bundle = await captureClaude({ home, codexHome }, 'alice', fixedNow, token)
    expect(bundle.data.oauth_token).toBe(token)
    expect(bundle.data['credentials_json']).toBeUndefined()
    expect(bundle.data['claude_json']).toBeUndefined()
    expect(bundle.data.updated_by).toBe('alice')
  })

  it('captures the token alongside files when both are present', async () => {
    writeFileSync(join(home, '.claude', '.credentials.json'), '{"accessToken":"a"}')
    writeFileSync(join(home, '.claude.json'), '{"oauthAccount":{}}')
    const bundle = await captureClaude({ home, codexHome }, 'alice', fixedNow, 'sk-ant-oat01-zzzzzzzzzzzzzzzzzzzz')
    expect(bundle.data.oauth_token).toBe('sk-ant-oat01-zzzzzzzzzzzzzzzzzzzz')
    expect(bundle.data['credentials_json']).toBe('{"accessToken":"a"}')
  })

  it('fails when neither a token nor a credentials file is available', async () => {
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
