#!/usr/bin/env bash
# ============================================================
# run-topomigrator.sh - Orquestador de TopoMigrator
# ============================================================
# Debe vivir en la raiz del proyecto, al mismo nivel que configs,
# changelogs, outputs, Dockerfile y docker-compose.yaml.
#
# Usa siempre las carpetas reales del proyecto:
#   configs/contract.yaml
#   configs/datasources.yaml
#   changelogs/tables/*.yaml
#   outputs/logs/
#   outputs/traces/
#
# No depende de datos de prueba externos. Usa configs/ y changelogs/tables/.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

normalize_host_path() {
  local path="$1"
  if command -v cygpath >/dev/null 2>&1 && [[ "$path" == *\\* || "$path" =~ ^[A-Za-z]: ]]; then
    cygpath -u "$path"
  else
    printf '%s\n' "$path"
  fi
}

SCRIPTS_DIR="$(normalize_host_path "${TOPOMIGRATOR_SCRIPTS_DIR:-$ROOT_DIR/scripts}")"
ENV_FILE="$ROOT_DIR/.env"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

CONFIG_DIR="$ROOT_DIR/configs"
CHANGELOG_DIR="$ROOT_DIR/changelogs/tables"
OUTPUTS_DIR="$ROOT_DIR/outputs"
LOG_DIR="$OUTPUTS_DIR/logs"
TRACE_DIR="$OUTPUTS_DIR/traces"
STATE_DIR="$OUTPUTS_DIR/state"
FLOW_DIR="$OUTPUTS_DIR/flows"
ERROR_DIR="$OUTPUTS_DIR/errors"

RUN_ID="$(date +%Y%m%d-%H%M%S)"
RUN_LOG="$LOG_DIR/run-topomigrator-$RUN_ID.log"

SOURCE_DB="${SOURCE_DB:-topomigrator_source}"
TARGET_DB="${TARGET_DB:-topomigrator_target}"
PG_USER="${PG_USER:-topomigrator_user}"
PG_PASSWORD="${PG_PASSWORD:-Topomigrator123!}"
PG_PORT="${PG_PORT:-5432}"
CONTAINER_DB_HOST="${CONTAINER_DB_HOST:-host.docker.internal}"
PG_HOST_FOR_CONTAINERS="${PG_HOST_FOR_CONTAINERS:-$CONTAINER_DB_HOST}"

SOURCE_DB_DRIVER="${SOURCE_DB_DRIVER:-org.postgresql.Driver}"
TARGET_DB_DRIVER="${TARGET_DB_DRIVER:-org.postgresql.Driver}"
SOURCE_DB_TYPE="${SOURCE_DB_TYPE:-PostgreSQL}"
TARGET_DB_TYPE="${TARGET_DB_TYPE:-PostgreSQL}"
SOURCE_DB_DRIVER_LOCATION="${SOURCE_DB_DRIVER_LOCATION:-/opt/nifi/drivers/postgresql-42.7.10.jar}"
TARGET_DB_DRIVER_LOCATION="${TARGET_DB_DRIVER_LOCATION:-/opt/nifi/drivers/postgresql-42.7.10.jar}"
SOURCE_DB_JDBC_URL="${SOURCE_DB_JDBC_URL:-jdbc:postgresql://${PG_HOST_FOR_CONTAINERS}:${PG_PORT}/${SOURCE_DB}}"
TARGET_DB_JDBC_URL="${TARGET_DB_JDBC_URL:-jdbc:postgresql://${PG_HOST_FOR_CONTAINERS}:${PG_PORT}/${TARGET_DB}}"
SOURCE_DB_USERNAME="${SOURCE_DB_USERNAME:-$PG_USER}"
SOURCE_DB_PASSWORD="${SOURCE_DB_PASSWORD:-$PG_PASSWORD}"
TARGET_DB_USERNAME="${TARGET_DB_USERNAME:-$PG_USER}"
TARGET_DB_PASSWORD="${TARGET_DB_PASSWORD:-$PG_PASSWORD}"

NIFI_USERNAME="${NIFI_USERNAME:-nifi_user}"
NIFI_PASSWORD="${NIFI_PASSWORD:-Topomigrator123!Topomigrator123!}"
NIFI_BASE_URL="${NIFI_BASE_URL:-https://nifi:8443/nifi-api}"
NIFI_ALLOW_INSECURE_LOCAL_TLS="${NIFI_ALLOW_INSECURE_LOCAL_TLS:-true}"

MIGRATION_CONFIG_PATH="${MIGRATION_CONFIG_PATH:-/app/configs/contract.yaml}"
DATASOURCES_CONFIG_PATH="${DATASOURCES_CONFIG_PATH:-/app/configs/datasources.yaml}"
INCREMENTAL_STATE_PATH="${INCREMENTAL_STATE_PATH:-/app/outputs/state/incremental-state.json}"

mkdir -p "$LOG_DIR" "$TRACE_DIR" "$TRACE_DIR/tables" "$STATE_DIR" "$FLOW_DIR" "$ERROR_DIR"

log() {
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$RUN_LOG"
}

run_script() {
  local script_name="$1"
  shift || true
  if [[ ! -f "$SCRIPTS_DIR/$script_name" ]]; then
    log "ERROR: falta scripts/$script_name en $SCRIPTS_DIR"
    exit 1
  fi
  log "Ejecutando script: scripts/$script_name $*"
  bash "$SCRIPTS_DIR/$script_name" "$@" 2>&1 | tee -a "$RUN_LOG"
}

