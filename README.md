# TopoMigrator

TopoMigrator es una herramienta Java/Maven de línea de comandos para orquestar migraciones batch entre bases de datos relacionales. El MVP está validado con PostgreSQL y usa un contrato YAML, validaciones previas, Liquibase, metadatos JDBC, Apache NiFi y trazas JSON para que la ejecución sea reproducible y auditable.

La herramienta no es una plataforma ETL completa. No tiene interfaz gráfica, no expone una API pública, no implementa CDC/tiempo real y no realiza transformaciones complejas de datos. Su objetivo es coordinar migraciones declarativas de tablas, preparar o validar el esquema destino, respetar dependencias relacionales y dejar evidencias de ejecución.

## Requisitos

- Java 17.
- Maven 3.x.
- Docker y Docker Compose, si se quiere levantar Apache NiFi localmente.
- Acceso JDBC a una base origen y una base destino.
- PostgreSQL como SGBD validado para el MVP.
- Driver JDBC de PostgreSQL disponible para NiFi en:

```text
src/main/resources/db/drivers/postgresql-42.7.10.jar
```

## Estructura principal

```text
configs/contract.yaml       Contrato funcional de migración
configs/datasources.yaml    Configuración técnica de conexiones JDBC
changelogs/tables/          Changelogs Liquibase por tabla destino
flows/MainMigration.json    Plantilla de flujo NiFi parametrizada
src/main/java/              Código fuente Java
src/test/java/              Tests unitarios e integración parcial
outputs/                    Salidas generadas en ejecución
```

`outputs/`, `target/`, `.env`, `scripts/` y `test-data/` quedan ignorados por Git en este repositorio.

## Arquitectura general

TopoMigrator separa la responsabilidad de la migración en varios bloques:

| Bloque | Responsabilidad |
|---|---|
| Contrato YAML | Define qué tablas se migran, origen, destino, tipo de migración, filtros e incrementalidad. |
| Datasources YAML | Define la configuración técnica de conexión a origen y destino. |
| Validadores Java | Comprueban contrato, conexiones, existencia de tablas origen, changelogs y compatibilidad origen/destino. |
| Liquibase | Crea las tablas destino cuando no existen y deja el esquema preparado antes de mover datos. |
| JDBC Metadata | Extrae PK, FK y columnas para validar estructura y calcular dependencias. |
| Dependencias | Ordena las tablas con un grafo dirigido y ordenación topológica. |
| Apache NiFi | Ejecuta el movimiento físico de datos mediante `MainMigration.json`. |
| Trazabilidad JSON | Genera resumen global, trazas por tabla y estado incremental. |

## Flujo interno de ejecución

El flujo real parte de `App.main()` y sigue estas fases:

1. Inicializa la estructura `outputs/`.
2. Limpia trazas, errores y flujos temporales de ejecuciones anteriores.
3. Carga `configs/contract.yaml` o la ruta definida en `MIGRATION_CONFIG_PATH`.
4. Carga `configs/datasources.yaml` o la ruta definida en `DATASOURCES_CONFIG_PATH`.
5. Resuelve variables de entorno del tipo `${VAR}` o `${VAR:valor_por_defecto}`.
6. Valida el contrato de migración.
7. Filtra las tablas activas con `enabled: true`.
8. Comprueba conexión JDBC contra origen y destino.
9. Valida que las tablas origen existen.
10. Valida que existe un changelog Liquibase por cada tabla destino activa.
11. Aplica Liquibase para crear tablas destino que todavía no existen.
12. Valida la compatibilidad estructural entre origen y destino.
13. Extrae dependencias FK desde metadatos JDBC.
14. Calcula el orden de ejecución con un grafo dirigido y algoritmo de Kahn.
15. Ejecuta cada tabla en Apache NiFi siguiendo ese orden.
16. Marca como `BLOCKED` las tablas que dependen de una tabla fallida o bloqueada.
17. Genera trazas JSON por tabla y un resumen global.
18. Actualiza el estado incremental solo en tablas incrementales ejecutadas correctamente.

