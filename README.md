# TopoMigrator

Orquestador de migraciones de datos con Java, Liquibase, NiFi y Docker Compose.

## Requisitos

- Java 17
- Maven
- Docker + Docker Compose
- `psql`
- PostgreSQL origen y destino ya preparados

## Estructura mínima

```text
configs/contract.yaml
configs/datasources.yaml
changelogs/tables/*.yaml
flows/MainMigration.json
run-topomigrator.sh
```

## Ejecución

```bash
./run-topomigrator.sh
```

El runner:

- valida `configs/` y `changelogs/`
- comprueba conectividad PostgreSQL con `psql`
- regenera `.env` con `scripts/02-write-env-file.sh`
- ejecuta `docker compose up --build --abort-on-container-exit`

No crea usuarios, bases de datos ni reglas de red en PostgreSQL. Esa preparación es externa al proyecto.

## Scripts

- `scripts/01-env.sh`: defaults compartidos
- `scripts/02-write-env-file.sh`: genera `.env`

## Build y tests

```bash
mvn package
mvn test
```
