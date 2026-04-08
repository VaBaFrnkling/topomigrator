# =============================================================================
# Dockerfile para topomigrator
# Utiliza un patrón "multi-stage build" con dos etapas:
#   1. Compilación: compila el proyecto con Maven y JDK 17.
#   2. Ejecución: empaqueta únicamente el JAR resultante en una imagen
#      ligera con solo el JRE, reduciendo el tamaño final de la imagen.
# =============================================================================

# -------------------------Etapa 1: Compilación-------------------------

# Imagen base con Maven 3.9.9 y JDK 17 (Eclipse Temurin).
# Se etiqueta como "build" para referenciarla desde la segunda etapa.
FROM maven:3.9.9-eclipse-temurin-17 AS build

# Establece /app como directorio de trabajo dentro del contenedor.
WORKDIR /app

# Se copia primero SOLO el pom.xml para aprovechar la caché de capas de Docker.
# Si el pom.xml no cambia, Docker reutiliza la capa de dependencias descargadas,
# evitando descargarlas de nuevo en cada build (optimización de tiempos de compilación).
COPY pom.xml .

# Descarga todas las dependencias declaradas en el pom.xml de forma offline.
# Esto permite que la capa quede cacheada y no se repita en builds sucesivos
# mientras el pom.xml no cambie.
RUN mvn dependency:go-offline

# Ahora se copia el código fuente. Al hacerlo en un paso separado, cualquier
# cambio en el código NO invalida la caché de dependencias de la capa anterior.
COPY src ./src

# Compila el proyecto y genera el JAR empaquetado.
# -DskipTests omite la ejecución de tests para acelerar el proceso de build.
# El artefacto resultante se genera en /app/target/.
RUN mvn clean package -DskipTests

# -------------------------Etapa 2: Ejecución-------------------------

# Imagen base ligera que contiene solo el JRE 17 (sin JDK ni Maven).
# Esto reduce significativamente el tamaño de la imagen final, ya que no se
# incluyen las herramientas de compilación (solo lo necesario para ejecutar).
FROM eclipse-temurin:17-jre

# Establece /app como directorio de trabajo en la imagen de ejecución.
WORKDIR /app

# Copia el JAR generado en la etapa "build" a la imagen final.
# --from=build indica que el archivo proviene de la primera etapa.
# Se renombra a "app.jar" para simplificar el comando de arranque.
COPY --from=build /app/target/topomigrator-1.0-SNAPSHOT.jar app.jar

# -------------------------Directorios de datos-------------------------
# Se crean los directorios que la aplicación necesita en tiempo de ejecución
# para leer configuración de entrada y escribir resultados de salida.
# Estos directorios reflejan la estructura del proyecto en el host:
#
#   configs/                → Ficheros de configuración de entrada (ej: Contrato.yaml)
#   flows/full/             → Flows de NiFi generados para migración completa
#   flows/incremental/      → Flows de NiFi generados para migración incremental
#   changelogs/tables/      → Changelogs de Liquibase generados por tabla
#   outputs/errors/         → Registro de errores ocurridos durante la migración
#   outputs/logs/           → Logs generales de ejecución de la aplicación
#   outputs/traces/         → Trazas de auditoría y trazabilidad de la migración

RUN mkdir -p configs \
            flows/full \
            flows/incremental \
            changelogs/tables \
            outputs/errors \
            outputs/logs \
            outputs/traces

# Se declaran como volúmenes de Docker para que:
#   1. Los datos persistan aunque se destruya el contenedor.
#   2. Se puedan montar desde el host con "docker run -v" para:
#      - Inyectar configuración de entrada (configs/).
#      - Extraer los artefactos generados (flows/, changelogs/, outputs/).
#
# Ejemplo de uso con bind mounts:
#   docker run \
#     -v ./configs:/app/configs \
#     -v ./flows:/app/flows \
#     -v ./changelogs:/app/changelogs \
#     -v ./outputs:/app/outputs \
#     topomigrator
VOLUME ["/app/configs", "/app/flows", "/app/changelogs", "/app/outputs"]

# Define el comando de arranque del contenedor.
# ENTRYPOINT (en lugar de CMD) asegura que el contenedor siempre ejecute
# la aplicación Java, sin posibilidad de ser sobreescrito accidentalmente.
ENTRYPOINT ["java", "-jar", "app.jar"]