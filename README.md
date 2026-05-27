# TopoMigrator

TopoMigrator is a Java command-line tool for declarative batch migrations between relational databases. In this project it is validated with PostgreSQL as source and target, Apache NiFi as the data movement engine, Liquibase for target schema creation, and JSON traces for auditability.

The tool is not a general ETL platform. It does not provide a UI, a public API, CDC, real-time streaming, or complex data transformations. Its purpose is narrower: given a migration contract, it validates the inputs, prepares the target schema, computes table dependencies, executes the table migrations in a safe order, and leaves evidence of what happened.

## Quick Start

The normal way to run the project is the root script:

```bash
./run-topomigrator.sh
```

Before running it, prepare these three things:

1. Create `.env` in the project root, or export the same variables before running the script.
2. Put the migration YAML files in `configs/`.
3. Put one Liquibase changelog per target table in `changelogs/tables/`.

Minimum expected layout:

```text
topomigrator/
  .env
  run-topomigrator.sh
  docker-compose.yaml
  Dockerfile
  configs/
    contract.yaml
    datasources.yaml
  changelogs/
    tables/
      public.customers.yaml
      public.orders.yaml
  flows/
    MainMigration.json
  outputs/
```

The script loads `.env` when it exists, validates the project structure, checks that the required configuration and changelog files are present, prepares local PostgreSQL databases when needed, ensures `.env` has the container paths expected by Docker Compose, runs the migration with Docker Compose, and then checks the generated results. The Java application validates the YAML contents during startup.

Requirements:

- Bash: Linux, WSL, or Git Bash.
- Docker with `docker compose`.
- PostgreSQL reachable from the host and from the Docker containers.
- `psql` available on the host for the current wrapper checks and PostgreSQL setup helper.
- Java 17 and Maven 3.x if you want to build or test locally outside Docker.

On Windows, use WSL or Git Bash. The script is not a native PowerShell or `cmd` script.

## What The Script Does

`run-topomigrator.sh` is the one-shot orchestrator. It lives in the repository root and should be the only script a normal user needs to execute.

Effective flow:

1. Validates that it is running from a real TopoMigrator project root.
2. Creates required output folders under `outputs/`.
3. Checks that `configs/contract.yaml` and `configs/datasources.yaml` exist.
4. Checks that `changelogs/tables/` contains YAML changelogs.
5. Loads `.env` values for the runner, helper scripts, and Docker Compose.
6. Checks PostgreSQL connectivity using `psql`.
7. If PostgreSQL is not ready, runs `scripts/02-postgres-setup.sh`.
8. Optionally runs `scripts/03-postgres-docker-access.sh` when `CONFIGURE_POSTGRES_DOCKER_ACCESS=true`.
9. Creates or refreshes `.env` through `scripts/04-write-env-file.sh` when needed.
10. Runs `docker compose up --build --abort-on-container-exit`.
11. Prints a basic summary of generated outputs, trace statuses, and incremental state.
12. If something fails, prints a basic diagnostic with paths, Docker Compose status, and recent container logs.

The script resolves paths from its own location, not from the shell's current directory. Paths are quoted, and Windows-style paths passed through variables are normalized with `cygpath` when available.

Important: `scripts/02-postgres-setup.sh` and `scripts/03-postgres-docker-access.sh` use Linux administration commands such as `sudo`, `systemctl`, and the `postgres` system user. If your PostgreSQL is managed differently, create the databases/users yourself and provide the connection data in `.env`.

## Environment File

Create `.env` in the project root. Do not commit it.

Example for the Docker Compose setup:

```env
SOURCE_DB_DRIVER=org.postgresql.Driver
SOURCE_DB_DRIVER_LOCATION=/opt/nifi/drivers/postgresql-42.7.10.jar
SOURCE_DB_TYPE=PostgreSQL
SOURCE_DB_JDBC_URL=jdbc:postgresql://host.docker.internal:5432/topomigrator_source
SOURCE_DB_USERNAME=topomigrator_user
SOURCE_DB_PASSWORD=Topomigrator123!

TARGET_DB_DRIVER=org.postgresql.Driver
TARGET_DB_DRIVER_LOCATION=/opt/nifi/drivers/postgresql-42.7.10.jar
TARGET_DB_TYPE=PostgreSQL
TARGET_DB_JDBC_URL=jdbc:postgresql://host.docker.internal:5432/topomigrator_target
TARGET_DB_USERNAME=topomigrator_user
TARGET_DB_PASSWORD=Topomigrator123!

NIFI_BASE_URL=https://nifi:8443/nifi-api
NIFI_USERNAME=nifi_user
NIFI_PASSWORD=Topomigrator123!Topomigrator123!
NIFI_ALLOW_INSECURE_LOCAL_TLS=true

MIGRATION_CONFIG_PATH=/app/configs/contract.yaml
DATASOURCES_CONFIG_PATH=/app/configs/datasources.yaml
INCREMENTAL_STATE_PATH=/app/outputs/state/incremental-state.json
```

