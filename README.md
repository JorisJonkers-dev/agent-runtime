# agent-runtime

Agent runtime images and deploy bundle metadata for JorisJonkers-dev agent
workloads.

## What It Is

`agent-runtime` builds the JVM agent gateway, the agent-runner image, and the
agents-login credential worker. The runner image contains the gateway jar,
Claude Code, Codex, GitHub tooling, language runtimes, and the published
agent-kit runtime-home artifact.

## Local Use

```bash
./gradlew :services:agent-gateway:check :services:agent-gateway:bootJar --no-daemon
cd services/agents-login
npm ci
npm run lint
npm run typecheck
npm run build
npm test
```

## Package

Runtime images:

```text
ghcr.io/jorisjonkers-dev/agent-runtime/agent-gateway:<version>
ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner:<version>
ghcr.io/jorisjonkers-dev/agent-runtime/agents-login:<version>
```

Deploy bundle:

```text
ghcr.io/jorisjonkers-dev/agent-runtime-deploy-bundle:<version>
```

## Related

- Agent API: <https://github.com/JorisJonkers-dev/agents-api>
- Agent UI: <https://github.com/JorisJonkers-dev/agents-ui>
- Agent kit: <https://github.com/JorisJonkers-dev/agent-kit>

## Links

- [Organization profile](https://github.com/JorisJonkers-dev)
- [Security policy](https://github.com/JorisJonkers-dev/.github/security/policy)
- [Changelog](./CHANGELOG.md)
- [License](./LICENSE)

Copyright (c) Joris Jonkers. Source available for viewing only; use, copying,
modification, redistribution, deployment, or reuse is not licensed. See
[LICENSE](./LICENSE).