La migración de datos solo comienza si las fases previas terminan correctamente.

## Configuración

### 1. Contrato funcional: `configs/contract.yaml`

El contrato define la información de la migración y las tablas a procesar. La sección `migration` debe aparecer antes de `tables`.

Ejemplo de migración completa:

```yaml
migration:
  name: "caso1-ecommerce"
  description: "Migración full de modelo e-commerce"
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
```

Valores admitidos en `migrationType`:

```text
full
incremental
```

Los identificadores SQL usados en `schema`, `table`, `column` e `idempotencyKeyColumns` deben empezar por letra o guion bajo y usar solo letras, números y guion bajo.

### 2. Migraciones incrementales

Una tabla incremental debe definir `incrementalConfig`:

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

Funcionamiento real:

- `column` indica la columna usada como cursor incremental.
- `startValue` es obligatorio y se usa en la primera ejecución o si no existe estado previo.
- Después de una ejecución correcta, TopoMigrator guarda el último valor procesado en `outputs/state/incremental-state.json`.
- En ejecuciones posteriores, el estado persistido tiene prioridad sobre `startValue`.
- La consulta incremental usa una condición equivalente a `column > valor`, orden ascendente y `LIMIT batchSize`.
- Si `batchSize` no se indica, se usa `1000`.
- El estado incremental solo avanza si la tabla termina en `SUCCESS`.
- Si la tabla falla o queda bloqueada, el cursor no se actualiza.

`type` existe en el modelo de configuración, pero actualmente no se usa para construir la SQL ni para aplicar validaciones específicas por tipo. Se conserva como campo descriptivo.

### 3. Estrategias de carga incremental

`loadStrategy` admite:

| Valor | Comportamiento |
|---|---|
| `upsert` | Usa `UPSERT` en NiFi. Es el valor por defecto si no se indica estrategia. |
| `append` | Usa `INSERT`. No garantiza idempotencia ante reejecuciones. |
| `append_only` | Usa `INSERT`. No garantiza idempotencia ante reejecuciones. |

Para `upsert`, TopoMigrator necesita claves de idempotencia. Se obtienen así:

1. Primero usa `incrementalConfig.idempotencyKeyColumns`, si está informado.
2. Si no está informado, intenta inferir la clave primaria de la tabla destino mediante JDBC.
3. Si no hay claves configuradas ni PK detectable, la tabla falla para evitar duplicados silenciosos.

### 4. Filtros SQL opcionales

Cada tabla puede definir un filtro `where`:

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
      where: "country = 'España'"
```

En migraciones completas, el filtro se añade como `WHERE`. En migraciones incrementales, se combina con el cursor incremental mediante `AND`.

El filtro se inserta como condición SQL definida por el usuario. Debe revisarse antes de ejecutar la migración, porque no se construye mediante un DSL ni se parametriza automáticamente.

### 5. Configuración técnica: `configs/datasources.yaml`

Las conexiones se separan del contrato funcional. El fichero de datasources contiene origen y destino:

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

Formato de variables soportado:

```text
${VAR}                 Variable obligatoria
${VAR:valor_defecto}   Variable con valor por defecto
```

Si una variable obligatoria no existe o está vacía, la carga del contrato falla.

### 6. Variables de entorno recomendadas

Crea un `.env` local. No subas este fichero al repositorio.

```text
SOURCE_DB_DRIVER=org.postgresql.Driver
SOURCE_DB_DRIVER_LOCATION=/opt/nifi/drivers/postgresql-42.7.10.jar
SOURCE_DB_TYPE=PostgreSQL
SOURCE_DB_JDBC_URL=jdbc:postgresql://host-origen:5432/source_db
SOURCE_DB_USERNAME=source_user
SOURCE_DB_PASSWORD=source_password

