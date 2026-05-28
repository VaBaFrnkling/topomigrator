# TopoMigrator

Orquestador de migraciones de datos con Java, Liquibase, NiFi y Docker Compose.

## Requisitos

- Java 17
- Maven
- Docker + Docker Compose
- `psql`
- PostgreSQL origen y destino ya preparados

## Estructura minima

```text
.env
configs/contract.yaml
configs/datasources.yaml
changelogs/tables/*.yaml
flows/MainMigration.json
run-topomigrator.sh
```

## Configuracion

1. Crea `.env` en la raiz del proyecto a partir de `.env.example`.
2. Sustituye todos los valores de ejemplo por los reales de tu entorno.
3. Verifica especialmente las JDBC URLs, usuarios y passwords.

Variables obligatorias:

- `NIFI_USERNAME`
- `NIFI_PASSWORD`
- `SOURCE_DB_JDBC_URL`
- `SOURCE_DB_USERNAME`
- `SOURCE_DB_PASSWORD`
- `TARGET_DB_JDBC_URL`
- `TARGET_DB_USERNAME`
- `TARGET_DB_PASSWORD`

Valores fijos del proyecto:

- `NIFI_BASE_URL=https://nifi:8443/nifi-api`
- `NIFI_ALLOW_INSECURE_LOCAL_TLS=true`
- `MIGRATION_CONFIG_PATH=/app/configs/contract.yaml`
- `DATASOURCES_CONFIG_PATH=/app/configs/datasources.yaml`
- `INCREMENTAL_STATE_PATH=/app/outputs/state/incremental-state.json`
- Los drivers y tipos de base de datos se resuelven con defaults desde `configs/datasources.yaml`

Ejemplo:

```dotenv
NIFI_USERNAME=nifi_user
NIFI_PASSWORD=change_this_password

SOURCE_DB_JDBC_URL=jdbc:postgresql://host.docker.internal:5432/topomigrator_source
SOURCE_DB_USERNAME=topomigrator_user
SOURCE_DB_PASSWORD=change_this_source_password

TARGET_DB_JDBC_URL=jdbc:postgresql://host.docker.internal:5432/topomigrator_target
TARGET_DB_USERNAME=topomigrator_user
TARGET_DB_PASSWORD=change_this_target_password
```

## Ejecucion

```bash
./run-topomigrator.sh
```

El runner:

- carga `.env` creado manualmente por el usuario
- valida que todas las variables obligatorias esten definidas
- valida `configs/` y `changelogs/`
- comprueba conectividad PostgreSQL con `psql`
- ejecuta `docker compose up --build --abort-on-container-exit`

No crea usuarios, bases de datos ni reglas de red en PostgreSQL. Esa preparacion es externa al proyecto.

## Build y tests

```bash
mvn package
mvn test
```