Use `/app/...` paths for files read by the application inside the container. Those are correct because Docker Compose mounts the host folders into `/app`.

Optional path override:

```env
CHANGELOGS_DIR=/app/changelogs/tables
```

If omitted, the Java code uses `changelogs/tables` relative to its working directory.

For PostgreSQL host names:

- `host.docker.internal` is usually correct for Docker Desktop.
- In Linux or VM environments, you may need the host private IP instead.
- You can set `CONTAINER_DB_HOST` or `PG_HOST_FOR_CONTAINERS` before running the script to change the default generated JDBC URLs.

## Configuration Files

TopoMigrator reads two YAML files from `configs/`.

### `configs/contract.yaml`

This file describes the migration from a functional point of view: what tables are migrated, where they come from, where they go, whether they are enabled, and whether the migration is full or incremental.

Basic full migration:

```yaml
migration:
  name: "ecommerce-migration"
  description: "Full migration of the ecommerce model"
  version: "1.0"
  author: "${USERNAME}"

tables:
  customers:
    source:
      schema: "public"
      table: "customers"
    target:
      schema: "public"
      table: "customers"
    enabled: true
    migrationType: "full"

  orders:
    source:
      schema: "public"
      table: "orders"
    target:
      schema: "public"
      table: "orders"
    enabled: true
    migrationType: "full"
```

Rules enforced by the validator:

- `migration` must exist.
- `migration.name` and `migration.version` are required.
- `migration` must appear before `tables` in the YAML file.
- At least one table must be active with `enabled: true`.
- `source.table` and `target.table` are required for active tables.
- SQL identifiers may contain letters, numbers, and `_`, and must start with a letter or `_`.
- `migrationType` must be `full` or `incremental`.

### Incremental Tables

Incremental migration example:

```yaml
tables:
  orders:
    source:
      schema: "public"
      table: "orders"
    target:
      schema: "public"
      table: "orders"
    enabled: true
    migrationType: "incremental"
    incrementalConfig:
      column: "updated_at"
      type: "timestamp"
      startValue: "2026-01-01T00:00:00"
      batchSize: 1000
      loadStrategy: "upsert"
      idempotencyKeyColumns:
        - "id"
```

How incremental mode works:

- `incrementalConfig.column` is the cursor column.
- `startValue` is used for the first execution or when no previous state exists.
- `batchSize` limits the selected records. If omitted, the code defaults to its configured behavior.
- The generated selection uses the incremental cursor and orders by that cursor.
- A successful table updates `outputs/state/incremental-state.json`.
- Failed or blocked tables do not advance the incremental state.

Supported incremental load strategies:

```text
upsert
append
append_only
```

For `upsert`, TopoMigrator needs idempotency keys. It uses `idempotencyKeyColumns` when configured; otherwise it attempts to infer the target primary key. If it cannot determine keys, the table fails instead of risking silent duplicates.

### Optional Filters

Tables may define a SQL `where` filter:

```yaml
tables:
  customers:
    source:
      schema: "public"
      table: "customers"
    target:
      schema: "public"
      table: "customers"
    enabled: true
    migrationType: "full"
    filters:
      where: "country = 'ES'"
```

The filter is user-provided SQL. Review it carefully before running a migration.

### `configs/datasources.yaml`

This file describes the technical connection settings. It usually references variables from `.env`.

