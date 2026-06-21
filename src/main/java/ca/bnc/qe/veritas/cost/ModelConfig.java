package ca.bnc.qe.veritas.cost;

import java.io.IOException;
import java.io.InputStream;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/** Loads the model catalog + policy YAML into beans at startup. */
@Configuration
public class ModelConfig {

    private final ObjectMapper yaml = YAMLMapper.builder()
            .addModule(new ParameterNamesModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
            .build();

    @Bean
    public ModelCatalog modelCatalog(ResourceLoader resourceLoader) throws IOException {
        return read(resourceLoader, "classpath:veritas/models.yaml", ModelCatalog.class);
    }

    @Bean
    public ModelPolicy modelPolicy(ResourceLoader resourceLoader) throws IOException {
        return read(resourceLoader, "classpath:veritas/model-policy.yaml", ModelPolicy.class);
    }

    private <T> T read(ResourceLoader rl, String location, Class<T> type) throws IOException {
        try (InputStream in = rl.getResource(location).getInputStream()) {
            return yaml.readValue(in, type);
        }
    }
}
