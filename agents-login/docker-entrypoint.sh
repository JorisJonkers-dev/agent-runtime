#!/usr/bin/env sh
set -eu

# Mode selected by AGENTS_LOGIN_MODE (controller|worker); first arg overrides.
MODE="${AGENTS_LOGIN_MODE:-controller}"
if [ "$#" -gt 0 ]; then
  case "$1" in
    controller|worker)
      MODE="$1"
      shift
      ;;
  esac
fi

export AGENTS_LOGIN_MODE="$MODE"
exec node /app/dist/index.js "$MODE" "$@"
