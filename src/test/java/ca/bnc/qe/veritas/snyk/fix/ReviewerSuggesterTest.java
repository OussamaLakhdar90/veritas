package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reviewer suggestions come from the pom's recent (human) committers, skipping the Veritas bot. */
class ReviewerSuggesterTest {

    @Test
    void suggestsRecentCommitterDerivingUsernameFromEmail(@TempDir Path dir) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            Files.writeString(dir.resolve("pom.xml"), "<project/>");
            git.add().addFilepattern("pom.xml").call();
            git.commit().setMessage("init").setAuthor("Alice QA", "alice.qa@bnc.ca")
                    .setCommitter("Alice QA", "alice.qa@bnc.ca").call();
        }
        List<String> reviewers = new ReviewerSuggester(new FrameworkProperties()).suggest(dir, "pom.xml", 5);
        assertThat(reviewers).containsExactly("alice.qa");
    }

    @Test
    void skipsTheVeritasBotAndFallsBackToConfiguredDefaults(@TempDir Path dir) throws Exception {
        FrameworkProperties fw = new FrameworkProperties();
        fw.setDefaultReviewers(List.of("lead.reviewer"));
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            Files.writeString(dir.resolve("pom.xml"), "<project/>");
            git.add().addFilepattern("pom.xml").call();
            git.commit().setMessage("bot").setAuthor("Veritas", "veritas@bnc.ca")
                    .setCommitter("Veritas", "veritas@bnc.ca").call();
        }
        // Only the Veritas bot touched the file → no human suggestion → configured default.
        assertThat(new ReviewerSuggester(fw).suggest(dir, "pom.xml", 5)).containsExactly("lead.reviewer");
    }
}
