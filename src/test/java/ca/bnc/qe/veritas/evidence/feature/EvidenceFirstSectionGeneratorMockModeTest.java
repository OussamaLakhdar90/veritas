package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.SourceMix;
import ca.bnc.qe.veritas.evidence.UnitType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end mock-mode guard: runs the generator through the REAL MockLlmGateway + real PromptComposer/prompt +
 * real schema + CitationValidator. It proves mock-mode synthesis produces a grounded section AND guards the
 * coupling between the generator's "Cite ONLY these unit ids: [...]" contract and the mock's parser — if either
 * drifts, the mock cites a non-allowed id, the citation check fails, the section is dropped, and this test goes red.
 */
@SpringBootTest
class EvidenceFirstSectionGeneratorMockModeTest {

    @Autowired
    private EvidenceFirstSectionGenerator generator;

    @Test
    void mockModeProducesAGroundedSectionCitingAnAllowedId() {
        EvidenceUnit unit = EvidenceUnit.of("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "Lockout",
                "Account locks after 5 failed attempts", null, Set.of());
        FeatureIndex index = new FeatureIndex(
                Map.of("feat-1", new Feature("feat-1", "login", List.of("JIRA-1"), FeatureStatus.PLANNED)),
                Map.of("JIRA-1", unit), Set.of(), Set.of(), new SourceMix(false, true, false), "src");

        SectionResult section = generator.generate("riskRegister", "List the product risks.",
                Set.of("1"), ModelTier.DEEP, index, "feat-1", "tester");

        assertThat(section.node()).isPresent();   // mock parsed the allowed id from the real contract → valid citation
        var evidence = section.node().get().path("evidence").get(0);
        assertThat(evidence.path("unitId").asText()).isEqualTo("JIRA-1");
        // quote is now mandatory + grounded: a verbatim slice of the unit's text (proves the bypass is closed end-to-end).
        String quote = evidence.path("quote").asText("");
        assertThat(quote).isNotBlank();
        assertThat("Account locks after 5 failed attempts").contains(quote);
    }
}
