package ca.bnc.qe.veritas.evolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository.ClassificationVoteRow;
import org.junit.jupiter.api.Test;

class ClassificationProposalServiceTest {

    private final FindingRecordRepository repo = mock(FindingRecordRepository.class);
    private final ClassificationAdvisor advisor = mock(ClassificationAdvisor.class);
    // bar: >= 3 votes across >= 2 distinct services.
    private final ClassificationProposalService service = new ClassificationProposalService(repo, advisor, 3, 2);

    // Fully stub the row mock and return it, so it is never stubbed inside an outer when(...).thenReturn(...).
    private static ClassificationVoteRow row(String type, String sev, String service, long votes) {
        ClassificationVoteRow r = mock(ClassificationVoteRow.class);
        when(r.getType()).thenReturn(type);
        when(r.getSeverity()).thenReturn(sev);
        when(r.getService()).thenReturn(service);
        when(r.getVotes()).thenReturn(votes);
        return r;
    }

    @Test
    void proposesWithTheAiSeverityWhenTheBarIsMetAndTheAiIsAvailable() {
        ClassificationVoteRow r1 = row("STATUS_CODE_MISSING", "MAJOR", "svc-a", 2);
        ClassificationVoteRow r2 = row("STATUS_CODE_MISSING", "MAJOR", "svc-b", 2);
        ClassificationVoteRow r3 = row("STATUS_CODE_MISSING", "CRITICAL", "svc-b", 1);
        when(repo.findUnspecifiedClassificationVotes()).thenReturn(List.of(r1, r2, r3));
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
    void skipsTypesBelowTheEvidenceBar() {
        // 2 votes, 1 service — below both thresholds.
        ClassificationVoteRow r = row("STATUS_CODE_MISSING", "MAJOR", "svc-a", 2);
        when(repo.findUnspecifiedClassificationVotes()).thenReturn(List.of(r));
        assertThat(service.computeProposals("alice")).isEmpty();
    }

    @Test
    void defaultsToTheFieldConsensusWhenTheAiIsUnavailable() {
        ClassificationVoteRow r1 = row("STATUS_CODE_MISSING", "MINOR", "svc-a", 3);
        ClassificationVoteRow r2 = row("STATUS_CODE_MISSING", "MAJOR", "svc-b", 1);
        when(repo.findUnspecifiedClassificationVotes()).thenReturn(List.of(r1, r2));
        when(advisor.suggest(any(), any(), anyInt(), anyString()))
                .thenReturn(ClassificationAdvisor.Suggestion.unavailable());

        ClassificationProposal p = service.computeProposals("alice").get(0);
        assertThat(p.aiSuggested()).isFalse();
        assertThat(p.suggestedSeverity()).isEqualTo(Severity.MINOR);   // the vote majority
        assertThat(p.rationale()).contains("AI unavailable");
    }

    @Test
    void ignoresRowsWithAnUnknownTypeOrSeverity() {
        ClassificationVoteRow r1 = row("NOT_A_TYPE", "MAJOR", "svc-a", 5);
        ClassificationVoteRow r2 = row("STATUS_CODE_MISSING", "BOGUS_SEV", "svc-a", 5);
        when(repo.findUnspecifiedClassificationVotes()).thenReturn(List.of(r1, r2));
        assertThat(service.computeProposals("alice")).isEmpty();
    }
}
