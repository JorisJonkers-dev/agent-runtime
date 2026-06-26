import type { PtyProcess, PtySpawner, PtySpawnOptions } from '../../src/worker/pty.js'

/**
 * Fake PTY whose script is a list of programmed actions: emit output, or wait
 * for a stdin write matching a predicate before continuing. Mirrors how a real
 * CLI would block on the PTY for the redirect-URL handshake — with no real
 * binary, network, or node-pty addon.
 */
export type Action =
  | { type: 'emit'; data: string }
  | { type: 'effect'; run: () => void }
  | { type: 'expectStdin'; match: (input: string) => boolean; then: Action[] }
  | { type: 'exit'; code: number }

export class FakePty implements PtyProcess {
  private dataCbs: Array<(c: string) => void> = []
  private exitCbs: Array<(i: { exitCode: number; signal?: number }) => void> = []
  private pendingExpect?: { match: (i: string) => boolean; then: Action[] }
  killed = false
  readonly writes: string[] = []

  constructor(private readonly script: Action[]) {}

  run(): void {
    // Run synchronously-ish on the microtask queue so listeners are attached.
    queueMicrotask(() => this.drain(this.script))
  }

  private drain(actions: Action[]): void {
    for (let i = 0; i < actions.length; i += 1) {
      const a = actions[i]
      if (a.type === 'emit') {
        this.dataCbs.forEach((cb) => cb(a.data))
      } else if (a.type === 'effect') {
        a.run()
      } else if (a.type === 'exit') {
        this.exitCbs.forEach((cb) => cb({ exitCode: a.code }))
        return
      } else if (a.type === 'expectStdin') {
        // Pause until matching stdin arrives, then run the nested actions.
        this.pendingExpect = { match: a.match, then: a.then }
        return
      }
    }
  }

  onData(cb: (c: string) => void): void {
    this.dataCbs.push(cb)
  }

  onExit(cb: (i: { exitCode: number; signal?: number }) => void): void {
    this.exitCbs.push(cb)
  }

  write(data: string): void {
    this.writes.push(data)
    if (this.pendingExpect && this.pendingExpect.match(data)) {
      const next = this.pendingExpect.then
      this.pendingExpect = undefined
      queueMicrotask(() => this.drain(next))
    }
  }

  kill(): void {
    this.killed = true
  }
}

export function fakeSpawner(scriptFor: (file: string, args: string[]) => Action[]): {
  spawner: PtySpawner
  instances: FakePty[]
  spawns: Array<{ file: string; args: string[]; options: PtySpawnOptions }>
} {
  const instances: FakePty[] = []
  const spawns: Array<{ file: string; args: string[]; options: PtySpawnOptions }> = []
  const spawner: PtySpawner = (file, args, options) => {
    spawns.push({ file, args, options })
    const pty = new FakePty(scriptFor(file, args))
    instances.push(pty)
    pty.run()
    return pty
  }
  return { spawner, instances, spawns }
}
