#!/bin/sh
set -eu

append_otel_resource_attribute() {
  key="$1"
  value="$2"

  if [ -z "$value" ]; then
    return
  fi

  case ",${OTEL_RESOURCE_ATTRIBUTES:-}," in
    *",${key}="*) return ;;
  esac

  if [ -n "${OTEL_RESOURCE_ATTRIBUTES:-}" ]; then
    export OTEL_RESOURCE_ATTRIBUTES="${OTEL_RESOURCE_ATTRIBUTES},${key}=${value}"
  else
    export OTEL_RESOURCE_ATTRIBUTES="${key}=${value}"
  fi
}

configure_otel_resource_attributes() {
  append_otel_resource_attribute "service.version" "${SERVICE_VERSION:-unknown}"
  append_otel_resource_attribute "deployment.environment" "${DEPLOYMENT_ENVIRONMENT:-unknown}"
}

if [ -z "${OTEL_SERVICE_NAME:-}" ]; then
  export OTEL_SERVICE_NAME="agent-gateway"
fi
configure_otel_resource_attributes

exec java \
  -XX:+UseZGC \
  -XX:MaxRAMPercentage=75 \
  -javaagent:/app/otel-javaagent.jar \
  -jar /app/agent-gateway.jar
