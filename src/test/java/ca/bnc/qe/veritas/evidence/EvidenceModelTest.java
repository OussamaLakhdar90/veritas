package ca.bnc.qe.veritas.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The value records: EvidenceUnit immutability, and SourceMix computed from FetchProvenance successes. */
class EvidenceModelTest {

    @Test
    void evidenceUnitDefensivelyCopiesAndIsImmutable() {
        Set<String> hints = new java.util.HashSet<>(Set.of("login"));
        EvidenceUnit u = EvidenceUnit.of("CODE:AuthController#POST /login", SourceKind.CODE, UnitType.ENDPOINT,
                "POST /login", "the login endpoint", "http://repo/AuthController.java", hints);
        hints.add("mutated");   // must not leak into the unit
        assertThat(u.hints()).containsExactly("login");
        assertThat(u.links()).isEmpty();   // null links → empty, not NPE
        assertThatThrownBy(() -> u.hints().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sourceMixIsComputedFromFetchSuccessesNotRequests() {
        // Confluence was requested (12 pages) but all failed; Jira fetched 5; no code.
        FetchProvenance p = new FetchProvenance(Map.of(
                SourceKind.JIRA, new FetchProvenance.Counts(8, 5, List.of("JIRA-9 timed out")),
                SourceKind.CONFLUENCE, new FetchProvenance.Counts(12, 0, List.of("page 7: 500"))));
        SourceMix mix = p.toMix();

        assertThat(mix.jira()).isTrue();
        assertThat(mix.confluence()).isFalse();   // requested but nothing fetched → not in the mix
        assertThat(mix.code()).isFalse();
        assertThat(mix.has(SourceKind.POLICY)).isTrue();   // policy is always available
        assertThat(p.requestedButEmpty(SourceKind.CONFLUENCE)).isTrue();   // §1.3 hard-fail trigger
        assertThat(p.requestedButEmpty(SourceKind.JIRA)).isFalse();
    }

    @Test
    void codeOnlyMixIsRecognised() {
        FetchProvenance p = new FetchProvenance(Map.of(
                SourceKind.CODE, new FetchProvenance.Counts(1, 1, List.of())));
        assertThat(p.toMix().codeOnly()).isTrue();
        assertThat(p.toMix().any()).isTrue();
    }

    @Test
    void hasHardFailFlagsAnEmptySelectedSourceButIgnoresPolicy() {
        FetchProvenance p = new FetchProvenance(Map.of(
                SourceKind.JIRA, new FetchProvenance.Counts(8, 5, List.of()),
                SourceKind.CONFLUENCE, new FetchProvenance.Counts(12, 0, List.of("page 7: 500"))));
        assertThat(p.hasHardFail(Set.of(SourceKind.JIRA, SourceKind.CONFLUENCE))).isTrue();   // confluence came back empty
        assertThat(p.hasHardFail(Set.of(SourceKind.JIRA))).isFalse();
        // POLICY is pre-authored, never user-selected → must not trip the gate even if it appears requested.
        FetchProvenance policyEmpty = new FetchProvenance(Map.of(
                SourceKind.POLICY, new FetchProvenance.Counts(3, 0, List.of())));
        assertThat(policyEmpty.hasHardFail(Set.of(SourceKind.POLICY))).isFalse();
    }

    @Test
    void jiraFactoryHardcodesSourceAndKeepsLifecycleAndLinks() {
        EvidenceUnit u = EvidenceUnit.jira("JIRA-1012", UnitType.REQUIREMENT, "Lockout rule",
                "Account locks after 5 failed attempts", "http://jira/JIRA-1012",
                "DONE", "High", Set.of("JIRA-1013"), Set.of("login"));
        assertThat(u.source()).isEqualTo(SourceKind.JIRA);
        assertThat(u.lifecycle()).isEqualTo("DONE");
        assertThat(u.priority()).isEqualTo("High");
        assertThat(u.links()).containsExactly("JIRA-1013");
        assertThat(u.hints()).containsExactly("login");
    }
}
