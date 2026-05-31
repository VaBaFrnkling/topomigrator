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
scripts/nifi-ready.sh
```

## Configuracion

1. Crea `.env` en la raiz del proyecto a partir de `.env.example`.
2. Sustituye todos los valores de ejemplo por los reales de tu entorno.
3. Verifica especialmente las JDBC URLs, usuarios y passwords.
4. Usa en las JDBC URLs un host alcanzable desde donde corresponda:
   `run-topomigrator.sh` prueba `psql` desde el host y `docker compose` conecta desde contenedores.
   Si PostgreSQL corre en tu maquina con Docker Desktop, `host.docker.internal` suele servir dentro del contenedor.

Variables obligatorias:

- `NIFI_USERNAME`
- `NIFI_PASSWORD`
- `SOURCE_DB_JDBC_URL`
- `SOURCE_DB_USERNAME`
- `SOURCE_DB_PASSWORD`
- `TARGET_DB_JDBC_URL`
- `TARGET_DB_USERNAME`
- `TARGET_DB_PASSWORD`

Variables opcionales del runner:

- `NIFI_READY_TIMEOUT_SECONDS` para ampliar o reducir la espera maxima del readiness funcional de NiFi. Por defecto `360`.
- `TOPOMIGRATOR_WAIT_TIMEOUT_SECONDS` para ampliar o reducir la espera maxima de finalizacion del contenedor `topomigrator`. Por defecto `14400`.

Ejemplo:

```dotenv
NIFI_USERNAME=nifi_user
NIFI_PASSWORD=change_this_password

SOURCE_DB_JDBC_URL=jdbc:postgresql://db-host-or-ip:5432/topomigrator_source
SOURCE_DB_USERNAME=topomigrator_user
SOURCE_DB_PASSWORD=change_this_source_password

TARGET_DB_JDBC_URL=jdbc:postgresql://db-host-or-ip:5432/topomigrator_target
TARGET_DB_USERNAME=topomigrator_user
TARGET_DB_PASSWORD=change_this_target_password
```

## Ejecucion

```bash
bash run-topomigrator.sh
```

El runner:

- carga `.env` creado manualmente por el usuario
- valida que todas las variables obligatorias esten definidas
- valida `configs/` y `changelogs/`
- valida `flows/MainMigration.json`
- valida `scripts/nifi-ready.sh`
- comprueba conectividad PostgreSQL con `psql`
- limpia el stack Docker Compose al inicio y al final
- arranca `nifi` desde un estado limpio
- espera a que NiFi autentique y responda a `https://nifi:8443/nifi-api`
- arranca `topomigrator` cuando NiFi ya esta listo a nivel funcional
- espera a que `topomigrator` termine y conserva logs de diagnostico si falla

No crea usuarios, bases de datos ni reglas de red en PostgreSQL. Esa preparacion es externa al proyecto.

## Salidas

La ejecucion genera `outputs/logs/run-topomigrator-*.log`, `outputs/traces/summary.json`, trazas por tabla en `outputs/traces/tables/` y errores tecnicos en `outputs/errors/`.

Si se detectan ciclos de dependencias, TopoMigrator ejecuta las tablas que tengan un orden valido, marca las tablas del ciclo como `FAILED` y las dependientes como `BLOCKED`. En ese caso no crea un error de orquestacion por el ciclo.

## Build y tests

```bash
mvn package
mvn test
```
