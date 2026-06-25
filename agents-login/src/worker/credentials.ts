import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { Provider } from '../shared/types.js'
import { parseClaudeToken } from './parse.js'

export const SCHEMA_VERSION = '1'

export interface CredentialPaths {
  home: string
  codexHome: string
}

export interface CredentialBundle {
  // Captured provider fields plus local bookkeeping used before ingest.
  data: Record<string, string>
}

async function readUtf8(path: string): Promise<string> {
  return readFile(path, 'utf8')
}

async function readUtf8OrUndefined(path: string): Promise<string | undefined> {
  try {
    return await readUtf8(path)
  } catch {
    return undefined
  }
}

export async function readClaudeCredentialsToken(paths: CredentialPaths): Promise<string | undefined> {
  const credentials = await readUtf8OrUndefined(join(paths.home, '.claude', '.credentials.json'))
  return credentials === undefined ? undefined : parseClaudeToken(credentials)
}

/**
 * Capture the Claude credential from a completed `setup-token` run.
 *
 * `setup-token`'s product is a long-lived OAuth token printed to stdout (for
 * CLAUDE_CODE_OAUTH_TOKEN); it does NOT write a credentials file. The token is
 * therefore the canonical credential and is parsed from the PTY output by the
 * caller. The interactive `claude login` flow instead leaves
 * `$HOME/.claude/.credentials.json` and `$HOME/.claude.json` (a SIBLING of
 * .claude/), so those are captured opportunistically when present. Capture
 * fails only when neither a token nor a credentials file is available.
 */
export async function captureClaude(
  paths: CredentialPaths,
  updatedBy: string,
  now: () => Date = () => new Date(),
  oauthToken?: string,
): Promise<CredentialBundle> {
  const credsPath = join(paths.home, '.claude', '.credentials.json')
  const dotClaudePath = join(paths.home, '.claude.json')
  const [credentials, dotClaude] = await Promise.all([
    readUtf8OrUndefined(credsPath),
    readUtf8OrUndefined(dotClaudePath),
  ])
  if (!oauthToken && credentials === undefined) {
    throw new Error('no Claude credential captured: setup-token printed no token and no .credentials.json was written')
  }
  const capturedToken = oauthToken ?? (credentials === undefined ? undefined : parseClaudeToken(credentials))
  const data: Record<string, string> = {
    schema_version: SCHEMA_VERSION,
    updated_at: now().toISOString(),
    updated_by: updatedBy,
  }
  if (capturedToken) {
    data.oauth_token = capturedToken
  }
  if (credentials !== undefined) {
    data.credentials_json = credentials
  }
  if (dotClaude !== undefined) {
    data.claude_json = dotClaude
  }
  return { data }
}

/**
 * Capture Codex credential files.
 *  - $CODEX_HOME/auth.json
 *  - $CODEX_HOME/config.toml
 */
export async function captureCodex(
  paths: CredentialPaths,
  updatedBy: string,
  now: () => Date = () => new Date(),
): Promise<CredentialBundle> {
  const authPath = join(paths.codexHome, 'auth.json')
  const configPath = join(paths.codexHome, 'config.toml')
  const [auth, config] = await Promise.all([readUtf8(authPath), readUtf8(configPath)])
  return {
    data: {
      auth_json: auth,
      config_toml: config,
      schema_version: SCHEMA_VERSION,
      updated_at: now().toISOString(),
      updated_by: updatedBy,
    },
  }
}

export async function capture(
  provider: Provider,
  paths: CredentialPaths,
  updatedBy: string,
  now?: () => Date,
  claudeToken?: string,
): Promise<CredentialBundle> {
  return provider === 'claude' ? captureClaude(paths, updatedBy, now, claudeToken) : captureCodex(paths, updatedBy, now)
}
