#!/usr/bin/env bash
# ============================================================
# 03-postgres-docker-access.sh - Acceso PostgreSQL desde Docker
# ============================================================
# Modifica postgresql.conf y pg_hba.conf para permitir conexiones
# desde contenedores Docker hacia PostgreSQL en el host.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/01-env.sh"

CONF_FILE="$(sudo -u postgres psql -tAc "SHOW config_file;" | xargs)"
HBA_FILE="$(sudo -u postgres psql -tAc "SHOW hba_file;" | xargs)"
BACKUP_SUFFIX=".bak.$(date +%Y%m%d%H%M%S)"

echo "==> postgresql.conf: $CONF_FILE"
echo "==> pg_hba.conf: $HBA_FILE"
sudo cp "$CONF_FILE" "${CONF_FILE}${BACKUP_SUFFIX}"
sudo cp "$HBA_FILE" "${HBA_FILE}${BACKUP_SUFFIX}"

echo "==> Activando listen_addresses='*'"
if sudo grep -Eq "^#?listen_addresses" "$CONF_FILE"; then
  sudo sed -i "s/^#\?listen_addresses.*/listen_addresses = '*'/" "$CONF_FILE"
else
  echo "listen_addresses = '*'" | sudo tee -a "$CONF_FILE" >/dev/null
fi

add_hba_line() {
  local cidr="$1"
  local line="host    all             $PG_USER             $cidr               scram-sha-256"
  if ! sudo grep -Fq "$line" "$HBA_FILE"; then
    echo "$line" | sudo tee -a "$HBA_FILE" >/dev/null
  fi
}

echo "==> Permitendo rangos típicos Docker y la IP de la VM"
add_hba_line "172.16.0.0/12"
add_hba_line "10.0.0.0/8"
if [[ "$PG_HOST_FOR_CONTAINERS" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  add_hba_line "$PG_HOST_FOR_CONTAINERS/32"
else
  echo "INFO: PG_HOST_FOR_CONTAINERS=$PG_HOST_FOR_CONTAINERS no es una IP IPv4; se omite regla /32 especifica."
fi

echo "==> Reiniciando PostgreSQL"
sudo systemctl restart postgresql
sleep 2

echo "==> Comprobando escucha en 5432"
ss -lntp | grep ':5432' || true

echo "==> Comprobando conexion por host de contenedores, no localhost"
PGPASSWORD="$PG_PASSWORD" psql -h "$PG_HOST_FOR_CONTAINERS" -p "$PG_PORT" -U "$PG_USER" -d "$SOURCE_DB" -c "SELECT 'source ok' AS status;"
PGPASSWORD="$PG_PASSWORD" psql -h "$PG_HOST_FOR_CONTAINERS" -p "$PG_PORT" -U "$PG_USER" -d "$TARGET_DB" -c "SELECT 'target ok' AS status;"
echo "OK: PostgreSQL acepta conexiones desde Docker."
