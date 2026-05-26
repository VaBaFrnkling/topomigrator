#!/usr/bin/env bash
# ============================================================
# 02-postgres-setup.sh - Usuario, BBDD y permisos PostgreSQL
# ============================================================
# Modifica PostgreSQL: crea rol, bases y permisos si faltan.
# No borra datos de tablas existentes.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/01-env.sh"

run_as_postgres() { sudo -u postgres psql -v ON_ERROR_STOP=1 "$@"; }

echo "==> Creando usuario $PG_USER si no existe"
run_as_postgres -d postgres <<SQL
DO \$\$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$PG_USER') THEN
      CREATE ROLE $PG_USER LOGIN PASSWORD '$PG_PASSWORD';
   ELSE
      ALTER ROLE $PG_USER WITH LOGIN PASSWORD '$PG_PASSWORD';
   END IF;
END
\$\$;
SQL

for DB in "$SOURCE_DB" "$TARGET_DB"; do
  echo "==> Creando base $DB si no existe"
  if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$DB'" | grep -q 1; then
    sudo -u postgres createdb -O "$PG_USER" "$DB"
  fi

  echo "==> Ajustando permisos y esquema public en $DB"
  run_as_postgres -d "$DB" <<SQL
GRANT ALL PRIVILEGES ON DATABASE $DB TO $PG_USER;
GRANT USAGE, CREATE ON SCHEMA public TO $PG_USER;
ALTER SCHEMA public OWNER TO $PG_USER;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO $PG_USER;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO $PG_USER;
SQL

done

echo "==> Comprobando conexión como $PG_USER"
PGPASSWORD="$PG_PASSWORD" psql -h 127.0.0.1 -p "$PG_PORT" -U "$PG_USER" -d "$SOURCE_DB" -c "SELECT current_database(), current_user;"
PGPASSWORD="$PG_PASSWORD" psql -h 127.0.0.1 -p "$PG_PORT" -U "$PG_USER" -d "$TARGET_DB" -c "SELECT current_database(), current_user;"
echo "OK: PostgreSQL preparado."
