import { mkdtempSync, writeFileSync, chmodSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

/**
 * Materialise fake `claude` and `codex` executables (POSIX shell scripts) in a
 * temp dir. They emit the same authorize-URL / device-code lines the real CLIs
 * print, perform the redirect-URL stdin handshake (Claude), and write the
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
if [ ! -f "$CLAUDE_CODE_MANAGED_SETTINGS_PATH" ]; then
  echo "error: missing managed settings"
  exit 1
fi
echo "Choose the look for Claude Code"
echo "Dark mode"
read THEME
echo "Welcome back"
echo "Not logged in · Run /login"
echo "? for shortcuts"
read LOGIN
if [ "$LOGIN" != "/login" ]; then
  echo "error: expected /login, got $LOGIN"
  exit 1
fi
echo "Select login method"
echo "Claude account with subscription"
read METHOD
if [ -n "$METHOD" ]; then
  echo "error: expected default login-method Enter"
  exit 1
fi
echo "Browser didn't open? Use the url below to sign in"
echo "https://claude.com/cai/oauth/authorize?code=true&scope=user%3Aprofile%20user%3Ainference%20user%3Asessions%3Aclaude_code&state=xyz"
echo "Paste code here if prompted >"
# block on stdin for the authorization code
read CODE
echo "received: $CODE" >/dev/null
mkdir -p "$HOME/.claude"
printf '%s' '{"claudeAiOauth":{"accessToken":"sk-ant-oat01-claudeSubscriptionToken1234567890","refreshToken":"r","scopes":["user:profile","user:inference","user:sessions:claude_code"],"subscriptionType":"max"}}' > "$HOME/.claude/.credentials.json"
printf '%s' '{"installMethod":"global","oauthAccount":{"emailAddress":"x@y","accountUuid":"acct-1"}}' > "$HOME/.claude.json"
echo "Logged in as x@y"
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
