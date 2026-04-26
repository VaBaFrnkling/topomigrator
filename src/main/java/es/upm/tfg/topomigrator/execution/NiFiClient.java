package es.upm.tfg.topomigrator.execution;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;

/**
 * Cliente REST para comunicarse con la API de Apache NiFi.
 *
 * Seguridad:
 * - Las credenciales deben venir siempre por variables de entorno.
 * - El modo TLS inseguro solo puede activarse explícitamente y únicamente para hosts locales.
 * - En entornos no locales se usa la validación TLS estándar del JDK.
 */
public class NiFiClient {

    private static final Logger logger = LoggerFactory.getLogger(NiFiClient.class);

    private static final String ENV_BASE_URL = "NIFI_BASE_URL";
    private static final String ENV_USERNAME = "NIFI_USERNAME";
    private static final String ENV_PASSWORD = "NIFI_PASSWORD";
    private static final String ENV_ALLOW_INSECURE_LOCAL_TLS = "NIFI_ALLOW_INSECURE_LOCAL_TLS";

    private final String baseUrl;
    private final URI baseUri;
    private final String username;
    private final String password;
    private final boolean allowInsecureLocalTls;
    private HttpClient httpClient;
    private String jwtToken;

    public NiFiClient() {
        String envUrl = System.getenv(ENV_BASE_URL);
        this.baseUrl = (envUrl == null || envUrl.trim().isEmpty())
                ? "https://localhost:8443/nifi-api"
                : envUrl.trim();
        this.baseUri = URI.create(this.baseUrl);

        this.username = requireEnvironmentVariable(ENV_USERNAME);
        this.password = requireEnvironmentVariable(ENV_PASSWORD);
        this.allowInsecureLocalTls = Boolean.parseBoolean(
                System.getenv().getOrDefault(ENV_ALLOW_INSECURE_LOCAL_TLS, "false")
        );

        initializeClient();
    }

    /**
     * Configura el cliente HTTP.
     *
     * Por defecto utiliza la validación TLS estándar del JDK.
     * Solo usa trust-all para certificados autofirmados cuando se activa explícitamente
     * y el host de NiFi es inequívocamente local.
     */
    private void initializeClient() {
        try {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10));

            if (shouldUseInsecureLocalTls()) {
                SSLContext sslContext = buildInsecureSslContext();
                SSLParameters sslParameters = new SSLParameters();
                sslParameters.setEndpointIdentificationAlgorithm(null);

                builder.sslContext(sslContext)
                        .sslParameters(sslParameters);

                logger.warn("Se ha activado NIFI_ALLOW_INSECURE_LOCAL_TLS para un host local ({}). " +
                        "Este modo solo es válido para desarrollo local.", baseUri.getHost());
            } else {
                validateInsecureTlsConfiguration();
                logger.info("Usando validación TLS estándar para NiFi en {}.", baseUrl);
            }

