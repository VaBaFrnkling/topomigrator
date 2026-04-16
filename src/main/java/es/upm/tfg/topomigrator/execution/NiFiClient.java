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
import java.security.SecureRandom;
import java.time.Duration;

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
     * Simula la creación/activación del flujo (Process Group) para una tabla en concreto.
     * @param tableName Nombre de la tabla
     */
    public void createProcessGroupParaTabla(String tableName) throws Exception {
        if (jwtToken == null) {
            throw new IllegalStateException("Cliente no autenticado. LLamar a authenticate() primero.");
        }

        logger.debug("Construyendo payload de invocación para la tabla {}", tableName);
        // FIXME: Esta request es figurativa; deberíamos obtener el root-group y crear hijos
        //        Por ahora consultamos y loggear para evitar complejidad asumiendo éxito
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/process-groups/root"))
                .header("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            logger.info("Respuesta exitosa de NiFi Process Group Root confirmando viabilidad para la tabla '{}'.", tableName);
        } else {
            logger.warn("NiFi no respondió exitosamente para tabla {}. API Status: {}", tableName, response.statusCode());
        }
    }
}
