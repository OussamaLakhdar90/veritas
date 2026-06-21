package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import org.junit.jupiter.api.Test;

/** A DTO referenced by an endpoint but absent from the scanned sources must be surfaced as a blind spot. */
class JavaSpringExtractorBlindSpotTest {

    @Test
    void recordsBlindSpotForUnresolvedDto() throws Exception {
        Path root = Path.of(getClass().getClassLoader().getResource("fixtures/blindspot").toURI());
        ApiModel model = new JavaSpringExtractor().extract(root);
        assertThat(model.blindSpots()).anySatisfy(b -> assertThat(b).contains("ExternalWidget"));
    }
}
