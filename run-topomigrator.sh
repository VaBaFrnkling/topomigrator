#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$ROOT_DIR/.env"

CONFIG_DIR="$ROOT_DIR/configs"
CHANGELOG_DIR="$ROOT_DIR/changelogs/tables"
FLOW_TEMPLATE="$ROOT_DIR/flows/MainMigration.json"
OUTPUTS_DIR="$ROOT_DIR/outputs"
LOG_DIR="$OUTPUTS_DIR/logs"
TRACE_DIR="$OUTPUTS_DIR/traces"
STATE_DIR="$OUTPUTS_DIR/state"
ERROR_DIR="$OUTPUTS_DIR/errors"
NIFI_READY_SCRIPT_HOST="$ROOT_DIR/scripts/nifi-ready.sh"
NIFI_READY_SCRIPT_CONTAINER="/opt/nifi/scripts/nifi-ready.sh"
NIFI_READY_TIMEOUT_SECONDS="${NIFI_READY_TIMEOUT_SECONDS:-360}"
TOPOMIGRATOR_WAIT_TIMEOUT_SECONDS="${TOPOMIGRATOR_WAIT_TIMEOUT_SECONDS:-14400}"

RUN_ID="$(date +%Y%m%d-%H%M%S)"
RUN_LOG="$LOG_DIR/run-topomigrator-$RUN_ID.log"

mkdir -p "$LOG_DIR" "$TRACE_DIR" "$TRACE_DIR/tables" "$STATE_DIR" "$ERROR_DIR"

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
    log "OK: $description"
    return 0
  fi

  eval "$action_command"
  log "OK: $description"
}

compose() {
  (cd "$ROOT_DIR" && docker compose "$@")
}

compose_logged() {
  compose "$@" 2>&1 | tee -a "$RUN_LOG"
  return "${PIPESTATUS[0]}"
}

cleanup_compose_stack() {
  local reason="$1"
  log "==> Cleanup Docker Compose ($reason)"
  if compose_logged down --remove-orphans --volumes --timeout 60; then
    log "OK: stack Docker Compose limpio ($reason)"
  else
    log "AVISO: docker compose down devolvio error durante $reason."
  fi
}

get_service_container_id() {
  compose ps -q "$1" 2>/dev/null | head -n 1 | tr -d '\r\n'
}

inspect_container_state() {
  docker inspect \
    --format '{{.State.Status}}|{{.State.ExitCode}}|{{.State.Running}}|{{.State.Restarting}}|{{.State.Dead}}' \
    "$1" 2>/dev/null | head -n 1 | tr -d '\r'
}

normalize_container_status() {
  local status="$1"
  local running="$2"
  local restarting="$3"
  local dead="$4"

  if [[ -n "$status" ]]; then
    printf '%s' "$status"
    return 0
  fi

  if [[ "$dead" == "true" ]]; then
    printf 'dead'
  elif [[ "$restarting" == "true" ]]; then
    printf 'restarting'
  elif [[ "$running" == "true" ]]; then
    printf 'running'
  else
    printf 'unknown'
  fi
}

compose_service_has_exited() {
  local container_id
  local inspect_data
  local status
  local running
  local restarting
  local dead

  container_id="$(get_service_container_id "$1")"
  [[ -n "$container_id" ]] || return 1

  inspect_data="$(inspect_container_state "$container_id")" || return 1
  [[ -n "$inspect_data" ]] || return 1

  IFS='|' read -r status _ running restarting dead <<<"$inspect_data"
  status="$(normalize_container_status "$status" "$running" "$restarting" "$dead")"
  [[ "$status" == "exited" || "$status" == "dead" ]]
}

