// Thin abstraction over a pseudo-terminal child process so the session logic
// can be tested without the native node-pty addon. The real implementation
// lazily loads node-pty; tests inject a fake PtySpawner.

export interface PtyProcess {
  onData(cb: (chunk: string) => void): void
  onExit(cb: (info: { exitCode: number; signal?: number }) => void): void
  write(data: string): void
  kill(signal?: string): void
}

export interface PtySpawnOptions {
  cwd: string
  env: NodeJS.ProcessEnv
  cols?: number
  rows?: number
}

export type PtySpawner = (file: string, args: string[], options: PtySpawnOptions) => PtyProcess

/** Real node-pty backed spawner. Loaded lazily so test runs need no addon. */
export async function createNodePtySpawner(): Promise<PtySpawner> {
  const pty = await import('node-pty')
  return (file, args, options) => {
    const proc = pty.spawn(file, args, {
      name: 'xterm-color',
      cols: options.cols ?? 120,
      rows: options.rows ?? 40,
      cwd: options.cwd,
      env: options.env as { [key: string]: string },
    })
    return {
      onData: (cb) => proc.onData(cb),
      onExit: (cb) => proc.onExit(cb),
      write: (data) => proc.write(data),
      kill: (signal) => proc.kill(signal),
    }
  }
}
