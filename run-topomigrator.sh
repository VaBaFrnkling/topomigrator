#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$ROOT_DIR/.env"

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

mkdir -p "$LOG_DIR" "$TRACE_DIR" "$TRACE_DIR/tables" "$STATE_DIR" "$FLOW_DIR" "$ERROR_DIR"

log() {
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$RUN_LOG"
}

load_env_file() {
  if [[ ! -f "$ENV_FILE" ]]; then
    log "ERROR: falta $ENV_FILE"
    log "Crea .env manualmente a partir de .env.example antes de ejecutar el runner."
    exit 1
  fi

  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
}

require_env_var() {
  local variable_name="$1"
  if [[ -z "${!variable_name:-}" ]]; then
    log "ERROR: la variable $variable_name es obligatoria en $ENV_FILE"
    exit 1
  fi
}

validate_env_file() {
  local required_vars=(
    NIFI_USERNAME
    NIFI_PASSWORD
    SOURCE_DB_JDBC_URL
    SOURCE_DB_USERNAME
    SOURCE_DB_PASSWORD
    TARGET_DB_JDBC_URL
    TARGET_DB_USERNAME
    TARGET_DB_PASSWORD
  )

  local variable_name
  for variable_name in "${required_vars[@]}"; do
    require_env_var "$variable_name"
  done
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
  log "ENV_FILE=$ENV_FILE"
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

postgres_is_ready() {
  command -v psql >/dev/null 2>&1 &&
  postgres_jdbc_url_is_ready "$SOURCE_DB_JDBC_URL" "$SOURCE_DB_USERNAME" "$SOURCE_DB_PASSWORD" &&
  postgres_jdbc_url_is_ready "$TARGET_DB_JDBC_URL" "$TARGET_DB_USERNAME" "$TARGET_DB_PASSWORD"
}

postgres_jdbc_url_is_ready() {
  local jdbc_url="$1"
  local username="$2"
  local password="$3"

  [[ "$jdbc_url" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/([^?]+) ]] || return 1

  local host="${BASH_REMATCH[1]}"
  local port="${BASH_REMATCH[3]:-5432}"
  local database="${BASH_REMATCH[4]}"

  PGPASSWORD="$password" psql -h "$host" -p "$port" -U "$username" -d "$database" -c "SELECT 1;" >/dev/null 2>&1
}

log "Inicio run-topomigrator.sh"
log "ROOT_DIR=$ROOT_DIR"
log "ENV_FILE=$ENV_FILE"
log "RUN_LOG=$RUN_LOG"

load_env_file

run_step \
  "Validar raiz del proyecto" \
  "has_required_project_files" \
  "echo 'ERROR: ejecuta este script desde la raiz real de TopoMigrator.'; exit 1"

run_step \
  "Crear carpetas reales del proyecto" \
  "[[ -d '$CONFIG_DIR' && -d '$CHANGELOG_DIR' && -d '$LOG_DIR' && -d '$STATE_DIR' && -d '$TRACE_DIR/tables' ]]" \
  "mkdir -p '$CONFIG_DIR' '$CHANGELOG_DIR' '$LOG_DIR' '$STATE_DIR' '$TRACE_DIR/tables' '$FLOW_DIR' '$ERROR_DIR'"

run_step \
  "Validar .env" \
  "validate_env_file" \
  "exit 1"

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
  "echo 'ERROR: PostgreSQL no esta listo o las credenciales configuradas no funcionan.'; echo 'Prepara manualmente las bases/usuarios y vuelve a ejecutar el runner.'; exit 1"

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
