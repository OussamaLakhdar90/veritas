package ca.bnc.qe.veritas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PromptChunkerTest {

    @Test
    void everythingFitsInOneChunkWhenUnderBudget() {
        List<String> lines = List.of("- a", "- b", "- c");
        List<String> chunks = PromptChunker.chunkLines("Header:", lines, 10_000);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).startsWith("Header:\n").contains("- a").contains("- b").contains("- c");
    }

    @Test
    void splitsIntoMultipleChunksAndRepeatsTheHeaderPreservingOrderAndCoverage() {
        // Tiny budget → ~one line per chunk; the header is repeated and every line is kept exactly once, in order.
        List<String> lines = List.of("- one", "- two", "- three", "- four");
        List<String> chunks = PromptChunker.chunkLines("Header:", lines, 5);

        assertThat(chunks.size()).isGreaterThan(1);
        chunks.forEach(c -> assertThat(c).startsWith("Header:\n"));
        String all = String.join("", chunks);
        for (String line : lines) {
            assertThat(all).contains(line);
        }
        // order preserved across the concatenation
        assertThat(all.indexOf("- one")).isLessThan(all.indexOf("- two"));
        assertThat(all.indexOf("- three")).isLessThan(all.indexOf("- four"));
    }

    @Test
    void aSingleOversizedLineIsEmittedAloneNeverDropped() {
        String big = "- " + "x".repeat(5_000);
        List<String> chunks = PromptChunker.chunkLines("H:", List.of(big), 50);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains(big);
    }

    @Test
    void emptyInputYieldsASingleHeaderOnlyChunk() {
        assertThat(PromptChunker.chunkLines("H:", List.of(), 100)).containsExactly("H:\n");
    }

    @Test
    void zeroBudgetMeansOneChunkWithEverything() {
        List<String> lines = List.of("- a", "- b");
        assertThat(PromptChunker.chunkLines("H:", lines, 0)).hasSize(1);
    }
}
