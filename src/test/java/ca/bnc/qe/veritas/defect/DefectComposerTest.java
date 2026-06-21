package ca.bnc.qe.veritas.defect;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import org.junit.jupiter.api.Test;

class DefectComposerTest {

    @Test
    void composesTitleDescriptionAndLabels() {
        FindingRecord f = new FindingRecord();
        f.setType("MISSING_ENDPOINT");
        f.setEndpoint("POST /v1/transfers");
        f.setSeverity("CRITICAL");
        f.setLayer("L2");
        f.setSummary("Endpoint exists in code but missing from the spec");
        f.setCodeFile("/src/PolicyController.java");
        f.setCodeStartLine(45);
        f.setCitation("CTFL §1.4.4");

        JiraCreateRequest req = new DefectComposer().compose(f, "ciam-policies", "CIAM", null);

        assertThat(req.issueType()).isEqualTo("Bug");
        assertThat(req.summary()).isEqualTo("ciam-policies — POST /v1/transfers — missing endpoint");
        assertThat(req.descriptionParagraphs()).anyMatch(p -> p.contains("Endpoint exists in code"));
        assertThat(req.descriptionParagraphs()).anyMatch(p -> p.contains("Evidence:") && p.contains("45"));
        assertThat(req.descriptionParagraphs()).anyMatch(p -> p.contains("CTFL"));
        assertThat(req.labels()).contains("contract-validation", "layer-L2", "severity-CRITICAL");
    }
}