run_step() {
  local description="$1"
  local check_command="$2"
  local action_command="$3"

  log "==> $description"

  if eval "$check_command"; then
    log "SKIP: $description ya estaba hecho."
    return 0
  fi

  eval "$action_command"
  log "OK: $description"
}

diagnose_on_failure() {
  local status=$?
  if [[ $status -eq 0 ]]; then
    return 0
  fi

  log "ERROR: run-topomigrator.sh fallo con estado $status."
  log "Diagnostico basico:"
  log "ROOT_DIR=$ROOT_DIR"
  log "CONFIG_DIR=$CONFIG_DIR"
  log "CHANGELOG_DIR=$CHANGELOG_DIR"
  log "OUTPUTS_DIR=$OUTPUTS_DIR"
  find "$OUTPUTS_DIR" -maxdepth 3 -type f -print 2>/dev/null | sort | tee -a "$RUN_LOG" || true
  (cd "$ROOT_DIR" && docker compose ps) 2>&1 | tee -a "$RUN_LOG" || true
  (cd "$ROOT_DIR" && docker compose logs --tail=120 topomigrator nifi) 2>&1 | tee -a "$RUN_LOG" || true
  exit "$status"
}
trap diagnose_on_failure EXIT

has_required_project_files() {
  [[ -f "$ROOT_DIR/Dockerfile" ]] &&
  [[ -f "$ROOT_DIR/docker-compose.yaml" || -f "$ROOT_DIR/docker-compose.yml" ]]
}

has_configs() {
  [[ -f "$CONFIG_DIR/contract.yaml" && -f "$CONFIG_DIR/datasources.yaml" ]]
}

has_changelogs() {
  find "$CHANGELOG_DIR" -maxdepth 1 -type f -name '*.yaml' -print -quit 2>/dev/null | grep -q .
}

env_is_current() {
  [[ -f "$ROOT_DIR/.env" ]] &&
  grep -Fq "MIGRATION_CONFIG_PATH=/app/configs/contract.yaml" "$ROOT_DIR/.env" &&
  grep -Fq "DATASOURCES_CONFIG_PATH=/app/configs/datasources.yaml" "$ROOT_DIR/.env" &&
  grep -Fq "INCREMENTAL_STATE_PATH=/app/outputs/state/incremental-state.json" "$ROOT_DIR/.env"
}

postgres_is_ready() {
  command -v psql >/dev/null 2>&1 &&
  PGPASSWORD="$PG_PASSWORD" psql -h 127.0.0.1 -p "$PG_PORT" -U "$PG_USER" -d "$SOURCE_DB" -c "SELECT 1;" >/dev/null 2>&1 &&
  PGPASSWORD="$PG_PASSWORD" psql -h 127.0.0.1 -p "$PG_PORT" -U "$PG_USER" -d "$TARGET_DB" -c "SELECT 1;" >/dev/null 2>&1
}

log "Inicio run-topomigrator.sh"
log "ROOT_DIR=$ROOT_DIR"
log "SCRIPTS_DIR=$SCRIPTS_DIR"
log "RUN_LOG=$RUN_LOG"

run_step \
  "Validar raiz del proyecto" \
  "has_required_project_files" \
  "echo 'ERROR: ejecuta este script desde la raiz real de TopoMigrator.'; exit 1"

run_step \
  "Crear carpetas reales del proyecto" \
  "[[ -d '$CONFIG_DIR' && -d '$CHANGELOG_DIR' && -d '$LOG_DIR' && -d '$STATE_DIR' && -d '$TRACE_DIR/tables' ]]" \
  "mkdir -p '$CONFIG_DIR' '$CHANGELOG_DIR' '$LOG_DIR' '$STATE_DIR' '$TRACE_DIR/tables' '$FLOW_DIR' '$ERROR_DIR'"

run_step \
  "Validar configs reales" \
  "has_configs" \
  "echo 'ERROR: faltan configs/contract.yaml o configs/datasources.yaml'; exit 1"

run_step \
  "Validar changelogs reales" \
  "has_changelogs" \
  "echo 'ERROR: changelogs/tables no contiene archivos .yaml'; exit 1"

run_step \
  "Comprobar PostgreSQL" \
  "postgres_is_ready" \
  "run_script '02-postgres-setup.sh'"

if [[ "${CONFIGURE_POSTGRES_DOCKER_ACCESS:-false}" == "true" ]]; then
  run_step \
    "Configurar acceso PostgreSQL desde Docker" \
    "false" \
    "run_script '03-postgres-docker-access.sh'"
else
  log "SKIP: 03-postgres-docker-access.sh no se ejecuta automaticamente. Usa CONFIGURE_POSTGRES_DOCKER_ACCESS=true si lo necesitas."
fi

run_step \
  "Generar .env con rutas del proyecto" \
  "env_is_current" \
  "run_script '04-write-env-file.sh'"

run_step \
  "Ejecutar TopoMigrator" \
  "false" \
  "cd '$ROOT_DIR' && docker compose up --build --abort-on-container-exit"

log "==> Comprobacion final"
find "$OUTPUTS_DIR" -maxdepth 3 -type f -print 2>/dev/null | sort | tee -a "$RUN_LOG" || true
grep -RhoE 'SUCCESS|FAILED|BLOCKED' "$TRACE_DIR" "$ERROR_DIR" 2>/dev/null | sort | uniq -c | tee -a "$RUN_LOG" || log "AVISO: no encontre estados en trazas o errores."
[[ -f "$STATE_DIR/incremental-state.json" ]] && log "OK: existe estado incremental." || log "INFO: no existe estado incremental."

log "OK: run-topomigrator.sh finalizado."
trap - EXIT
