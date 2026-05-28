#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

normalize_host_path() {
  local path="$1"
  if command -v cygpath >/dev/null 2>&1 && [[ "$path" == *\\* || "$path" =~ ^[A-Za-z]: ]]; then
    cygpath -u "$path"
  else
    printf '%s\n' "$path"
  fi
}

export BASE_DIR="$(normalize_host_path "${BASE_DIR:-$PROJECT_ROOT}")"

export CONTAINER_DB_HOST="${CONTAINER_DB_HOST:-host.docker.internal}"

export SOURCE_DB="${SOURCE_DB:-topomigrator_source}"
export TARGET_DB="${TARGET_DB:-topomigrator_target}"
export PG_USER="${PG_USER:-topomigrator_user}"
export PG_PASSWORD="${PG_PASSWORD:-Topomigrator123!}"
export PG_HOST_FOR_CONTAINERS="${PG_HOST_FOR_CONTAINERS:-$CONTAINER_DB_HOST}"
export PG_PORT="${PG_PORT:-5432}"

export SOURCE_DB_DRIVER="${SOURCE_DB_DRIVER:-org.postgresql.Driver}"
export TARGET_DB_DRIVER="${TARGET_DB_DRIVER:-org.postgresql.Driver}"
export SOURCE_DB_TYPE="${SOURCE_DB_TYPE:-PostgreSQL}"
export TARGET_DB_TYPE="${TARGET_DB_TYPE:-PostgreSQL}"
export SOURCE_DB_JDBC_URL="${SOURCE_DB_JDBC_URL:-jdbc:postgresql://${PG_HOST_FOR_CONTAINERS}:${PG_PORT}/${SOURCE_DB}}"
export TARGET_DB_JDBC_URL="${TARGET_DB_JDBC_URL:-jdbc:postgresql://${PG_HOST_FOR_CONTAINERS}:${PG_PORT}/${TARGET_DB}}"
export SOURCE_DB_USERNAME="${SOURCE_DB_USERNAME:-$PG_USER}"
export SOURCE_DB_PASSWORD="${SOURCE_DB_PASSWORD:-$PG_PASSWORD}"
export TARGET_DB_USERNAME="${TARGET_DB_USERNAME:-$PG_USER}"
export TARGET_DB_PASSWORD="${TARGET_DB_PASSWORD:-$PG_PASSWORD}"

export NIFI_USERNAME="${NIFI_USERNAME:-nifi_user}"
export NIFI_PASSWORD="${NIFI_PASSWORD:-Topomigrator123!Topomigrator123!}"
export NIFI_BASE_URL="${NIFI_BASE_URL:-https://nifi:8443/nifi-api}"
export NIFI_ALLOW_INSECURE_LOCAL_TLS="${NIFI_ALLOW_INSECURE_LOCAL_TLS:-true}"
export SOURCE_DB_DRIVER_LOCATION="${SOURCE_DB_DRIVER_LOCATION:-/opt/nifi/drivers/postgresql-42.7.10.jar}"
export TARGET_DB_DRIVER_LOCATION="${TARGET_DB_DRIVER_LOCATION:-/opt/nifi/drivers/postgresql-42.7.10.jar}"

export MIGRATION_CONFIG_PATH="${MIGRATION_CONFIG_PATH:-/app/configs/contract.yaml}"
export DATASOURCES_CONFIG_PATH="${DATASOURCES_CONFIG_PATH:-/app/configs/datasources.yaml}"
export INCREMENTAL_STATE_PATH="${INCREMENTAL_STATE_PATH:-/app/outputs/state/incremental-state.json}"