TARGET_DB_DRIVER=org.postgresql.Driver
TARGET_DB_DRIVER_LOCATION=/opt/nifi/drivers/postgresql-42.7.10.jar
TARGET_DB_TYPE=PostgreSQL
TARGET_DB_JDBC_URL=jdbc:postgresql://host-destino:5432/target_db
TARGET_DB_USERNAME=target_user
TARGET_DB_PASSWORD=target_password

NIFI_BASE_URL=https://nifi:8443/nifi-api
NIFI_USERNAME=nifi_user
NIFI_PASSWORD=nifi_password
NIFI_ALLOW_INSECURE_LOCAL_TLS=true

MIGRATION_CONFIG_PATH=configs/contract.yaml
DATASOURCES_CONFIG_PATH=configs/datasources.yaml
CHANGELOGS_DIR=changelogs/tables
INCREMENTAL_STATE_PATH=outputs/state/incremental-state.json
```

`NIFI_ALLOW_INSECURE_LOCAL_TLS=true` está pensado para el NiFi local de Docker Compose con certificado autofirmado. En entornos con certificado válido, usa `false` u omite la variable.

## Changelogs Liquibase

Cada tabla destino activa debe tener un changelog con esta convención:

```text
changelogs/tables/<schema>.<table>.yaml
```

Ejemplo:

```text
changelogs/tables/public.customers.yaml
```

El validador exige que el fichero:

- exista en `CHANGELOGS_DIR` o en `changelogs/tables` por defecto;
- sea un YAML válido;
- contenga la raíz `databaseChangeLog`;
- contenga un `createTable` para la tabla destino esperada;
- use el mismo `schemaName` y `tableName` definidos en `contract.yaml`.

Ejemplo mínimo:

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
```

Si la tabla destino ya existe, TopoMigrator no ejecuta el `createTable` de Liquibase para esa tabla. En ese caso continúa con la validación estructural entre origen y destino.

## Validaciones previas

Antes de ejecutar NiFi, la herramienta comprueba:

- que el contrato YAML tiene `migration` y `tables`;
- que hay al menos una tabla activa;
- que `migrationType` es `full` o `incremental`;
- que las migraciones incrementales tienen `column` y `startValue`;
- que `loadStrategy` es válido;
- que las conexiones JDBC de origen y destino funcionan;
- que las tablas origen existen;
- que hay changelogs Liquibase para las tablas destino activas;
- que las tablas destino existen después de Liquibase;
- que las columnas de origen existen en destino;
- que los tipos JDBC son compatibles por familia;
- que la longitud destino no es menor cuando la longitud es comparable;
- que la nullability destino no es más restrictiva que la de origen;
- que las PK y FK detectadas en origen se conservan en destino cuando aplica.

## Dependencias entre tablas

TopoMigrator extrae claves foráneas desde la base de datos origen usando `DatabaseMetaData.getImportedKeys`. Con esas relaciones construye un grafo dirigido:

```text
tabla_padre -> tabla_dependiente
```

Después calcula el orden de ejecución mediante ordenación topológica. Si se detecta un ciclo, la ejecución se detiene porque no hay un orden seguro de migración.

Durante la ejecución, si una tabla falla, las tablas dependientes que todavía no se han ejecutado se marcan como `BLOCKED`. Esto evita migrar tablas hijas cuando sus padres no se han migrado correctamente.

## Integración con Apache NiFi

El movimiento físico de datos se delega en Apache NiFi. Java no inserta directamente los registros en destino; Java orquesta y monitoriza el flujo.

La plantilla base está en:

```text
flows/MainMigration.json
```

Antes de subir el flujo a NiFi, TopoMigrator sustituye tokens del tipo `##...##`, entre ellos:

```text
##EXECUTION_ID##
##TABLA_ORIGEN##
##ESQUEMA_ORIGEN##
##TABLA_DESTINO##
##ESQUEMA_DESTINO##
##SOURCE_DB_URL##
##SOURCE_DB_USER##
##SOURCE_DB_PASSWORD##
##SOURCE_DB_DRIVER##
##SOURCE_DB_DRIVER_LOCATION##
##TARGET_DB_URL##
##TARGET_DB_USER##
##TARGET_DB_PASSWORD##
##TARGET_DB_DRIVER##
##TARGET_DB_DRIVER_LOCATION##
##TARGET_DB_TYPE##
##STATEMENT_TYPE##
##UPDATE_KEYS##
##QUERY_SQL##
```

El cliente `NiFiClient` realiza estas operaciones:

1. Autenticación contra `/access/token`.
2. Obtención del root process group.
3. Subida del flujo parametrizado.
4. Activación de controller services.
5. Arranque del process group.
6. Monitorización de hilos activos y colas.
7. Revisión de procesadores de fallo.
8. Parada y limpieza del process group temporal.

El flujo debe conservar procesadores de fallo con prefijo:

```text
NiFiFailure_
```

El monitor usa esos procesadores para detectar errores internos del flujo. Si no existen, la ejecución se considera insegura y puede fallar.

## Ejecución

### Opción A: Docker Compose

Levanta NiFi y ejecuta la aplicación:

```bash
docker compose up --build
```

Docker Compose pasa al contenedor las variables `SOURCE_DB_*`, `TARGET_DB_*`, `NIFI_*`, `MIGRATION_CONFIG_PATH`, `DATASOURCES_CONFIG_PATH` e `INCREMENTAL_STATE_PATH` definidas en `.env`.

Si falta una URL, usuario o contraseña obligatoria, Compose aborta antes de arrancar la aplicación.

NiFi queda disponible en:

```text
https://localhost:8443/nifi
```

### Opción B: Maven local

Compila el proyecto:

```bash
mvn package
```

Ejecuta el JAR generado:

```bash
java -jar target/topomigrator-1.0-SNAPSHOT.jar
```

También puedes ejecutar directamente con Maven:

```bash
mvn test
mvn package -DskipTests
```

## Outputs y ciclo de vida de salidas

La aplicación crea automáticamente esta estructura si no existe:

```text
outputs/
outputs/logs/
outputs/errors/
outputs/traces/
outputs/traces/tables/
outputs/flows/
outputs/state/
```

Al inicio de cada ejecución se limpian ficheros de:

```text
outputs/traces/
outputs/errors/
outputs/flows/
```

No se limpian:

```text
outputs/logs/
outputs/state/
outputs/traces/.last_execution_id
```

Esto significa que:

- las trazas anteriores se sustituyen en cada ejecución;
- los errores antiguos se limpian;
- el estado incremental se conserva;
- los logs se conservan;
- el contador interno de ejecuciones se conserva.

Salidas principales:

```text
outputs/traces/summary.json                 Resumen global de la ejecución
outputs/traces/tables/<schema>.<table>.json Traza individual por tabla destino
outputs/state/incremental-state.json        Estado persistido de migraciones incrementales
outputs/logs/                               Logs de la aplicación/NiFi cuando aplica
```

`outputs/flows/` se crea y se limpia como directorio de trabajo/reserva para artefactos de flujo, aunque la ejecución actual no depende de consultar ficheros generados ahí.

## Interpretar resultados

TopoMigrator usa tres estados finales de tabla:

| Estado | Significado |
|---|---|
| `SUCCESS` | Tabla ejecutada correctamente en NiFi y auditada. |
| `FAILED` | Tabla fallida por error Java, error NiFi, timeout, configuración insegura o resultado no válido. |
| `BLOCKED` | Tabla no ejecutada porque depende de una tabla fallida o bloqueada. |

Además, las métricas de auditoría pueden usar estos estados informativos:

| Estado de auditoría | Significado |
|---|---|
| `MATCH` | Las filas seleccionadas en origen coinciden con el delta neto observado en destino. |
| `MISMATCH` | La selección origen y el delta destino no coinciden. Se genera warning. |
| `SOURCE_ONLY` | Solo se pudo calcular la métrica de origen. |
| `TARGET_DELTA_ONLY` | Solo se pudo calcular el delta de destino. |
| `UNAVAILABLE` | No se pudo calcular ninguna métrica de consistencia. |

La métrica principal de registros procesados se basa en la consulta de origen equivalente a la enviada a NiFi. El delta de destino se usa como validación auxiliar, porque puede verse afectado por reejecuciones o escrituras concurrentes.

## Pruebas

Ejecutar toda la suite:

```bash
mvn test
```

Ejecutar una clase concreta:

```bash
mvn test -Dtest=ContractLoaderTest
```

Ejecutar varias clases concretas:

```bash
mvn test -Dtest=DependencyResolverTest,DependencyGraphTest,ForeignKeyDependencyTest,TableNodeTest
```

Compilar sin ejecutar tests:

```bash
mvn package -DskipTests
```

La suite incluye pruebas sobre:

- carga y validación del contrato;
- resolución de variables de entorno;
- validación de changelogs Liquibase;
- compatibilidad de esquemas;
- grafo y resolución de dependencias;
- generación de trazas;
- generación de IDs de ejecución;
- limpieza e inicialización de `outputs/`;
- clasificación de estados `SUCCESS`, `FAILED` y `BLOCKED`;
- propagación de bloqueos por dependencias;
- gestión del estado incremental;
- coherencia del resumen global.

## Limpieza local

Para borrar artefactos de build:

```bash
mvn clean
```

Para reiniciar trazas y errores, basta con lanzar de nuevo la aplicación: `OutputCleaner` limpia las salidas temporales al inicio.

Para reiniciar completamente el estado incremental, elimina manualmente:

```text
outputs/state/incremental-state.json
```

No borres ese fichero si quieres que las migraciones incrementales continúen desde el último cursor procesado.

## Limitaciones del MVP

- PostgreSQL es el SGBD validado en el proyecto.
- No hay soporte real probado para migraciones multi-SGBD en producción.
- No implementa CDC ni streaming en tiempo real.
- No implementa transformaciones complejas, enriquecimiento o limpieza semántica de datos.
- No ofrece interfaz gráfica ni diseñador visual de migraciones.
- No expone API REST propia.
- No garantiza transaccionalidad distribuida entre varias tablas.
- La detección de dependencias depende de las FK físicas visibles por JDBC.
- La validación de consistencia no compara registro a registro.
- Los filtros `where` son SQL escrito por el usuario y deben revisarse cuidadosamente.
- La parametrización del flujo NiFi se basa en sustitución de tokens `##...##` sobre el JSON de la plantilla.

## Relación con el TFG

Este repositorio puede explicarse como un orquestador de migraciones batch basado en configuración declarativa. Algunas clases relevantes para este proyecto y que se explicane en la memoria son:

- `App`: flujo principal de orquestación.
- `ContractLoader`: carga de contrato y datasources.
- `ContractValidator`: validación funcional del YAML.
- `TargetChangelogValidator`: validación de changelogs Liquibase.
- `LiquibaseSchemaExecutor`: preparación del esquema destino.
- `SchemaCompatibilityValidator`: validación estructural origen/destino.
- `MetadataDependencyExtractor`: extracción de dependencias FK.
- `DependencyResolver`: ordenación topológica.
- `ExecutionEngine`: ejecución, auditoría y propagación de fallos.
- `NiFiClient`: integración REST con Apache NiFi.
- `FlowVariableBuilder`: generación de variables para la plantilla NiFi.
- `TableMetricsService`: SQL de selección y métricas de auditoría.
- `IncrementalStateService`: persistencia del cursor incremental.
- `TraceabilityManager`: escritura de trazas JSON.
- `OutputDirectoryInitializer` y `OutputCleaner`: ciclo de vida de salidas runtime.
