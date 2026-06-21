// Minimal, dependency-free portal UI. The CSRF token is injected at render
// time; the inline JS polls /api/status and posts to the mutating endpoints.

export function renderUi(csrfToken: string, user: string): string {
  const safeUser = user.replace(/[<>&"]/g, '')
  return `<!-- agents-login portal -->
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Agents Login Portal</title>
<style>
  :root { color-scheme: light dark; }
  body { font-family: ui-sans-serif, system-ui, sans-serif; max-width: 720px; margin: 2rem auto; padding: 0 1rem; line-height: 1.5; }
  h1 { font-size: 1.4rem; }
  button { font-size: 1rem; padding: 0.5rem 1rem; margin-right: 0.5rem; cursor: pointer; }
  .card { border: 1px solid #8884; border-radius: 8px; padding: 1rem; margin-top: 1rem; }
  code, .copyable { font-family: ui-monospace, monospace; word-break: break-all; }
  .copyable { display: block; background: #8881; padding: 0.5rem; border-radius: 6px; margin: 0.5rem 0; }
  input[type=text] { width: 100%; box-sizing: border-box; padding: 0.5rem; font-family: ui-monospace, monospace; }
  .muted { color: #8889; font-size: 0.85rem; }
  .phase { font-weight: 600; }
  .ok { color: #2a8; } .err { color: #d44; }
</style>
<h1>Agents Login Portal</h1>
<p class="muted">Signed in as <strong>${safeUser}</strong>. Credentials are written to Vault for fan-out to the runners.</p>
<div>
  <button id="start-claude">Login Claude</button>
  <button id="start-codex">Login Codex</button>
  <button id="cancel" disabled>Cancel</button>
</div>
<div id="status" class="card" hidden>
  <div>Provider: <code id="provider"></code> — <span id="phase" class="phase"></span></div>
  <div id="message" class="muted"></div>
  <div id="error" class="err"></div>
  <div id="authorize" hidden>
    <p>Open this authorize URL, approve, then paste the redirect URL below:</p>
    <a id="authorize-url" class="copyable" target="_blank" rel="noopener"></a>
    <input id="redirect-url" type="text" placeholder="https://… redirect URL after approval" />
    <button id="submit-redirect">Submit redirect URL</button>
  </div>
  <div id="device" hidden>
    <p>Open the verification URL and enter this code:</p>
    <a id="verification-url" class="copyable" target="_blank" rel="noopener"></a>
    <span class="copyable" id="device-code"></span>
  </div>
</div>
<script>
const CSRF = ${JSON.stringify(csrfToken)};
let sessionId = null, poll = null;
const $ = (id) => document.getElementById(id);
function show(el, on) { el.hidden = !on; }
async function post(path, body) {
  const res = await fetch(path, { method: 'POST', headers: { 'content-type': 'application/json', 'x-csrf-token': CSRF }, body: JSON.stringify(body || {}) });
  if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.error || res.status); }
  return res.json();
}
function render(s) {
  show($('status'), true);
  $('provider').textContent = s.provider;
  $('phase').textContent = s.phase;
  $('message').textContent = s.message || '';
  $('error').textContent = s.error || '';
  show($('authorize'), s.provider === 'claude' && !!s.authorizeUrl && s.phase !== 'succeeded' && s.phase !== 'failed' && s.phase !== 'cancelled');
  if (s.authorizeUrl) { $('authorize-url').textContent = s.authorizeUrl; $('authorize-url').href = s.authorizeUrl; }
  show($('device'), s.provider === 'codex' && (!!s.deviceCode || !!s.verificationUrl));
  if (s.verificationUrl) { $('verification-url').textContent = s.verificationUrl; $('verification-url').href = s.verificationUrl; }
  if (s.deviceCode) { $('device-code').textContent = s.deviceCode; }
  const terminal = ['succeeded','failed','cancelled'].includes(s.phase);
  $('cancel').disabled = terminal;
  if (terminal && poll) { clearInterval(poll); poll = null; }
}
async function start(provider) {
  $('error').textContent = '';
  try {
    const s = await post('/api/login', { provider });
    sessionId = s.id; render(s);
    if (poll) clearInterval(poll);
    poll = setInterval(refresh, 2000);
  } catch (e) { show($('status'), true); $('error').textContent = String(e.message || e); }
}
async function refresh() {
  if (!sessionId) return;
  const res = await fetch('/api/status?id=' + encodeURIComponent(sessionId), { headers: { 'cache-control': 'no-store' } });
  if (res.ok) render(await res.json());
}
$('start-claude').onclick = () => start('claude');
$('start-codex').onclick = () => start('codex');
$('cancel').onclick = async () => { if (sessionId) { await post('/api/cancel', { id: sessionId }).catch(()=>{}); refresh(); } };
$('submit-redirect').onclick = async () => {
  const url = $('redirect-url').value;
  try { await post('/api/redirect', { id: sessionId, url }); refresh(); }
  catch (e) { $('error').textContent = String(e.message || e); }
};
</script>`
}
