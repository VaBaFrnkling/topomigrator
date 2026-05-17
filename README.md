# TopoMigrator

TopoMigrator es una herramienta Java/Maven para orquestar migraciones de datos PostgreSQL en un entorno local. Usa un contrato YAML, validaciones previas, Liquibase, dependencias JDBC, Apache NiFi y trazas JSON para dejar una ejecucion defendible.

## Requisitos

- Java 17.
- Maven 3.x.
- Docker y Docker Compose, si se quiere ejecutar con NiFi local.
- Acceso a una base PostgreSQL origen y una base PostgreSQL destino.
- Driver JDBC de PostgreSQL disponible para NiFi en `src/main/resources/db/drivers/postgresql-42.7.10.jar`.

## Estructura principal

```text
configs/contract.yaml       Contrato de migracion
configs/datasources.yaml    Conexiones origen/destino
changelogs/tables/          Changelogs Liquibase por tabla destino
flows/MainMigration.json    Flujo NiFi parametrizado
src/                        Codigo Java
outputs/                    Salidas generadas en ejecucion
```

`outputs/`, `target/` y `.env` no forman parte del proyecto entregable y quedan ignorados por Git.

## Configuracion

1. Configura `configs/contract.yaml`.

Define las tablas activas, origen, destino y tipo de migracion:

```yaml
tables:
  ejemplo:
    source:
      schema: "public"
      table: "tabla_origen"
    target:
      schema: "public"
      table: "tabla_destino"
    enabled: true
    migrationType: "full"
```

Para migraciones incrementales, define `incrementalConfig`:

```yaml
incrementalConfig:
  column: "updated_at"
  type: "timestamp"
  startValue: "2026-01-01T00:00:00"
  batchSize: 1000
  loadStrategy: "upsert"
  idempotencyKeyColumns:
    - "id"
```

2. Configura `configs/datasources.yaml`.

El fichero ya esta preparado para resolver valores desde variables de entorno:

```yaml
source:
  jdbcUrl: ${SOURCE_DB_JDBC_URL}
  username: ${SOURCE_DB_USERNAME}
  password: ${SOURCE_DB_PASSWORD}

target:
  jdbcUrl: ${TARGET_DB_JDBC_URL}
  username: ${TARGET_DB_USERNAME}
  password: ${TARGET_DB_PASSWORD}
```

3. Crea un `.env` local.

No subas este fichero al repositorio. Usa valores reales de tu entorno:

```text
SOURCE_DB_DRIVER=org.postgresql.Driver
SOURCE_DB_JDBC_URL=jdbc:postgresql://host-origen:5432/source_db
SOURCE_DB_USERNAME=source_user
SOURCE_DB_PASSWORD=source_password

TARGET_DB_DRIVER=org.postgresql.Driver
TARGET_DB_JDBC_URL=jdbc:postgresql://host-destino:5432/target_db
TARGET_DB_USERNAME=target_user
TARGET_DB_PASSWORD=target_password

NIFI_BASE_URL=https://nifi:8443/nifi-api
NIFI_USERNAME=nifi_user
NIFI_PASSWORD=nifi_password
NIFI_ALLOW_INSECURE_LOCAL_TLS=true
```

`NIFI_ALLOW_INSECURE_LOCAL_TLS=true` esta pensado para el NiFi local de Docker Compose con certificado autofirmado. En entornos con certificado valido, usa `false` u omite la variable.

4. Prepara changelogs Liquibase.

Cada tabla destino activa debe tener su changelog en:

```text
changelogs/tables/<schema>.<table>.yaml
```

Ejemplo:

```text
changelogs/tables/public.customers.yaml
```

5. Verifica el driver JDBC para NiFi.

El proyecto incluye el driver PostgreSQL esperado por el flujo NiFi en:

```text
src/main/resources/db/drivers/postgresql-42.7.10.jar
```

Docker Compose monta esa carpeta en el contenedor NiFi como `/opt/nifi/drivers`, y las variables `SOURCE_DB_DRIVER_LOCATION` y `TARGET_DB_DRIVER_LOCATION` apuntan por defecto a `/opt/nifi/drivers/postgresql-42.7.10.jar`.

## Ejecutar la herramienta

### Opcion A: Docker Compose

Levanta NiFi y ejecuta la aplicacion:

```bash
docker compose up --build
```

Docker Compose pasa al contenedor las variables `SOURCE_DB_*`, `TARGET_DB_*` y `NIFI_*` definidas en `.env`. Si falta alguna URL, usuario o password de base de datos, Compose aborta antes de arrancar la aplicacion.

NiFi queda disponible en:

```text
https://localhost:8443/nifi
```

Las salidas se generan en:

```text
outputs/traces/
outputs/errors/
outputs/flows/
outputs/state/incremental-state.json
```

### Opcion B: Maven local

Compila el proyecto:

```bash
mvn package
```

Ejecuta el JAR generado:

```bash
java -jar target/topomigrator-1.0-SNAPSHOT.jar
```

Si no usas las rutas por defecto, configura estas variables:

```text
MIGRATION_CONFIG_PATH=configs/contract.yaml
DATASOURCES_CONFIG_PATH=configs/datasources.yaml
INCREMENTAL_STATE_PATH=outputs/state/incremental-state.json
NIFI_BASE_URL=https://localhost:8443/nifi-api
```

## Interpretar resultados

TopoMigrator usa tres estados principales:

| Estado | Significado |
|---|---|
| `SUCCESS` | Tabla migrada correctamente. |
| `FAILED` | Tabla fallida por error Java, NiFi, timeout, resultado ambiguo o configuracion insegura. |
| `BLOCKED` | Tabla no ejecutada porque depende de una tabla fallida. |

El resumen global debe ser coherente con las trazas individuales por tabla.

## Pruebas

### Ejecutar toda la suite

```bash
mvn test
```

### Ejecutar una clase concreta

```bash
mvn test -Dtest=ContractLoaderTest
```

### Ejecutar varias clases concretas

```bash
mvn test -Dtest=DependencyResolverTest,DependencyGraphTest,ForeignKeyDependencyTest,TableNodeTest
```

### Compilar sin ejecutar tests

```bash
mvn package -DskipTests
```

## Limpieza local

Para borrar artefactos de build:

```bash
mvn clean
```

Para limpiar salidas de ejecucion, elimina el contenido generado bajo `outputs/` con cuidado. El estado incremental vive en:

```text
outputs/state/incremental-state.json
```

No borres ese fichero si quieres conservar el cursor incremental entre ejecuciones.
