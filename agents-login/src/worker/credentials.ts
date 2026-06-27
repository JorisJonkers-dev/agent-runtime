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

export async function readClaudeCredentialsJson(paths: CredentialPaths): Promise<string | undefined> {
  return readUtf8OrUndefined(join(paths.home, '.claude', '.credentials.json'))
}

/**
 * True only when `.credentials.json` actually carries an OAuth token. Claude
 * creates the file and populates `claudeAiOauth.accessToken` a moment later, so
 * a probe that fires on mere file existence captures an empty `{}` (the runner
 * then has no token and shows "Not logged in" / API billing). Gate capture on
 * this so the worker waits for the populated credential.
 */
export function claudeCredentialIsPopulated(content: string | undefined): content is string {
  return content !== undefined && extractClaudeOauthToken(content) !== undefined
}

function objectRecord(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined
}

function findStringField(value: unknown, fieldNames: Set<string>): string | undefined {
  const record = objectRecord(value)
  if (!record) {
    return undefined
  }
  for (const [key, fieldValue] of Object.entries(record)) {
    if (fieldNames.has(key) && typeof fieldValue === 'string' && fieldValue.length > 0) {
      return fieldValue
    }
  }
  for (const fieldValue of Object.values(record)) {
    const nested = findStringField(fieldValue, fieldNames)
    if (nested) {
      return nested
    }
  }
  return undefined
}

export function extractClaudeOauthToken(credentialsJson: string): string | undefined {
  const tokenFromRaw = parseClaudeToken(credentialsJson)
  if (tokenFromRaw) {
    return tokenFromRaw
  }
  try {
    return findStringField(JSON.parse(credentialsJson), new Set(['accessToken', 'oauth_token']))
  } catch {
    return undefined
  }
}

function extractClaudeAccountJson(dotClaudeJson: string): string | undefined {
  const parsed = objectRecord(JSON.parse(dotClaudeJson))
  if (!parsed || parsed.oauthAccount === undefined) {
    return undefined
  }
  return JSON.stringify(parsed.oauthAccount)
}

/**
 * Capture the Claude credential from a completed subscription login.
 *
 * The interactive `claude` first-run login writes
 * `$HOME/.claude/.credentials.json` and `$HOME/.claude.json` (a SIBLING of
 * .claude/). The full subscription credential is the canonical artifact; a
 * parsed token is kept only as an optional back-compat payload field.
 */
export async function captureClaude(
  paths: CredentialPaths,
  updatedBy: string,
  now: () => Date = () => new Date(),
  _oauthToken?: string,
): Promise<CredentialBundle> {
  const credsPath = join(paths.home, '.claude', '.credentials.json')
  const dotClaudePath = join(paths.home, '.claude.json')
  const [credentials, dotClaude] = await Promise.all([
    readUtf8OrUndefined(credsPath),
    readUtf8OrUndefined(dotClaudePath),
  ])
  if (!claudeCredentialIsPopulated(credentials)) {
    throw new Error(
      'no Claude credential captured: .credentials.json missing claudeAiOauth.accessToken (not written yet)',
    )
  }
  const capturedToken = extractClaudeOauthToken(credentials)
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
    const accountJson = extractClaudeAccountJson(dotClaude)
    if (accountJson !== undefined) {
      data.account_json = accountJson
    }
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
