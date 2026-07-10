package ca.bnc.qe.veritas.evolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.config.EvolveProperties;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository.ClassificationVoteRow;
import org.junit.jupiter.api.Test;

class ClassificationProposalServiceTest {

    private final FindingRecordRepository repo = mock(FindingRecordRepository.class);
    private final ClassificationAdvisor advisor = mock(ClassificationAdvisor.class);
    // Default bar: >= 3 votes across >= 2 distinct services.
    private final ClassificationProposalService service =
            new ClassificationProposalService(repo, advisor, new EvolveProperties());

    // One finding row (dedupe key = fingerprint). Fully stubbed then returned, so it is never stubbed inside an
    // outer when(...).thenReturn(...). Rows are supplied newest-first (as the repository orders by createdAt desc).
    private static ClassificationVoteRow row(String type, String sev, String service, String fingerprint) {
        ClassificationVoteRow r = mock(ClassificationVoteRow.class);
        when(r.getType()).thenReturn(type);
        when(r.getSeverity()).thenReturn(sev);
        when(r.getService()).thenReturn(service);
        when(r.getFingerprint()).thenReturn(fingerprint);
        return r;
    }

    private void stubVotes(ClassificationVoteRow... rows) {
        when(repo.findUnspecifiedClassificationVotes()).thenReturn(List.of(rows));
    }

    @Test
    void proposesWithTheAiSeverityWhenTheBarIsMetAndTheAiIsAvailable() {
        ClassificationVoteRow r1 = row("STATUS_CODE_MISSING", "MAJOR", "svc-a", "f1");
        ClassificationVoteRow r2 = row("STATUS_CODE_MISSING", "MAJOR", "svc-a", "f2");
        ClassificationVoteRow r3 = row("STATUS_CODE_MISSING", "MAJOR", "svc-b", "f3");
        ClassificationVoteRow r4 = row("STATUS_CODE_MISSING", "MAJOR", "svc-b", "f4");
        ClassificationVoteRow r5 = row("STATUS_CODE_MISSING", "CRITICAL", "svc-b", "f5");
        stubVotes(r1, r2, r3, r4, r5);
        when(advisor.suggest(any(), any(), anyInt(), anyString()))
                .thenReturn(new ClassificationAdvisor.Suggestion(true, Severity.MAJOR, "rubric says MAJOR"));

        List<ClassificationProposal> out = service.computeProposals("alice");

        assertThat(out).hasSize(1);
        ClassificationProposal p = out.get(0);
        assertThat(p.findingType()).isEqualTo(FindingType.STATUS_CODE_MISSING);
        assertThat(p.suggestedSeverity()).isEqualTo(Severity.MAJOR);
        assertThat(p.aiSuggested()).isTrue();
        assertThat(p.voteCount()).isEqualTo(5);
        assertThat(p.distinctServices()).isEqualTo(2);
        assertThat(p.voteBreakdown()).containsEntry(Severity.MAJOR, 4).containsEntry(Severity.CRITICAL, 1);
    }

    @Test
    void countsAFindingOnceUsingItsLatestOverrideWhenItChangedAcrossReScans() {
        // fX was CRITICAL then re-overridden to MINOR (the MINOR row is newer → listed first). It must count ONCE
        // as MINOR, not twice — otherwise the stale CRITICAL both inflates the tally and can win the consensus.
        ClassificationVoteRow newest = row("STATUS_CODE_MISSING", "MINOR", "svc-a", "fX");
        ClassificationVoteRow stale = row("STATUS_CODE_MISSING", "CRITICAL", "svc-a", "fX");
        ClassificationVoteRow r2 = row("STATUS_CODE_MISSING", "MINOR", "svc-b", "f2");
        ClassificationVoteRow r3 = row("STATUS_CODE_MISSING", "MINOR", "svc-a", "f3");
        stubVotes(newest, stale, r2, r3);
        when(advisor.suggest(any(), any(), anyInt(), anyString()))
                .thenReturn(ClassificationAdvisor.Suggestion.unavailable());

        ClassificationProposal p = service.computeProposals("alice").get(0);
        assertThat(p.voteCount()).isEqualTo(3);
        assertThat(p.voteBreakdown()).containsEntry(Severity.MINOR, 3).doesNotContainKey(Severity.CRITICAL);
        assertThat(p.suggestedSeverity()).isEqualTo(Severity.MINOR);
    }

    @Test
    void skipsTypesBelowTheEvidenceBar() {
        // 2 votes, 1 service — below both thresholds.
        ClassificationVoteRow r1 = row("STATUS_CODE_MISSING", "MAJOR", "svc-a", "f1");
        ClassificationVoteRow r2 = row("STATUS_CODE_MISSING", "MAJOR", "svc-a", "f2");
        stubVotes(r1, r2);
        assertThat(service.computeProposals("alice")).isEmpty();
    }

    @Test
    void defaultsToTheFieldConsensusWhenTheAiIsUnavailable() {
        ClassificationVoteRow r1 = row("STATUS_CODE_MISSING", "MINOR", "svc-a", "f1");
        ClassificationVoteRow r2 = row("STATUS_CODE_MISSING", "MINOR", "svc-a", "f2");
        ClassificationVoteRow r3 = row("STATUS_CODE_MISSING", "MINOR", "svc-b", "f3");
        ClassificationVoteRow r4 = row("STATUS_CODE_MISSING", "MAJOR", "svc-b", "f4");
        stubVotes(r1, r2, r3, r4);
        when(advisor.suggest(any(), any(), anyInt(), anyString()))
                .thenReturn(ClassificationAdvisor.Suggestion.unavailable());

        ClassificationProposal p = service.computeProposals("alice").get(0);
        assertThat(p.aiSuggested()).isFalse();
        assertThat(p.suggestedSeverity()).isEqualTo(Severity.MINOR);   // the vote majority
        assertThat(p.rationale()).contains("AI unavailable");
    }

    @Test
    void ignoresRowsWithAnUnknownTypeOrSeverity() {
        ClassificationVoteRow r1 = row("NOT_A_TYPE", "MAJOR", "svc-a", "f1");
        ClassificationVoteRow r2 = row("STATUS_CODE_MISSING", "BOGUS_SEV", "svc-a", "f2");
        stubVotes(r1, r2);
        assertThat(service.computeProposals("alice")).isEmpty();
    }
}
