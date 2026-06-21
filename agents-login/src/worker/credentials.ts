import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { Provider } from '../shared/types.js'

export const SCHEMA_VERSION = '1'

export interface CredentialPaths {
  home: string
  codexHome: string
}

export interface CredentialBundle {
  // Vault KV v2 path-relative field map plus metadata.
  data: Record<string, string>
}

async function readUtf8(path: string): Promise<string> {
  return readFile(path, 'utf8')
}

/**
 * Capture Claude credential files from the live HOME.
 *  - $HOME/.claude/.credentials.json
 *  - $HOME/.claude.json  (SIBLING of .claude/, NOT inside it)
 */
export async function captureClaude(
  paths: CredentialPaths,
  updatedBy: string,
  now: () => Date = () => new Date(),
): Promise<CredentialBundle> {
  const credsPath = join(paths.home, '.claude', '.credentials.json')
  const dotClaudePath = join(paths.home, '.claude.json')
  const [credentials, dotClaude] = await Promise.all([readUtf8(credsPath), readUtf8(dotClaudePath)])
  return {
    // Vault KV v2 field keys are underscored (credentials_json, claude_json):
    // `vault kv put` and the import/compare Job use these from the CLI, where
    // leading-dot keys are awkward. The VaultStaticSecret transformation
    // templates re-emit the dot-named filenames (.credentials.json,
    // .claude.json) into the projected Secret for consumers.
    data: {
      credentials_json: credentials,
      claude_json: dotClaude,
      schema_version: SCHEMA_VERSION,
      updated_at: now().toISOString(),
      updated_by: updatedBy,
    },
  }
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
): Promise<CredentialBundle> {
  return provider === 'claude' ? captureClaude(paths, updatedBy, now) : captureCodex(paths, updatedBy, now)
}
