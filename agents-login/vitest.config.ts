import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'text-summary'],
      include: ['src/**/*.ts'],
      // Entrypoints wire real Vault / node-pty / network and are exercised via
      // their composed units; they hold no branching logic worth gating on.
      exclude: ['src/index.ts', 'src/**/index.ts', 'src/worker/pty.ts'],
      thresholds: {
        lines: 80,
        functions: 80,
        branches: 80,
        statements: 80,
      },
    },
  },
})