```yaml
source:
  driver: ${SOURCE_DB_DRIVER:org.postgresql.Driver}
  driverLocation: ${SOURCE_DB_DRIVER_LOCATION:/opt/nifi/drivers/postgresql-42.7.10.jar}
  databaseType: ${SOURCE_DB_TYPE:PostgreSQL}
  jdbcUrl: ${SOURCE_DB_JDBC_URL}
  username: ${SOURCE_DB_USERNAME}
  password: ${SOURCE_DB_PASSWORD}

target:
  driver: ${TARGET_DB_DRIVER:org.postgresql.Driver}
  driverLocation: ${TARGET_DB_DRIVER_LOCATION:/opt/nifi/drivers/postgresql-42.7.10.jar}
  databaseType: ${TARGET_DB_TYPE:PostgreSQL}
  jdbcUrl: ${TARGET_DB_JDBC_URL}
  username: ${TARGET_DB_USERNAME}
  password: ${TARGET_DB_PASSWORD}
```

Variable syntax:

```text
${VAR}                 required variable
${VAR:default_value}   optional variable with default
```

If a required variable is missing or empty, the loader stops the execution.

## Liquibase Changelogs

Each active target table must have one changelog in:

```text
changelogs/tables/<schema>.<table>.yaml
```

Example:

```text
changelogs/tables/public.customers.yaml
```

Minimum changelog:

```yaml
databaseChangeLog:
  - changeSet:
      id: 1
      author: topomigrator
      changes:
        - createTable:
            schemaName: public
            tableName: customers
            columns:
              - column:
                  name: id
                  type: int
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: name
                  type: varchar(255)
```

The changelog validator checks that:

- The file exists.
- The YAML has a `databaseChangeLog` root.
- It contains a `createTable` for the expected target table.
- `schemaName` and `tableName` match the contract.

If the target table already exists, the application continues with schema compatibility validation instead of blindly recreating it.

## Runtime Architecture

The main Java entry point is `es.upm.tfg.topomigrator.App`.

Internal execution flow:

1. Creates output directories.
2. Cleans previous temporary traces, errors, and flow outputs.
3. Loads `contract.yaml`.
4. Loads `datasources.yaml` through `ContractLoader`.
5. Resolves datasource environment placeholders.
6. Validates the contract.
7. Keeps only `enabled: true` tables.
8. Tests source and target JDBC connections.
9. Validates source schemas.
10. Validates target changelog files.
11. Applies target schema changes through Liquibase.
12. Validates source-target schema compatibility.
13. Reads foreign-key metadata from the source database.
14. Computes a topological execution order.
15. Executes each table in Apache NiFi.
16. Blocks dependent tables if a parent table fails.
17. Writes one table trace per table and one global summary.
18. Updates incremental state after successful incremental tables.

The data movement itself is delegated to Apache NiFi. Java orchestrates, validates, monitors, and audits.

## Apache NiFi

Docker Compose starts Apache NiFi 2.9.0 and exposes it at:

```text
https://localhost:8443/nifi
```

The NiFi API used by TopoMigrator is:

```text
https://nifi:8443/nifi-api
```

The flow template is:

```text
flows/MainMigration.json
```

Before uploading the flow to NiFi, TopoMigrator replaces tokens such as:

```text
##EXECUTION_ID##
##QUERY_SQL##
##TABLA_ORIGEN##
##TABLA_DESTINO##
##SOURCE_DB_URL##
##SOURCE_DB_USER##
##SOURCE_DB_PASSWORD##
##TARGET_DB_URL##
##TARGET_DB_USER##
##TARGET_DB_PASSWORD##
##STATEMENT_TYPE##
##UPDATE_KEYS##
```

NiFi needs the PostgreSQL JDBC driver mounted at:

```text
/opt/nifi/drivers/postgresql-42.7.10.jar
```

Docker Compose maps that from:

```text
src/main/resources/db/drivers/
```

## Outputs

Runtime outputs are written under `outputs/`.

Important paths:

```text
outputs/logs/                               execution logs
outputs/traces/summary.json                 global execution summary
outputs/traces/tables/<schema>.<table>.json individual table traces
outputs/errors/                             error artifacts
outputs/flows/                              temporary flow artifacts
outputs/state/incremental-state.json        persisted incremental cursor state
```

At startup, the Java application cleans temporary traces, errors, and flows. It keeps logs and incremental state.

Table final statuses:

```text
SUCCESS   the table finished correctly
FAILED    the table failed
BLOCKED   the table was not executed because a dependency failed or was blocked
```

Audit consistency statuses:

```text
MATCH
MISMATCH
SOURCE_ONLY
TARGET_DELTA_ONLY
UNAVAILABLE
```

