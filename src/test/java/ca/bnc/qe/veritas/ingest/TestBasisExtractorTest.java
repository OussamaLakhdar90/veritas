package ca.bnc.qe.veritas.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TestBasisExtractorTest {

    private final TestBasisExtractor extractor = new TestBasisExtractor();

    @Test
    void extractsCitableBasisWithKinds() {
        String md = """
            # Policy creation

            ## Acceptance Criteria
            - Given a valid request, the policy is created
            - The response must return 201

            ## Rules
            - A policy name must be unique
            - Optional description

            | Field | Rule |
            | --- | --- |
            | name | required, max 10 |
            """;
        NormalizedDoc doc = new NormalizedDoc("jira", "JIRA-42", "Policy creation", md);

        List<TestBasisItem> items = extractor.extract(doc);

        // acceptance-criteria bullets
        assertThat(items).anyMatch(i -> i.kind() == TestBasisKind.ACCEPTANCE_CRITERIA
                && i.text().contains("policy is created"));
        // modal "must" under a non-AC section → business rule
        assertThat(items).anyMatch(i -> i.kind() == TestBasisKind.BUSINESS_RULE
                && i.text().contains("name must be unique"));
        // plain bullet under non-AC section → requirement
        assertThat(items).anyMatch(i -> i.kind() == TestBasisKind.REQUIREMENT
                && i.text().contains("Optional description"));
        // table data row → business rule
        assertThat(items).anyMatch(i -> i.kind() == TestBasisKind.BUSINESS_RULE
                && i.text().contains("name") && i.text().contains("required"));
        // every item carries a traceability id rooted at the source
        assertThat(items).allMatch(i -> i.id().startsWith("JIRA-42#"));
        assertThat(items).allMatch(i -> i.origin().equals("JIRA-42"));
    }
}
