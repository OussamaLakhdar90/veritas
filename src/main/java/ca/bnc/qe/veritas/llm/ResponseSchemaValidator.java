package ca.bnc.qe.veritas.llm;

import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Validates an LLM JSON reply against the step's declared schema
 * ({@code classpath:veritas/schemas/<file>}). A mismatch throws — the runner then retries or fails the
 * step, so malformed model output never propagates downstream.
 */
@Component
public class ResponseSchemaValidator {

    private final ResourceLoader resourceLoader;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final ConcurrentHashMap<String, JsonSchema> cache = new ConcurrentHashMap<>();

    public ResponseSchemaValidator(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public void validate(JsonNode node, String schemaFile) {
        JsonSchema schema = cache.computeIfAbsent(schemaFile, this::load);
        Set<ValidationMessage> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            String detail = errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "));
            throw new IllegalStateException("LLM reply failed schema '" + schemaFile + "': " + detail);
        }
    }

    private JsonSchema load(String schemaFile) {
        Resource resource = resourceLoader.getResource("classpath:veritas/schemas/" + schemaFile);
        try (InputStream in = resource.getInputStream()) {
            return factory.getSchema(in);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load schema '" + schemaFile + "': " + e.getMessage(), e);
        }
    }
}
