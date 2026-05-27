package es.upm.tfg.topomigrator.execution;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class NiFiFlowTemplateQualityTest {

    @Test
    public void putDatabaseRecordDoesNotRollbackFailedFlowFilesIntoRetryLoop() throws Exception {
        JsonObject template = JsonParser.parseString(Files.readString(Path.of("flows", "MainMigration.json"))).getAsJsonObject();
        JsonObject putDatabaseRecord = findProcessorByName(template, "PutDatabaseRecord");

        assertNotNull(putDatabaseRecord);
        assertEquals("0", getString(putDatabaseRecord, "retryCount"));
        JsonObject properties = putDatabaseRecord.getAsJsonObject("properties");
        assertEquals("false", getString(properties, "Rollback On Failure"));
        assertEquals("##STATEMENT_TYPE##", getString(properties, "Statement Type"));
        assertEquals("##UPDATE_KEYS##", getString(properties, "Update Keys"));
    }

    private JsonObject findProcessorByName(JsonElement element, String name) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (name.equals(getString(object, "name"))
                    && "PROCESSOR".equals(getString(object, "componentType"))
                    && "org.apache.nifi.processors.standard.PutDatabaseRecord".equals(getString(object, "type"))) {
                return object;
            }
            for (String key : object.keySet()) {
                JsonObject found = findProcessorByName(object.get(key), name);
                if (found != null) {
                    return found;
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                JsonObject found = findProcessorByName(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }
}