            this.httpClient = builder.build();
        } catch (Exception e) {
            logger.error("Error inicializando el cliente HTTP de NiFi: {}", e.getMessage(), e);
            throw new RuntimeException("No se pudo inicializar el cliente HTTP de NiFi", e);
        }
    }

    public void authenticate() throws Exception {
        logger.debug("Intentando autenticar en NiFi: {} mediante /access/token", baseUrl);

        String formBody = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/access/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 201 || response.statusCode() == 200) {
            this.jwtToken = response.body();
            logger.info("Autenticación correcta en NiFi API. Token JWT obtenido con éxito.");
        } else {
            logger.error("Falló la autenticación contra NiFi. Status: {}, Body: {}", response.statusCode(), response.body());
            throw new RuntimeException("No se pudo iniciar sesión en NiFi. Status: " + response.statusCode());
        }
    }

    public String getRootProcessGroupId() throws Exception {
        if (jwtToken == null) {
            throw new IllegalStateException("Cliente no autenticado. Llamar a authenticate() primero.");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/process-groups/root"))
                .header("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            return root.getAsJsonObject("component").get("id").getAsString();
        } else {
            logger.error("No se pudo obtener el root process group. Status: {}", response.statusCode());
            throw new RuntimeException("Error obteniendo PID de root: " + response.statusCode());
        }
    }

    public String uploadFlowDefinition(String parentId, String groupName, int positionY, Path flowJsonPath, java.util.Map<String, String> dynamicVariables) throws Exception {
        if (jwtToken == null) {
            throw new IllegalStateException("Cliente no autenticado.");
        }

        String boundary = "----NiFiFormBoundary" + System.currentTimeMillis();
        String crlf = "\r\n";

        byte[] fileBytes = Files.readAllBytes(flowJsonPath);
        String fileContent = new String(fileBytes, StandardCharsets.UTF_8);

        if (dynamicVariables != null) {
            for (java.util.Map.Entry<String, String> entry : dynamicVariables.entrySet()) {
                fileContent = fileContent.replace(entry.getKey(), escapeForJsonStringValue(entry.getValue()));
            }
        }

        StringBuilder sb = new StringBuilder();

        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"groupName\"").append(crlf).append(crlf);
        sb.append(groupName).append(crlf);

        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"positionX\"").append(crlf).append(crlf);
        sb.append("0").append(crlf);

        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"positionY\"").append(crlf).append(crlf);
        sb.append(positionY).append(crlf);

        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"flowDefinition\"; filename=\"flow.json\"").append(crlf);
        sb.append("Content-Type: application/json").append(crlf).append(crlf);
        sb.append(fileContent).append(crlf);

        sb.append("--").append(boundary).append("--").append(crlf);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/process-groups/" + parentId + "/process-groups/upload"))
                .header("Authorization", "Bearer " + jwtToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 201 || response.statusCode() == 200) {
            logger.info("Flujo cargado exitosamente para el Process Group: {}", groupName);
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            return root.getAsJsonObject("processGroup").get("id").getAsString();
        } else {
            logger.error("Error al cargar el flujo {}: Status {} - {}", groupName, response.statusCode(), response.body());
            throw new RuntimeException("No se pudo cargar el JSON del flujo de NiFi. Status: " + response.statusCode());
        }
    }

    public void changeProcessGroupState(String processGroupId, String state) throws Exception {
        if (jwtToken == null) {
            throw new IllegalStateException("Cliente no autenticado.");
        }

        String jsonBody = "{\"id\":\"" + processGroupId + "\",\"state\":\"" + state + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/flow/process-groups/" + processGroupId))
                .header("Authorization", "Bearer " + jwtToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            logger.error("No se pudo cambiar el estado a {} del grupo {}. HTTP {}", state, processGroupId, response.statusCode());
            throw new RuntimeException("Error al arrancar/detener NiFi Group: " + response.statusCode());
        }
    }

    public JsonObject getProcessGroupStatus(String processGroupId) throws Exception {
        if (jwtToken == null) {
            throw new IllegalStateException("Cliente no autenticado.");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/flow/process-groups/" + processGroupId + "/status"))
                .header("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } else {
            logger.error("Error al obtener status de NiFi para el grupo {}. HTTP {}", processGroupId, response.statusCode());
            throw new RuntimeException("Error consultando métricas: " + response.statusCode());
        }
    }

    private void validateInsecureTlsConfiguration() {
        if (allowInsecureLocalTls && !isLocalHost(baseUri.getHost())) {
            throw new IllegalStateException(
                    "NIFI_ALLOW_INSECURE_LOCAL_TLS solo puede usarse con hosts locales. Host recibido: " + baseUri.getHost()
            );
        }
    }

    private boolean shouldUseInsecureLocalTls() {
        return allowInsecureLocalTls
                && "https".equalsIgnoreCase(baseUri.getScheme())
                && isLocalHost(baseUri.getHost());
    }

    private boolean isLocalHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "nifi".equals(normalized)
                || "host.docker.internal".equals(normalized)
                || normalized.endsWith(".local");
    }

    private SSLContext buildInsecureSslContext() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }

                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                    }

                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                    }
                }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new SecureRandom());
        return sslContext;
    }

    private String requireEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("La variable de entorno " + name + " es obligatoria.");
        }
        return value.trim();
    }

    private String escapeForJsonStringValue(String value) {
        String safeValue = value != null ? value : "";
        String jsonLiteral = new Gson().toJson(safeValue);
        return jsonLiteral.substring(1, jsonLiteral.length() - 1);
    }
}
