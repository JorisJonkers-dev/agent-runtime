# agent-runtime

Builds the agent runtime images:

- `ghcr.io/jorisjonkers-dev/agent-runtime/agent-gateway`
- `ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner`
- `ghcr.io/jorisjonkers-dev/agent-runtime/agents-login`

The repo contains the JVM gateway, the runner image, and the agents-login Node
worker. The runner consumes the published agent-kit runtime-home OCI artifact
instead of vendoring agent-kit files in this repository.

## Validation

```bash
./gradlew :services:agent-gateway:check :services:agent-gateway:bootJar --no-daemon
cd services/agents-login
npm ci
npm run lint
npm run typecheck
npm run build
npm test
```
