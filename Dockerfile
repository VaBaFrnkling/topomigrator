# =============================================================================
# Dockerfile para topomigrator
# Utiliza un patrón "multi-stage build" con dos etapas:
#   1. Compilación: compila el proyecto con Maven y JDK 17.
#   2. Ejecución: empaqueta únicamente el JAR resultante en una imagen
#      ligera con solo el JRE, reduciendo el tamaño final de la imagen.
#
# =============================================================================

# -------------------------Etapa 1: Compilación-------------------------

# Imagen base con Maven 3.9.9 y JDK 17 (Eclipse Temurin).
# Se etiqueta como "build" para poder reutilizar el artefacto generado
# en la segunda etapa.
FROM maven:3.9.9-eclipse-temurin-17 AS build

# Establece /app como directorio de trabajo dentro del contenedor.
WORKDIR /app

# Se copia primero SOLO el pom.xml para aprovechar la caché de capas.
# Si el pom.xml no cambia, Docker puede reutilizar la capa en la que
# se descargan las dependencias, acelerando builds posteriores.
COPY pom.xml .

# Descarga las dependencias declaradas en el pom.xml en modo batch.
# La opción -B evita salida interactiva y hace el build más adecuado
# para automatización y entornos CI/CD.
RUN mvn -B dependency:go-offline

# Copia el código fuente del proyecto.
# Al hacerlo después del pom.xml, cualquier cambio en src/ no invalida
# la caché de dependencias descargadas en la capa anterior.
COPY src ./src

# Compila el proyecto y genera el JAR empaquetado.
# -DskipTests omite la ejecución de tests para acelerar la construcción.
RUN mvn -B clean package -DskipTests

# -------------------------Etapa 2: Ejecución-------------------------

# Imagen base ligera con solo el JRE 17.
# No incluye Maven ni el JDK, por lo que reduce el tamaño de la imagen final.
FROM eclipse-temurin:17-jre

# Establece /app como directorio de trabajo en la imagen final.
WORKDIR /app

# Copia el JAR generado en la etapa "build" a la imagen de ejecución.
# Se renombra como "app.jar" para simplificar el comando de arranque.
# Se usa *.jar para evitar depender de un nombre exacto con versión fija.
COPY --from=build /app/target/*.jar app.jar

# -------------------------Recursos del proyecto-------------------------

# Copia a la imagen los directorios de configuración y definición
# necesarios para la ejecución de la aplicación:
#
#   configs/      → Configuraciones de entrada
#   flows/        → Definiciones de flujos de NiFi
#   changelogs/   → Changelogs de Liquibase por tabla/esquema
#
# De esta forma, la imagen contiene no solo el binario, sino también
# los recursos base necesarios para operar sin depender obligatoriamente
# de volúmenes externos.
COPY configs ./configs
COPY flows ./flows
COPY changelogs ./changelogs

# -------------------------Directorios de salida-------------------------

# Crea los directorios donde la aplicación escribirá sus salidas
# en tiempo de ejecución:
#
#   outputs/errors/   → Registro de errores
#   outputs/logs/     → Logs generales de ejecución
#   outputs/traces/   → Trazas y auditoría por tabla/ejecución
#   outputs/state/    → Cursor incremental persistido entre ejecuciones
#
# Estos directorios pueden seguir montándose desde fuera si se desea
# persistir la información en el host.
RUN mkdir -p outputs/errors \
             outputs/logs \
             outputs/traces \
             outputs/state

# -------------------------Arranque del contenedor-------------------------

# Define el comando de arranque del contenedor.
# Cuando la imagen se ejecute, se lanzará automáticamente la aplicación Java.
ENTRYPOINT ["java", "-jar", "app.jar"]