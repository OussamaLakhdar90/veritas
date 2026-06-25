package ca.bnc.qe.veritas.evidence.feature;

import java.util.List;
import java.util.Set;

/**
 * The deterministic gap analysis of a {@link FeatureIndex} (design §3.3): the coverage gaps, plus the set of
 * features that need a <b>contradiction check</b> during synthesis. A feature in
 * {@link #contradictionCheckFeatureIds} has both intent and code — the risk prompt for it must either assert that
 * the spec and the implementation agree, or emit a gap-risk citing both sides (so an intent↔impl mismatch can't
 * pass silently). This is what forces the headline "Jira says X, code does Y" check rather than hoping the LLM notices.
 */
public record GapReport(List<Gap> gaps, Set<String> contradictionCheckFeatureIds) {

    public GapReport {
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        contradictionCheckFeatureIds = contradictionCheckFeatureIds == null ? Set.of() : Set.copyOf(contradictionCheckFeatureIds);
    }

    public List<Gap> gapsOfKind(GapKind kind) {
        return gaps.stream().filter(g -> g.kind() == kind).toList();
    }
}
