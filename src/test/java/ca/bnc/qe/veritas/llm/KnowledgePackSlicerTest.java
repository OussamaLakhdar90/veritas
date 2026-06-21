package ca.bnc.qe.veritas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgePackSlicerTest {

    private final KnowledgePackSlicer slicer = new KnowledgePackSlicer();

    private static final String PACK = """
        ## 0. Grounding rules
        always cite
        ## 1. Traceability
        link everything
        ## 5. ISO 25010
        quality chars
        ## 6.1 EP and BVA
        techniques
        ## 12. API heuristics
        rest rules
        """;

    @Test
    void keepsRequestedSectionsPlusSectionZero() {
        String sliced = slicer.slice(PACK, Set.of("1", "5"));

        assertThat(sliced).contains("Grounding rules");   // §0 always
        assertThat(sliced).contains("Traceability");
        assertThat(sliced).contains("ISO 25010");
        assertThat(sliced).doesNotContain("techniques");  // §6 not requested
        assertThat(sliced).doesNotContain("rest rules");  // §12 not requested
    }

    @Test
    void majorSectionMatchKeepsSubsections() {
        String sliced = slicer.slice(PACK, Set.of("6"));
        assertThat(sliced).contains("EP and BVA");        // §6.1 kept via major "6"
        assertThat(sliced).contains("Grounding rules");
    }
}
