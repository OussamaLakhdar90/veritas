package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import org.junit.jupiter.api.Test;

/** Rebuilding an in-memory Finding (incl. disposition audit) from a persisted FindingRecord, with guarded enums. */
class FindingMapperTest {

    @Test
    void rebuildsFindingWithDispositionAudit() {
        FindingRecord r = new FindingRecord();
        r.setFingerprint("fp1");
        r.setType("RESPONSE_SCHEMA_MISMATCH");
        r.setLayer("L4");
        r.setSeverity("MAJOR");
        r.setConfidence("MEDIUM");
        r.setOrigin("DETERMINISTIC");
        r.setEndpoint("GET /x");
        r.setSummary("schema differs");
        r.setStatus("REJECTED");
        r.setReviewedBy("alice");
        r.setReviewedAt(Instant.parse("2026-06-23T14:09:00Z"));
        r.setCodeFile("X.java");
        r.setCodeStartLine(10);
        r.setCodeEndLine(12);
        r.setCodeSnippet("return null;");

        Finding f = FindingMapper.toFinding(r);

        assertThat(f.getType()).isEqualTo(FindingType.RESPONSE_SCHEMA_MISMATCH);
        assertThat(f.getSeverity()).isEqualTo(Severity.MAJOR);
        assertThat(f.getStatus()).isEqualTo("REJECTED");
        assertThat(f.getReviewedBy()).isEqualTo("alice");
        assertThat(f.getReviewedAt()).isEqualTo(Instant.parse("2026-06-23T14:09:00Z"));
        assertThat(f.getCodeEvidence().location()).isEqualTo("X.java");
        assertThat(f.getCodeEvidence().startLine()).isEqualTo(10);
    }

    @Test
    void guardsUnknownEnumValuesAndDefaultsStatus() {
        FindingRecord bad = new FindingRecord();
        bad.setType("NOT_A_TYPE");
        bad.setSeverity("NOPE");   // unknown enum names must not throw
        Finding f = FindingMapper.toFinding(bad);
        assertThat(f.getType()).isNull();
        assertThat(f.getSeverity()).isNull();
        assertThat(f.getStatus()).isEqualTo("OPEN");
        assertThat(f.getCodeEvidence()).isNull();
    }
}
