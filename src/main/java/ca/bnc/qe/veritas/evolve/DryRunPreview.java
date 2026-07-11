package ca.bnc.qe.veritas.evolve;

/**
 * The result of a developer dry-run of a classification promotion: the deterministic {@code DiffEngine.java} edit
 * was rendered from a local checkout and written to a review folder — no clone, gate, or PR happened. It reports
 * where the edited file + the human-readable manifest landed so the maintainer can open and verify them.
 */
public record DryRunPreview(
        String trainId,
        String findingType,
        String finalSeverity,
        boolean aiSuggested,
        String editedFilePath,
        String manifestPath,
        String mockBranch,
        String mockPrTitle) {
}