The global summary keeps the execution order and per-table execution details. Individual table traces intentionally do not expose `executionOrder`.

## Scripts

Current scripts:

```text
run-topomigrator.sh                 main one-shot runner
scripts/01-env.sh                   shared environment defaults
scripts/02-postgres-setup.sh        creates PostgreSQL role/databases when using local PostgreSQL
scripts/03-postgres-docker-access.sh optional PostgreSQL host access setup for Docker
scripts/04-write-env-file.sh        writes .env from the configured environment variables
```

Normal users should run only:

```bash
./run-topomigrator.sh
```

Use the helper scripts directly only when debugging or preparing a specific environment.

## Running Without The Wrapper

The wrapper is recommended. For debugging, you can run Docker Compose directly:

```bash
docker compose up --build --abort-on-container-exit
```

You can also build locally:

```bash
mvn package
java -jar target/topomigrator-1.0-SNAPSHOT.jar
```

When running locally outside Docker, make sure `MIGRATION_CONFIG_PATH`, `DATASOURCES_CONFIG_PATH`, `INCREMENTAL_STATE_PATH`, `CHANGELOGS_DIR`, and all datasource variables point to host paths and reachable JDBC URLs.

## Tests

Run the test suite:

```bash
mvn test
```

Run one test class:

```bash
mvn test -Dtest=ContractLoaderQualityTest
```

Build without tests:

```bash
mvn package -DskipTests
```

The test suite is intentionally focused on behavior that can be checked without external infrastructure. It covers contract loading, contract validation, changelog validation, Liquibase preconditions, schema compatibility, dependency ordering, SQL generation, NiFi flow variable generation, incremental state, execution trace behavior, and table identifier normalization.

The unit tests do not start NiFi, PostgreSQL, or Docker, and they do not depend on the YAML files under `configs/` or `changelogs/`. Tests create their own temporary contracts, datasource files, and changelogs. NiFi-specific coverage validates the values TopoMigrator injects into the flow template; running the actual flow remains an integration concern handled by `run-topomigrator.sh`.

## Common Problems

`SOURCE_DB_JDBC_URL no configurado`

The `.env` file is missing the required datasource variable.

`Faltan configs/contract.yaml o configs/datasources.yaml`

Put both YAML files in `configs/`.

`changelogs/tables no contiene archivos .yaml`

Add one Liquibase changelog per active target table.

`No existe databasechangelog`

Liquibase may not have run, or the target database/schema is not the one you expected.

`no pg_hba.conf entry`

PostgreSQL is rejecting the Docker container connection. On Linux/WSL environments, try:

```bash
CONFIGURE_POSTGRES_DOCKER_ACCESS=true ./run-topomigrator.sh
```

`host.docker.internal` does not resolve

Use the host IP in `SOURCE_DB_JDBC_URL` and `TARGET_DB_JDBC_URL`, or set `CONTAINER_DB_HOST` / `PG_HOST_FOR_CONTAINERS`.

## Limitations

- PostgreSQL is the validated database for this project.
- The tool is batch-oriented, not streaming/CDC.
- It does not perform complex transformations.
- It does not provide a UI or API.
- It does not guarantee distributed transactions across multiple tables.
- Dependency ordering depends on physical foreign keys visible through JDBC metadata.
- Consistency checks compare counts/deltas, not every record.
- User-defined `where` filters are inserted as SQL conditions and must be reviewed.
- The NiFi flow depends on token replacement in `flows/MainMigration.json`.

## Main Code Areas

```text
App                         main orchestration flow
ContractLoader              loads contract and datasources
ContractValidator           validates contract rules
TargetChangelogValidator    validates Liquibase changelog files
LiquibaseSchemaExecutor     applies target schema changes
SchemaCompatibilityValidator validates source/target compatibility
MetadataDependencyExtractor extracts FK dependencies
DependencyResolver          computes table execution order
ExecutionEngine             runs table migrations and writes audit data
NiFiClient                  talks to NiFi REST API
FlowVariableBuilder         builds NiFi token values
TableMetricsService         builds selection SQL and audit metrics
IncrementalStateService     persists incremental cursor state
TraceabilityManager         writes JSON traces
OutputDirectoryInitializer  creates output folders
OutputCleaner               cleans runtime outputs
```
