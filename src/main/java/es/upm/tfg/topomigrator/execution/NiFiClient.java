package es.upm.tfg.topomigrator.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Cliente REST para comunicarse con la API de Apache NiFi.
 * Soporta autenticación mediante JWT y conexiones HTTPS (incluso autofirmadas).
 */
public class NiFiClient {

    private static final Logger logger = LoggerFactory.getLogger(NiFiClient.class);
    
    private final String baseUrl;
    private final String username;
    private final String password;
    private HttpClient httpClient;
    private String jwtToken;

    public NiFiClient() {
        // En docker-compose.yaml está como NIFI_BASE_URL: https://nifi:8443/nifi-api
        String envUrl = System.getenv("NIFI_BASE_URL");
        this.baseUrl = envUrl != null ? envUrl : "https://localhost:8443/nifi-api";
        
        // Credenciales por defecto (Single User Credentials configuradas en compose)
        String envUser = System.getenv("NIFI_USERNAME");
        this.username = envUser != null ? envUser : "admin";
        
        String envPass = System.getenv("NIFI_PASSWORD");
        this.password = envPass != null ? envPass : "Admin123456!!";

        initializeClient();
    }

    /**
     * Configura el cliente HTTP para confiar en certificados autofirmados
     * (necesario ya que NiFi 2.0 arranca con TLS autofirmado por defecto).
     */
    private void initializeClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                }
            };
            
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            
            // System property for allowing insecure hostnames with Java 11+ (usa jdk.internal pero es universal)
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");

            this.httpClient = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        } catch (Exception e) {
            logger.error("Error inicializando SSLContext para httpClient: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Autentica con NiFi devolviendo el Bearer Token.
     */
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
            logger.error("Fallo la autenticación contra NiFi. Status: {}, Body: {}", response.statusCode(), response.body());
            throw new RuntimeException("No se pudo iniciar sesión en NiFi. Status: " + response.statusCode());
        }
    }

    /**
     * Obtiene el ID del Process Group raíz (root) del canvas de NiFi.
     * @return El UUID del process group raíz.
     */
    public String getRootProcessGroupId() throws Exception {
        if (jwtToken == null) {
            throw new IllegalStateException("Cliente no autenticado. LLamar a authenticate() primero.");
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

    /**
     * Sube un fichero JSON de Flujo NiFi al orchestrador usando el endpoint de upload form-data en versión 2.x
     * @param parentId El UUID del process group padre donde residirá
     * @param groupName El nombre del nuevo Process Group
     * @param positionY Coordenada para que no colapsen visualmente
     * @param flowJsonPath La ruta al fichero con el JSON Template (flujo)
     */
    public String uploadFlowDefinition(String parentId, String groupName, int positionY, Path flowJsonPath) throws Exception {
        if (jwtToken == null) {
            throw new IllegalStateException("Cliente no autenticado.");
        }

        String boundary = "----NiFiFormBoundary" + System.currentTimeMillis();
        String crlf = "\r\n";

        byte[] fileBytes = Files.readAllBytes(flowJsonPath);
        String fileContent = new String(fileBytes, StandardCharsets.UTF_8);

        StringBuilder sb = new StringBuilder();

        // Param: groupName
        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"groupName\"").append(crlf).append(crlf);
        sb.append(groupName).append(crlf);

        // Param: positionX
        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"positionX\"").append(crlf).append(crlf);
        sb.append("0").append(crlf);

        // Param: positionY
        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"positionY\"").append(crlf).append(crlf);
        sb.append(positionY).append(crlf);

        // Param: flowDefinition
        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"flowDefinition\"; filename=\"flow.json\"").append(crlf);
        sb.append("Content-Type: application/json").append(crlf).append(crlf);
        sb.append(fileContent).append(crlf);

        // Finale
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
             return response.body();
        } else {
             logger.error("Error al cargar el flujo {}: Status {} - {}", groupName, response.statusCode(), response.body());
             throw new RuntimeException("No se pudo cargar el JSON del flujo de NiFi. Status: " + response.statusCode());
        }
    }
}
