package ca.bnc.qe.veritas.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a long list of line-items into chunks that each fit a token budget, so a large input (e.g. every
 * Jira issue in a release) can be processed in batches and merged — instead of silently eliding the middle
 * to fit one prompt (release-plan blind spot: "batch by epic/component then merge; deterministic match first").
 *
 * <p>Deterministic and order-preserving: lines are packed greedily into chunks; a single line larger than the
 * budget is emitted alone (never dropped). Always returns at least one chunk (header-only when there are no lines).
 */
public final class PromptChunker {

    private PromptChunker() {
    }

    /**
     * Pack {@code lines} into chunks of {@code header + lines}, each estimated to stay within {@code perChunkTokens}.
     *
     * @param header           a prefix line repeated at the top of every chunk (context for the batch); may be blank
     * @param lines            the items to distribute (e.g. {@code "- [KEY] summary"})
     * @param perChunkTokens   per-chunk token budget (~4 chars/token); {@code <= 0} means "everything in one chunk"
     * @return one or more chunk strings, original order preserved
     */
    public static List<String> chunkLines(String header, List<String> lines, int perChunkTokens) {
        String head = header == null ? "" : (header.isBlank() ? "" : header + "\n");
        List<String> chunks = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            chunks.add(head.isBlank() ? "" : head);
            return chunks;
        }
        if (perChunkTokens <= 0) {
            chunks.add(head + String.join("\n", lines));
            return chunks;
        }

        int headTokens = PromptComposer.estimateTokens(head);
        StringBuilder current = new StringBuilder(head);
        int currentTokens = headTokens;
        boolean hasLine = false;

        for (String line : lines) {
            int lineTokens = PromptComposer.estimateTokens(line + "\n");
            // Start a new chunk when adding this line would overflow — but only if the current chunk already has one.
            if (hasLine && currentTokens + lineTokens > perChunkTokens) {
                chunks.add(current.toString());
                current = new StringBuilder(head);
                currentTokens = headTokens;
                hasLine = false;
            }
            current.append(line).append("\n");
            currentTokens += lineTokens;
            hasLine = true;
        }
        chunks.add(current.toString());
        return chunks;
    }
}
