import { mkdtempSync, writeFileSync, chmodSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

/**
 * Materialise fake `claude` and `codex` executables (POSIX shell scripts) in a
 * temp dir. They emit the same authorize-URL / device-code lines the real CLIs
 * print, perform the redirect-URL stdin handshake (Claude), and write the four
 * credential files into HOME / CODEX_HOME — so the worker can be driven by a
 * real PTY with no network and no real CLIs.
 */
export interface FakeCliEnv {
  binDir: string
  home: string
  codexHome: string
  pathEnv: string
}

export function makeFakeCliEnv(): FakeCliEnv {
  const root = mkdtempSync(join(tmpdir(), 'agents-login-'))
  const binDir = join(root, 'bin')
  const home = join(root, 'home')
  const codexHome = join(home, '.codex')
  mkdirSync(binDir, { recursive: true })
  mkdirSync(join(home, '.claude'), { recursive: true })
  mkdirSync(codexHome, { recursive: true })

  const claude = `#!/usr/bin/env sh
echo "Visit the following URL to authorize Claude Code:"
echo "https://claude.ai/oauth/authorize?code=abc123&state=xyz"
echo "Paste code here if prompted >"
# block on stdin for the authorization code
read CODE
echo "received: $CODE" >/dev/null
echo "Login successful. You are now logged in."
mkdir -p "$HOME/.claude"
printf '%s' '{"accessToken":"claude-secret-token","refreshToken":"r"}' > "$HOME/.claude/.credentials.json"
printf '%s' '{"installMethod":"global","oauthAccount":{"emailAddress":"x@y"}}' > "$HOME/.claude.json"
exit 0
`

  const codex = `#!/usr/bin/env sh
echo "Open the following URL to sign in to Codex:"
echo "https://auth.openai.com/device"
echo "Enter the code: WXYZ-1234"
sleep 0.05
echo "Successfully logged in."
mkdir -p "$CODEX_HOME"
printf '%s' '{"OPENAI_API_KEY":null,"tokens":{"access_token":"codex-secret"}}' > "$CODEX_HOME/auth.json"
printf '%s' 'model = "gpt-5"\n' > "$CODEX_HOME/config.toml"
exit 0
`

  writeFileSync(join(binDir, 'claude'), claude)
  writeFileSync(join(binDir, 'codex'), codex)
  chmodSync(join(binDir, 'claude'), 0o755)
  chmodSync(join(binDir, 'codex'), 0o755)

  return {
    binDir,
    home,
    codexHome,
    pathEnv: `${binDir}:${process.env.PATH ?? ''}`,
  }
}
