package ca.bnc.qe.veritas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/** Proves the vendored real prompt + knowledge-pack slicing work end-to-end (token optimization). */
class PromptLibraryTest {

    private final PromptLibrary library = new PromptLibrary(new DefaultResourceLoader(), new KnowledgePackSlicer());

    @Test
    void assemblesRealPromptWithOnlyRequestedPackSections() {
        String prompt = library.assemble("validate-service-contract.prompt.md",
                Set.of("1", "5", "6", "12"), Map.of());

        assertThat(prompt).contains("Validate Service Contract");   // the real prompt body
        assertThat(prompt).contains("Grounding rules");             // §0 always included
        assertThat(prompt).contains("Terminology");                 // §1 requested
        assertThat(prompt).contains("API testing");                 // §12 requested
        assertThat(prompt).doesNotContain("Seven testing principles"); // §2 not requested → sliced out
        assertThat(prompt).doesNotContain("Standards referenced");  // §13 not requested → sliced out
        assertThat(prompt).doesNotContain("Paste the full content"); // placeholder replaced
    }

    @Test
    void testPlanPromptKeepsItsBodyWhenPackInjected() {
        // Regression: the [KNOWLEDGE PACK] marker must be at the BOTTOM, or the body is discarded on assemble.
        String prompt = library.assemble("generate-test-plan.prompt.md", Set.of("8", "9"), Map.of());
        assertThat(prompt).contains("Principal Test Manager");   // role/instructions preserved
        assertThat(prompt).contains("SELF-REVIEW");              // the differentiator section preserved
        assertThat(prompt).contains("## Knowledge pack");        // pack still injected
        assertThat(prompt).contains("Risk management");          // requested §9
    }

    @Test
    void section0IsSlimAndDeepSectionsSliceOnDemand() {
        // Always-on §0 (empty request): keeps the rules but NOT the precedence table (moved to §14).
        String alwaysOn = library.assemble("generate-test-plan.prompt.md", Set.of(), Map.of());
        assertThat(alwaysOn).contains("Grounding rules");
        assertThat(alwaysOn).doesNotContain("Source-precedence reference");
        assertThat(alwaysOn).doesNotContain("Stakeholder/role management");   // table row now lives in §14

        // Requesting planning + risk pulls exactly those deep sections.
        String planSlice = library.assemble("generate-test-plan.prompt.md", Set.of("8", "9"), Map.of());
        assertThat(planSlice).contains("Test planning");                      // §8
        assertThat(planSlice).contains("Risk management");                    // §9
        assertThat(planSlice).doesNotContain("Static testing & reviews");     // §7 not requested
        assertThat(planSlice).doesNotContain("Source-precedence reference");  // §14 absent unless explicitly asked
    }
}