wait_for_nifi_functional_readiness() {
  local deadline=$((SECONDS + NIFI_READY_TIMEOUT_SECONDS))
  local attempt=0

  log "==> Esperar a NiFi listo a nivel funcional"

  while (( SECONDS < deadline )); do
    attempt=$((attempt + 1))

    if compose exec -T nifi bash "$NIFI_READY_SCRIPT_CONTAINER" >/dev/null 2>&1; then
      log "OK: NiFi autentica y responde a la API."
      return 0
    fi

    if compose_service_has_exited nifi; then
      log "ERROR: NiFi se detuvo antes de quedar listo."
      compose_logged ps || true
      compose_logged logs --tail=200 nifi || true
      return 1
    fi

    if (( attempt == 1 || attempt % 6 == 0 )); then
      log "NiFi aun no esta listo. Reintentando readiness funcional..."
    fi

    sleep 5
  done

  log "ERROR: timeout esperando a que NiFi quede listo a nivel funcional."
  compose_logged ps || true
  compose_logged logs --tail=200 nifi || true
  return 1
}

start_nifi_clean() {
  log "==> Arrancar NiFi desde estado limpio"
  compose_logged up --build --detach --force-recreate --renew-anon-volumes nifi
  wait_for_nifi_functional_readiness
}

start_topomigrator() {
  log "==> Arrancar TopoMigrator"
  compose_logged up --build --detach --force-recreate --no-deps topomigrator
}

wait_for_topomigrator_exit() {
  local deadline=$((SECONDS + TOPOMIGRATOR_WAIT_TIMEOUT_SECONDS))
  local attempt=0
  local container_id
  local last_seen_container_id=""
  local inspect_data
  local state
  local exit_code
  local running
  local restarting
  local dead
  local saw_container=0

  log "==> Esperar a que TopoMigrator termine"

  while (( SECONDS < deadline )); do
    attempt=$((attempt + 1))
    container_id="$(get_service_container_id topomigrator)"

    if [[ -z "$container_id" ]]; then
      if (( saw_container == 0 )); then
        if (( attempt == 1 || attempt % 12 == 0 )); then
          log "TopoMigrator aun no tiene contenedor visible en Docker Compose."
        fi
        sleep 5
        continue
      fi

      inspect_data="$(inspect_container_state "$last_seen_container_id")" || inspect_data=""
      if [[ -z "$inspect_data" ]]; then
        log "ERROR: Docker Compose ha dejado de exponer topomigrator y el ultimo contenedor conocido ya no puede inspeccionarse."
        compose_logged ps || true
        compose_logged logs --tail=200 topomigrator nifi || true
        return 1
      fi

      IFS='|' read -r state exit_code running restarting dead <<<"$inspect_data"
      state="$(normalize_container_status "$state" "$running" "$restarting" "$dead")"

      case "$state" in
        exited|dead)
          [[ "$exit_code" =~ ^[0-9]+$ ]] || exit_code=1
          log "TopoMigrator finalizo con codigo $exit_code."
          return "$exit_code"
          ;;
        running|restarting|created)
          if (( attempt == 1 || attempt % 12 == 0 )); then
            log "AVISO: Docker Compose ya no lista topomigrator, pero el ultimo contenedor conocido sigue con estado=$state."
          fi
          ;;
        *)
          log "AVISO: estado inesperado del ultimo contenedor conocido de topomigrator: $state"
          ;;
      esac

      sleep 5
      continue
    fi

    if [[ "$container_id" != "$last_seen_container_id" ]]; then
      last_seen_container_id="$container_id"
      saw_container=1
      log "TopoMigrator visible en Docker (contenedor=${container_id:0:12})."
    fi

    inspect_data="$(inspect_container_state "$container_id")" || inspect_data=""
    if [[ -z "$inspect_data" ]]; then
      if (( attempt == 1 || attempt % 12 == 0 )); then
        log "AVISO: no pude inspeccionar el contenedor real de topomigrator (${container_id:0:12}). Reintentando..."
      fi
      sleep 5
      continue
    fi

    IFS='|' read -r state exit_code running restarting dead <<<"$inspect_data"
    state="$(normalize_container_status "$state" "$running" "$restarting" "$dead")"

    case "$state" in
      exited|dead)
        [[ "$exit_code" =~ ^[0-9]+$ ]] || exit_code=1
        log "TopoMigrator finalizo con codigo $exit_code."
        return "$exit_code"
        ;;
      running|restarting|created)
        if (( attempt == 1 || attempt % 12 == 0 )); then
          log "TopoMigrator sigue en ejecucion (estado=$state)."
        fi
        ;;
      "")
        log "AVISO: aun no hay estado visible de Docker Compose para topomigrator."
        ;;
      *)
        log "AVISO: estado inesperado de topomigrator: $state"
        ;;
    esac

    sleep 5
  done

  log "ERROR: timeout esperando a que TopoMigrator termine."
  compose_logged ps || true
  compose_logged logs --tail=200 topomigrator nifi || true
  return 1
}

