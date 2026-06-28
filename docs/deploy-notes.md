# Deploy Notes

Runner setup hashes include the setup version, image, image pull policy, service
account, knowledge settings, MCP server ConfigMap, default MCP profile,
connector config, tool profiles, Docker socket settings, and node selector.

Do not reintroduce Kubernetes MCP by editing `default@1` in place. Add a new
setup version so existing durable sessions retain their stable hash.

```yaml
agent-runtime:
  setups:
    - id: default
      version: 1
      display-name: Default runner
      default-selectable: false
      default-mcp-profile: minimal
      image: ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner:v0.16.0
    - id: default
      version: 2
      display-name: Default runner with cluster MCP
      default-selectable: true
      default-mcp-profile: cluster
      image: ghcr.io/jorisjonkers-dev/agent-runtime/agent-runner:v0.16.0
```
