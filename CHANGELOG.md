# Changelog

## [0.18.0](https://github.com/JorisJonkers-dev/agent-runtime/compare/v0.17.0...v0.18.0) (2026-07-10)


### Features

* onboard agent-runtime onto the deploy platform ([b2c71dc](https://github.com/JorisJonkers-dev/agent-runtime/commit/b2c71dc894c79da08c81773bad0f2dabb0870aa5))

## [0.17.0](https://github.com/JorisJonkers-dev/agent-runtime/compare/v0.16.0...v0.17.0) (2026-06-28)


### Features

* **002:** durable sessions — restart with full history ([#17](https://github.com/JorisJonkers-dev/agent-runtime/issues/17)) ([33c1045](https://github.com/JorisJonkers-dev/agent-runtime/commit/33c104586efed673495ab27bf65b550f92d6630c))
* **003:** agent console redesign + live session-status SSE ([#18](https://github.com/JorisJonkers-dev/agent-runtime/issues/18)) ([56fec52](https://github.com/JorisJonkers-dev/agent-runtime/commit/56fec52dfb57303bd8eff39b67a8fb028d69d228))
* **008:** emitted telemetry contract for agents observability ([#20](https://github.com/JorisJonkers-dev/agent-runtime/issues/20)) ([ea44f93](https://github.com/JorisJonkers-dev/agent-runtime/commit/ea44f93481877c7581ff4263f148cf04c51632b9))
* **010:** versioned agent setup management with restart-into-setup ([#19](https://github.com/JorisJonkers-dev/agent-runtime/issues/19)) ([851bb4c](https://github.com/JorisJonkers-dev/agent-runtime/commit/851bb4c6a5c926acddd7e5a06fe3536afb736bee))
* **024a:** stream headless job output incrementally over SSE ([#65](https://github.com/JorisJonkers-dev/agent-runtime/issues/65)) ([a7296a6](https://github.com/JorisJonkers-dev/agent-runtime/commit/a7296a693b5eca25985efe90a6116950ef71f86b))
* **024b:** runner-Pod chat generation backend (flag-gated, default off) ([#67](https://github.com/JorisJonkers-dev/agent-runtime/issues/67)) ([42c1303](https://github.com/JorisJonkers-dev/agent-runtime/commit/42c130303a2b2323f51c2e4f79f64ea4d876cc76))
* **024c:** true token-level streaming for runner-Pod chat ([#68](https://github.com/JorisJonkers-dev/agent-runtime/issues/68)) ([0460e5c](https://github.com/JorisJonkers-dev/agent-runtime/commit/0460e5c9a7007de70f48e6016ce49b96e21cfd05))
* **agent-runner:** boot-time repository manifest + dynamic CLAUDE.md/AGENTS.md ([#131](https://github.com/JorisJonkers-dev/agent-runtime/issues/131)) ([1fec128](https://github.com/JorisJonkers-dev/agent-runtime/commit/1fec128aa7844b9b08464098b0b370922b659536))
* **agents-login:** add the credential login portal service ([#707](https://github.com/JorisJonkers-dev/agent-runtime/issues/707)) ([d02631e](https://github.com/JorisJonkers-dev/agent-runtime/commit/d02631e3b17362d3f7e79289640f776ae94ec904))
* **agents-login:** concurrent Claude+Codex logins and a stored-credential status check ([#720](https://github.com/JorisJonkers-dev/agent-runtime/issues/720)) ([d3b8c00](https://github.com/JorisJonkers-dev/agent-runtime/commit/d3b8c00453057f9cb9aeb6d9231a4aa991a15a16))
* **agents-login:** store captured credentials in agents-api Postgres, not Vault ([#730](https://github.com/JorisJonkers-dev/agent-runtime/issues/730)) ([e7a4126](https://github.com/JorisJonkers-dev/agent-runtime/commit/e7a412615b633e4682945b6f74be7949e84dc217))
* **agents:** GitHub App-only repository access (spec 025) ([#103](https://github.com/JorisJonkers-dev/agent-runtime/issues/103)) ([484bd25](https://github.com/JorisJonkers-dev/agent-runtime/commit/484bd2588dcc5f8ecc2bba2f0fbae5a704efe603))
* **agents:** user-scoped credential store + workspace-pane improvements ([#111](https://github.com/JorisJonkers-dev/agent-runtime/issues/111)) ([1d18e4d](https://github.com/JorisJonkers-dev/agent-runtime/commit/1d18e4d3b8384ddfc5984c8d35422b449482e25d))
* extract + rename the agent stack into ExtraToast/agents (spec 001) ([#2](https://github.com/JorisJonkers-dev/agent-runtime/issues/2)) ([9c7c352](https://github.com/JorisJonkers-dev/agent-runtime/commit/9c7c3526eb2956241937baed41362b9eab623de4))
* only recycle a stale runner once its agent has gone idle ([#47](https://github.com/JorisJonkers-dev/agent-runtime/issues/47)) ([ed6708b](https://github.com/JorisJonkers-dev/agent-runtime/commit/ed6708be43a9cf091abaaad49225ca19fb58a952))
* **runtime:** publish split agent runtime images ([#1](https://github.com/JorisJonkers-dev/agent-runtime/issues/1)) ([98552b0](https://github.com/JorisJonkers-dev/agent-runtime/commit/98552b0dc8f74bed995b76e05324012be4e5b785))


### Bug Fixes

* **agent-gateway:** fall back to a fresh session when a Claude transcript is gone on revival ([#124](https://github.com/JorisJonkers-dev/agent-runtime/issues/124)) ([352b819](https://github.com/JorisJonkers-dev/agent-runtime/commit/352b8197085cf00c06b4b6fd5ceaaba7e5bfcd90))
* **agent-gateway:** resume Claude from the transcript's cwd after runner update/restart ([#140](https://github.com/JorisJonkers-dev/agent-runtime/issues/140)) ([dac28d4](https://github.com/JorisJonkers-dev/agent-runtime/commit/dac28d4f4d43f481e4f7b4837dd3587378d90b9c))
* **agents-login:** accept body-less POSTs so the cancel route stops returning 415 ([#718](https://github.com/JorisJonkers-dev/agent-runtime/issues/718)) ([ba30cf0](https://github.com/JorisJonkers-dev/agent-runtime/commit/ba30cf09059cd16d204d829331d0c5ee7d0f4181))
* **agents-login:** capture a full user:profile-scoped Claude credential ([#739](https://github.com/JorisJonkers-dev/agent-runtime/issues/739)) ([badc8ce](https://github.com/JorisJonkers-dev/agent-runtime/commit/badc8cee899e692491e1eac9e3d61259c517e945))
* **agents-login:** capture the setup-token OAuth token so Vault gets populated ([#721](https://github.com/JorisJonkers-dev/agent-runtime/issues/721)) ([5891e04](https://github.com/JorisJonkers-dev/agent-runtime/commit/5891e04fefdad057b4f55d405a44ccf38cabcbcd))
* **agents-login:** codex config.toml optional — fixes ENOENT after entering device code ([#748](https://github.com/JorisJonkers-dev/agent-runtime/issues/748)) ([81773e9](https://github.com/JorisJonkers-dev/agent-runtime/commit/81773e977dcb43f558b8d7ac108c941e7b00e727))
* **agents-login:** codex login --device-auth + parse its one-time code ([#738](https://github.com/JorisJonkers-dev/agent-runtime/issues/738)) ([e974a95](https://github.com/JorisJonkers-dev/agent-runtime/commit/e974a9501af08c7dd80b1c8826b5a585b67901c3))
* **agents-login:** detect the ANSI-fused Claude login chooser/REPL ([#741](https://github.com/JorisJonkers-dev/agent-runtime/issues/741)) ([d354f46](https://github.com/JorisJonkers-dev/agent-runtime/commit/d354f46e2e540be903435e57c637abb6421ddfc1))
* **agents-login:** drive claude setup-token, parse its real URL, accept the code ([#716](https://github.com/JorisJonkers-dev/agent-runtime/issues/716)) ([f481035](https://github.com/JorisJonkers-dev/agent-runtime/commit/f481035dac4fad5eb3b4335fd05f691f36f64a32))
* **agents-login:** drop the broken K8s lease that failed credential finalize ([#736](https://github.com/JorisJonkers-dev/agent-runtime/issues/736)) ([e73bc1d](https://github.com/JorisJonkers-dev/agent-runtime/commit/e73bc1d7b7145a623cf46e179571227322d237c7))
* **agents-login:** extract OSC 8 hyperlink target so the authorize URL isn't corrupted ([#717](https://github.com/JorisJonkers-dev/agent-runtime/issues/717)) ([d94b207](https://github.com/JorisJonkers-dev/agent-runtime/commit/d94b20760231cfb65943f7f0536661f9ffe91552))
* **agents-login:** finalize Claude capture on parsed token (stuck-waiting bug) ([#731](https://github.com/JorisJonkers-dev/agent-runtime/issues/731)) ([0023687](https://github.com/JorisJonkers-dev/agent-runtime/commit/0023687bcb8676311ee1b4f980daa91432c1c449))
* **agents-login:** install ca-certificates so codex sign-in works (real root cause) ([#747](https://github.com/JorisJonkers-dev/agent-runtime/issues/747)) ([e58e45f](https://github.com/JorisJonkers-dev/agent-runtime/commit/e58e45fd6fd05b4dfac963f52a1e97b148beeb27))
* **agents-login:** make Claude subscription login reach the authorize URL reliably ([#740](https://github.com/JorisJonkers-dev/agent-runtime/issues/740)) ([5f2bafa](https://github.com/JorisJonkers-dev/agent-runtime/commit/5f2bafa7c1f2dadc693769792fbdc6bda529235d))
* **agents-login:** match the space-less paste prompt (credential hang) ([#734](https://github.com/JorisJonkers-dev/agent-runtime/issues/734)) ([f0a2c6e](https://github.com/JorisJonkers-dev/agent-runtime/commit/f0a2c6e69e2e18389e8f7d3eec2c235feafa8cd2))
* **agents-login:** paste the auth code, not the full URL, into setup-token ([#733](https://github.com/JorisJonkers-dev/agent-runtime/issues/733)) ([38cb8b5](https://github.com/JorisJonkers-dev/agent-runtime/commit/38cb8b57d2bf2438c54d1f5aa24606e67d0025bb))
* **agents-login:** pin OpenAI auth host to IPv4 so codex sign-in works (musl + broken IPv6) ([#745](https://github.com/JorisJonkers-dev/agent-runtime/issues/745)) ([790514a](https://github.com/JorisJonkers-dev/agent-runtime/commit/790514a5828096df4c51330ae56c635865112908))
* **agents-login:** prefer IPv4 so codex login can reach auth.openai.com ([#742](https://github.com/JorisJonkers-dev/agent-runtime/issues/742)) ([a63b7ef](https://github.com/JorisJonkers-dev/agent-runtime/commit/a63b7ef25e123bdb5c2c09c3a05e9fd1fc012489))
* **agents-login:** robust credential completion + lifecycle logging ([#732](https://github.com/JorisJonkers-dev/agent-runtime/issues/732)) ([2327201](https://github.com/JorisJonkers-dev/agent-runtime/commit/23272012e3f04a8c618aec85f7c745d25a902851))
* **agents-login:** stop redacting the authorize URL and resume an in-progress login ([#719](https://github.com/JorisJonkers-dev/agent-runtime/issues/719)) ([11e2834](https://github.com/JorisJonkers-dev/agent-runtime/commit/11e2834d5e53e584c637663ff9cf5c7ac195eaca))
* **agents-login:** submit the pasted code with a separate Enter keystroke ([#735](https://github.com/JorisJonkers-dev/agent-runtime/issues/735)) ([4932c6d](https://github.com/JorisJonkers-dev/agent-runtime/commit/4932c6d00bd5439ebb8050a9b90271d2d2cddc6a))
* **agents-login:** wait for a populated .credentials.json before capturing ([#744](https://github.com/JorisJonkers-dev/agent-runtime/issues/744)) ([4613fad](https://github.com/JorisJonkers-dev/agent-runtime/commit/4613fad07da3232135fab73aee9b13995244c29b))
* **agents-login:** widen Claude login PTY so the authorize URL keeps its state param ([#743](https://github.com/JorisJonkers-dev/agent-runtime/issues/743)) ([10a2674](https://github.com/JorisJonkers-dev/agent-runtime/commit/10a2674d5feb729639ff88750d554f1f6dfbec66))
* **agents-ui:** re-fit terminal when console layout changes ([#50](https://github.com/JorisJonkers-dev/agent-runtime/issues/50)) ([045d56f](https://github.com/JorisJonkers-dev/agent-runtime/commit/045d56f1a703125e83e1e0798536e966d7b0c95e))
* **agents:** build the agents-login image with repo-root-relative COPY paths ([#714](https://github.com/JorisJonkers-dev/agent-runtime/issues/714)) ([1c6fdc8](https://github.com/JorisJonkers-dev/agent-runtime/commit/1c6fdc8a421513c3480c51c0b9e3ed396d24ee33))
* **agents:** inject full Claude subscription credential into runners ([#121](https://github.com/JorisJonkers-dev/agent-runtime/issues/121)) ([d66dd02](https://github.com/JorisJonkers-dev/agent-runtime/commit/d66dd02ba9c71f92b29ea5f37c770b3e74772264))
* **agents:** persist Claude+Codex session state across runner Pod recreation ([#130](https://github.com/JorisJonkers-dev/agent-runtime/issues/130)) ([3562b61](https://github.com/JorisJonkers-dev/agent-runtime/commit/3562b611c331492b5a1190dfae673f1a629929b2))
* **agents:** reuse UID 1000 in the agents-login image instead of useradd ([#715](https://github.com/JorisJonkers-dev/agent-runtime/issues/715)) ([eb0265e](https://github.com/JorisJonkers-dev/agent-runtime/commit/eb0265ea841f0d6ca86e6f31ab6c75200d26319b))
* **sessions:** resume the prior Claude & Codex conversation on revival ([#78](https://github.com/JorisJonkers-dev/agent-runtime/issues/78)) ([7bcf31a](https://github.com/JorisJonkers-dev/agent-runtime/commit/7bcf31af0ed7990bad6fa6d205f1d6841b8653f3))


### Performance Improvements

* **gateway:** bound cold-attach transcript replay to a recent tail ([#44](https://github.com/JorisJonkers-dev/agent-runtime/issues/44)) ([c970bb3](https://github.com/JorisJonkers-dev/agent-runtime/commit/c970bb358af6f76ece5689f55240b20f518a45d0))
* **terminal:** coalesce output writes and ship fewer, larger frames ([#40](https://github.com/JorisJonkers-dev/agent-runtime/issues/40)) ([89d7055](https://github.com/JorisJonkers-dev/agent-runtime/commit/89d7055d74cb0e13750ab8fb85e4233263da4515))

## 0.16.0 (2026-06-28)

Baseline import for the split agent runtime images.