finalize_run() {
  local status=$?

  if [[ $status -ne 0 ]]; then
    log "ERROR: run-topomigrator.sh fallo con estado $status."
    log "Diagnostico basico:"
    log "ROOT_DIR=$ROOT_DIR"
    log "ENV_FILE=$ENV_FILE"
    log "CONFIG_DIR=$CONFIG_DIR"
    log "CHANGELOG_DIR=$CHANGELOG_DIR"
    log "OUTPUTS_DIR=$OUTPUTS_DIR"
    find "$OUTPUTS_DIR" -maxdepth 3 -type f -print 2>/dev/null | sort | tee -a "$RUN_LOG" || true
    compose_logged ps || true
    compose_logged logs --tail=200 topomigrator nifi || true
  fi

  cleanup_compose_stack "final"
  exit "$status"
}
trap finalize_run EXIT

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

has_flow_template() {
  [[ -f "$FLOW_TEMPLATE" ]]
}

has_nifi_ready_script() {
  [[ -f "$NIFI_READY_SCRIPT_HOST" ]]
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

  if [[ "$host" == "host.docker.internal" ]]; then
    log "AVISO: se omite la comprobacion con psql para $jdbc_url porque host.docker.internal puede no resolver en el host."
    return 0
  fi

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
  "mkdir -p '$CONFIG_DIR' '$CHANGELOG_DIR' '$LOG_DIR' '$STATE_DIR' '$TRACE_DIR/tables' '$ERROR_DIR'"

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
  "Validar plantilla base de NiFi" \
  "has_flow_template" \
  "echo 'ERROR: falta flows/MainMigration.json'; exit 1"

run_step \
  "Validar script de readiness funcional de NiFi" \
  "has_nifi_ready_script" \
  "echo 'ERROR: falta scripts/nifi-ready.sh'; exit 1"

run_step \
  "Comprobar PostgreSQL" \
  "postgres_is_ready" \
  "echo 'ERROR: PostgreSQL no esta listo o las credenciales configuradas no funcionan.'; echo 'Prepara manualmente las bases/usuarios y vuelve a ejecutar el runner.'; exit 1"

cleanup_compose_stack "inicio"
start_nifi_clean
start_topomigrator
TOPOMIGRATOR_EXIT_CODE=0
if wait_for_topomigrator_exit; then
  TOPOMIGRATOR_EXIT_CODE=0
else
  TOPOMIGRATOR_EXIT_CODE=$?
fi

log "==> Logs finales Docker Compose"
compose_logged logs --tail=120 topomigrator nifi || true

if [[ $TOPOMIGRATOR_EXIT_CODE -ne 0 ]]; then
  log "ERROR: TopoMigrator termino con codigo $TOPOMIGRATOR_EXIT_CODE."
  exit "$TOPOMIGRATOR_EXIT_CODE"
fi

log "==> Comprobacion final"
find "$OUTPUTS_DIR" -maxdepth 3 -type f -print 2>/dev/null | sort | tee -a "$RUN_LOG" || true
grep -RhoE 'SUCCESS|FAILED|BLOCKED' "$TRACE_DIR" "$ERROR_DIR" 2>/dev/null | sort | uniq -c | tee -a "$RUN_LOG" || log "AVISO: no encontre estados en trazas o errores."
[[ -f "$STATE_DIR/incremental-state.json" ]] && log "OK: existe estado incremental." || log "INFO: no existe estado incremental."

log "OK: run-topomigrator.sh finalizado."
