import { redactValue } from './redact.js'

type Level = 'info' | 'warn' | 'error' | 'debug'

export interface Logger {
  info(msg: string, fields?: Record<string, unknown>): void
  warn(msg: string, fields?: Record<string, unknown>): void
  error(msg: string, fields?: Record<string, unknown>): void
  debug(msg: string, fields?: Record<string, unknown>): void
}

function emit(level: Level, msg: string, fields?: Record<string, unknown>): void {
  const line = {
    ts: new Date().toISOString(),
    level,
    msg,
    ...(fields ? (redactValue(fields) as Record<string, unknown>) : {}),
  }
  const out = JSON.stringify(line)
  if (level === 'error') {
    process.stderr.write(out + '\n')
  } else {
    process.stdout.write(out + '\n')
  }
}

export function createLogger(): Logger {
  return {
    info: (m, f) => emit('info', m, f),
    warn: (m, f) => emit('warn', m, f),
    error: (m, f) => emit('error', m, f),
    debug: (m, f) => {
      if (process.env.AGENTS_LOGIN_DEBUG === '1') {
        emit('debug', m, f)
      }
    },
  }
}
