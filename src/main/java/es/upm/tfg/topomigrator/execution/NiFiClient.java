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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
        
        // Credenciales obligatorias desde variables de entorno (.env / docker-compose)
        String envUser = System.getenv("NIFI_USERNAME");
        if (envUser == null || envUser.trim().isEmpty()) {
            throw new IllegalStateException("La variable de entorno NIFI_USERNAME es obligatoria y no está definida.");
        }
        this.username = envUser;
        
        String envPass = System.getenv("NIFI_PASSWORD");
        if (envPass == null || envPass.trim().isEmpty()) {
            throw new IllegalStateException("La variable de entorno NIFI_PASSWORD es obligatoria y no está definida.");
        }
        this.password = envPass;

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
     * @param dynamicVariables Mapa clave-valor (ej: "##TABLA_ORIGEN##" -> "clientes") a incrustar
     */
    public String uploadFlowDefinition(String parentId, String groupName, int positionY, Path flowJsonPath, java.util.Map<String, String> dynamicVariables) throws Exception {
        if (jwtToken == null) {
            throw new IllegalStateException("Cliente no autenticado.");
        }

        String boundary = "----NiFiFormBoundary" + System.currentTimeMillis();
        String crlf = "\r\n";

        byte[] fileBytes = Files.readAllBytes(flowJsonPath);
        String fileContent = new String(fileBytes, StandardCharsets.UTF_8);

        // Parametrización en caliente: Buscamos e insertamos los tokens dinámicos de cada tabla
        if (dynamicVariables != null) {
            for (java.util.Map.Entry<String, String> entry : dynamicVariables.entrySet()) {
                fileContent = fileContent.replace(entry.getKey(), entry.getValue());
            }
        }

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
             JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
             return root.getAsJsonObject("processGroup").get("id").getAsString();
        } else {
             logger.error("Error al cargar el flujo {}: Status {} - {}", groupName, response.statusCode(), response.body());
             throw new RuntimeException("No se pudo cargar el JSON del flujo de NiFi. Status: " + response.statusCode());
        }
    }

    /**
     * Inicia o detiene los procesadores dentro de un Process Group.
     * @param processGroupId El ID del grupo subido.
     * @param state "RUNNING" o "STOPPED"
     */
    public void changeProcessGroupState(String processGroupId, String state) throws Exception {
        if (jwtToken == null) throw new IllegalStateException("Cliente no autenticado.");

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

    /**
     * Obtiene las métricas en crudo del Process Group (bytes read, records, threads).
     */
    public JsonObject getProcessGroupStatus(String processGroupId) throws Exception {
        if (jwtToken == null) throw new IllegalStateException("Cliente no autenticado.");

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


    /**
     * Limpia el Process Group temporal para no acumular grupos en el canvas de NiFi.
     * Flujo de limpieza:
     * 1) detener el grupo,
     * 2) vaciar colas pendientes,
     * 3) deshabilitar controller services del propio grupo y subgrupos,
     * 4) borrar el Process Group.
     */
    public void cleanupProcessGroup(String processGroupId) throws Exception {
        ensureAuthenticated();

        logger.info("Iniciando limpieza del Process Group temporal {} en NiFi.", processGroupId);

        try {
            changeProcessGroupState(processGroupId, "STOPPED");
        } catch (Exception e) {
            logger.warn("No se pudo detener el Process Group {} antes de limpiarlo: {}", processGroupId, e.getMessage());
        }

        try {
            emptyAllConnections(processGroupId);
        } catch (Exception e) {
            logger.warn("No se pudieron vaciar todas las colas del Process Group {}: {}", processGroupId, e.getMessage());
        }

        try {
            disableControllerServicesRecursively(processGroupId);
        } catch (Exception e) {
            logger.warn("No se pudieron deshabilitar todos los Controller Services del Process Group {}: {}",
                    processGroupId, e.getMessage());
        }

        deleteProcessGroup(processGroupId);
        logger.info("Process Group {} eliminado de NiFi correctamente.", processGroupId);
    }

    public JsonObject getProcessGroup(String processGroupId) throws Exception {
        ensureAuthenticated();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/process-groups/" + processGroupId))
                .header("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        }

        logger.error("No se pudo obtener el Process Group {}. HTTP {} - {}",
                processGroupId, response.statusCode(), response.body());
        throw new RuntimeException("Error obteniendo Process Group " + processGroupId + ": " + response.statusCode());
    }

    public JsonObject getProcessGroupFlow(String processGroupId) throws Exception {
        ensureAuthenticated();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/flow/process-groups/" + processGroupId))
                .header("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        }

        logger.error("No se pudo obtener el flow del Process Group {}. HTTP {} - {}",
                processGroupId, response.statusCode(), response.body());
        throw new RuntimeException("Error obteniendo flow del Process Group " + processGroupId + ": " + response.statusCode());
    }

    public JsonObject getControllerService(String controllerServiceId) throws Exception {
        ensureAuthenticated();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/controller-services/" + controllerServiceId))
                .header("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        }

        logger.error("No se pudo obtener el Controller Service {}. HTTP {} - {}",
                controllerServiceId, response.statusCode(), response.body());
        throw new RuntimeException("Error obteniendo Controller Service " + controllerServiceId + ": " + response.statusCode());
    }

    public void disableControllerServicesRecursively(String processGroupId) throws Exception {
        ensureAuthenticated();

        int maxAttempts = 15;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            List<ControllerServiceRef> services = collectControllerServices(processGroupId, new LinkedHashSet<>());
            if (services.isEmpty()) {
                return;
            }

            List<ControllerServiceRef> pendingServices = new ArrayList<>();
            for (ControllerServiceRef service : services) {
                if (!"DISABLED".equalsIgnoreCase(service.state)) {
                    pendingServices.add(service);
                }
            }

            if (pendingServices.isEmpty()) {
                return;
            }

            for (ControllerServiceRef service : pendingServices) {
                JsonObject serviceEntity = getControllerService(service.id);
                ControllerServiceRef refreshed = toControllerServiceRef(serviceEntity);
                if (!"DISABLED".equalsIgnoreCase(refreshed.state)) {
                    disableControllerService(refreshed);
                }
            }

            Thread.sleep(1000L);
        }

        List<ControllerServiceRef> stillPending = collectControllerServices(processGroupId, new LinkedHashSet<>());
        List<String> notDisabled = new ArrayList<>();
        for (ControllerServiceRef service : stillPending) {
            if (!"DISABLED".equalsIgnoreCase(service.state)) {
                notDisabled.add(service.name + " (" + service.id + ") estado=" + service.state);
            }
        }
        if (!notDisabled.isEmpty()) {
            throw new RuntimeException("No se pudieron deshabilitar todos los Controller Services del Process Group "
                    + processGroupId + ": " + notDisabled);
        }
    }

    private List<ControllerServiceRef> collectControllerServices(String processGroupId, Set<String> visitedProcessGroups) throws Exception {
        String normalizedId = processGroupId == null ? null : processGroupId.trim();
        if (normalizedId == null || normalizedId.isEmpty() || !visitedProcessGroups.add(normalizedId)) {
            return List.of();
        }

        JsonObject processGroupFlow = getProcessGroupFlow(normalizedId);
        JsonObject processGroupFlowEntity = getObject(processGroupFlow, "processGroupFlow");
        JsonObject flow = getObject(processGroupFlowEntity, "flow");

        List<ControllerServiceRef> services = new ArrayList<>();
        collectControllerServicesFromFlow(flow, services);

        if (flow != null && flow.has("processGroups") && flow.get("processGroups").isJsonArray()) {
            flow.getAsJsonArray("processGroups").forEach(processGroupElement -> {
                if (processGroupElement != null && processGroupElement.isJsonObject()) {
                    JsonObject processGroup = processGroupElement.getAsJsonObject();
                    String childProcessGroupId = extractComponentId(processGroup);
                    if (childProcessGroupId != null && !childProcessGroupId.isBlank()) {
                        try {
                            services.addAll(collectControllerServices(childProcessGroupId, visitedProcessGroups));
                        } catch (Exception e) {
                            throw new RuntimeException("No se pudieron recoger los Controller Services del subgrupo "
                                    + childProcessGroupId, e);
                        }
                    }
                }
            });
        }

        return services;
    }

    private void collectControllerServicesFromFlow(JsonObject flow, List<ControllerServiceRef> services) {
        if (flow == null || !flow.has("controllerServices") || !flow.get("controllerServices").isJsonArray()) {
            return;
        }

        flow.getAsJsonArray("controllerServices").forEach(controllerServiceElement -> {
            if (controllerServiceElement != null && controllerServiceElement.isJsonObject()) {
                services.add(toControllerServiceRef(controllerServiceElement.getAsJsonObject()));
            }
        });
    }

    private ControllerServiceRef toControllerServiceRef(JsonObject entity) {
        JsonObject component = getObject(entity, "component");
        JsonObject revision = getObject(entity, "revision");
        JsonObject status = getObject(entity, "status");

        String id = extractComponentId(entity);
        String name = getString(component, "name");
        if (name == null || name.isBlank()) {
            name = getString(entity, "name");
        }

        long version = revision != null && revision.has("version") && !revision.get("version").isJsonNull()
                ? revision.get("version").getAsLong()
                : 0L;

        String state = firstNonBlank(
                getString(component, "state"),
                getString(component, "scheduledState"),
                getString(status, "runStatus"),
                getString(entity, "state")
        );

        return new ControllerServiceRef(id, name != null ? name : id, version, state != null ? state : "UNKNOWN");
    }

    private void disableControllerService(ControllerServiceRef service) throws Exception {
        String clientId = "topomigrator-" + UUID.randomUUID();
        String payload = "{"
                + "\"revision\":{\"clientId\":\"" + clientId + "\",\"version\":" + service.version + "},"
                + "\"id\":\"" + service.id + "\","
                + "\"state\":\"DISABLED\","
                + "\"disconnectedNodeAcknowledged\":false"
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/controller-services/" + service.id + "/run-status"))
                .header("Authorization", "Bearer " + jwtToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            logger.error("No se pudo deshabilitar el Controller Service {} ({}). HTTP {} - {}",
                    service.name, service.id, response.statusCode(), response.body());
            throw new RuntimeException("Error deshabilitando Controller Service " + service.name + ": HTTP "
                    + response.statusCode());
        }
    }

    private void emptyAllConnections(String processGroupId) throws Exception {
        JsonObject requestEntity = createDropAllFlowFilesRequest(processGroupId);
        String dropRequestId = extractDropRequestId(requestEntity);
        if (dropRequestId == null || dropRequestId.isBlank()) {
            logger.info("NiFi no devolvió dropRequestId al vaciar colas del Process Group {}. Se continúa con el borrado.",
                    processGroupId);
            return;
        }

        int maxPolls = 60;
        for (int i = 0; i < maxPolls; i++) {
            JsonObject statusEntity = getDropAllFlowFilesRequest(processGroupId, dropRequestId);
            JsonObject dropRequest = getObject(statusEntity, "dropRequest");
            boolean complete = dropRequest != null && dropRequest.has("complete") && dropRequest.get("complete").getAsBoolean();
            if (complete) {
                deleteDropAllFlowFilesRequest(processGroupId, dropRequestId);
                return;
            }
            Thread.sleep(1000L);
        }

        throw new RuntimeException("Timeout vaciando colas del Process Group " + processGroupId);
    }

    private JsonObject createDropAllFlowFilesRequest(String processGroupId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/process-groups/" + processGroupId + "/empty-all-connections-requests"))
                .header("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200 || response.statusCode() == 202) {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        }
        if (response.statusCode() == 409) {
            throw new RuntimeException("NiFi no está todavía en estado válido para vaciar colas del Process Group " + processGroupId);
        }
        logger.error("No se pudo crear la petición de vaciado de colas para el Process Group {}. HTTP {} - {}",
                processGroupId, response.statusCode(), response.body());
        throw new RuntimeException("Error vaciando colas del Process Group " + processGroupId + ": " + response.statusCode());
    }

    private JsonObject getDropAllFlowFilesRequest(String processGroupId, String dropRequestId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/process-groups/" + processGroupId + "/empty-all-connections-requests/" + dropRequestId))
                .header("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        }
        logger.error("No se pudo consultar el drop request {} del Process Group {}. HTTP {} - {}",
                dropRequestId, processGroupId, response.statusCode(), response.body());
        throw new RuntimeException("Error consultando vaciado de colas para el Process Group " + processGroupId + ": " + response.statusCode());
    }

    private void deleteDropAllFlowFilesRequest(String processGroupId, String dropRequestId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/process-groups/" + processGroupId + "/empty-all-connections-requests/" + dropRequestId))
                .header("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/json")
                .DELETE()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            logger.warn("No se pudo eliminar el drop request {} del Process Group {}. HTTP {} - {}",
                    dropRequestId, processGroupId, response.statusCode(), response.body());
        }
    }

    private String extractDropRequestId(JsonObject requestEntity) {
        JsonObject dropRequest = getObject(requestEntity, "dropRequest");
        String directId = getString(dropRequest, "id");
        if (directId != null && !directId.isBlank()) {
            return directId;
        }
        return getString(requestEntity, "id");
    }

    private void deleteProcessGroup(String processGroupId) throws Exception {
        JsonObject processGroupEntity = getProcessGroup(processGroupId);
        JsonObject revision = getObject(processGroupEntity, "revision");

        long version = revision != null && revision.has("version") && !revision.get("version").isJsonNull()
                ? revision.get("version").getAsLong()
                : 0L;
        String clientId = revision != null && revision.has("clientId") && !revision.get("clientId").isJsonNull()
                ? revision.get("clientId").getAsString()
                : "topomigrator-" + UUID.randomUUID();

        String deleteUrl = baseUrl + "/process-groups/" + processGroupId
                + "?version=" + version
                + "&clientId=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&disconnectedNodeAcknowledged=false";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(deleteUrl))
                .header("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/json")
                .DELETE()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            logger.error("No se pudo eliminar el Process Group {}. HTTP {} - {}",
                    processGroupId, response.statusCode(), response.body());
            throw new RuntimeException("Error eliminando Process Group " + processGroupId + ": " + response.statusCode());
        }
    }

    private void ensureAuthenticated() {
        if (jwtToken == null) {
            throw new IllegalStateException("Cliente no autenticado. Llamar a authenticate() primero.");
        }
    }

    private JsonObject getObject(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
    }

    private String getString(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return null;
        }
        return parent.get(key).getAsString();
    }

    private String extractComponentId(JsonObject entity) {
        String directId = getString(entity, "id");
        if (directId != null && !directId.isBlank()) {
            return directId;
        }

        JsonObject component = getObject(entity, "component");
        return getString(component, "id");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static final class ControllerServiceRef {
        private final String id;
        private final String name;
        private final long version;
        private final String state;

        private ControllerServiceRef(String id, String name, long version, String state) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.state = state;
        }
    }
}
