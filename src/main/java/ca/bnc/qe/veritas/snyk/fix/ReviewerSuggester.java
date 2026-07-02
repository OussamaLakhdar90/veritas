package ca.bnc.qe.veritas.snyk.fix;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.stereotype.Component;

/**
 * Suggests PR reviewers from a pom's recent commit history — the people who last touched the file are the natural
 * reviewers of a version bump to it. Usernames are derived from the author email's local part (the BNC corporate id
 * convention); the suggestions are always <b>editable</b> by the user before the PR opens. Falls back to a configured
 * default list when history yields nothing. Never suggests Veritas's own bot commits.
 */
@Component
public class ReviewerSuggester {

    private final FrameworkProperties fw;

    public ReviewerSuggester(FrameworkProperties fw) {
        this.fw = fw;
    }

    /** Up to {@code limit} suggested reviewer usernames for the given pom, most-recent committer first. */
    public List<String> suggest(Path repoDir, String filePath, int limit) {
        List<String> found = recentCommitters(repoDir, filePath, limit);
        return found.isEmpty() ? fw.getDefaultReviewers() : found;
    }

    private List<String> recentCommitters(Path repoDir, String filePath, int limit) {
        try (Git git = Git.open(repoDir.toFile())) {
            LinkedHashSet<String> users = new LinkedHashSet<>();
            for (RevCommit c : git.log().addPath(filePath).setMaxCount(40).call()) {
                String u = toUsername(c.getAuthorIdent());
                if (u != null) {
                    users.add(u);
                }
                if (users.size() >= limit) {
                    break;
                }
            }
            return new ArrayList<>(users);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Email local part (the corporate id / Bitbucket username), skipping Veritas's own bot identity. A raw display
     * name that isn't a plausible username (contains whitespace, e.g. "Alice Smith") is NOT emitted — Bitbucket would
     * silently drop it — so we fall back to the configured default reviewers instead of an unresolvable name.
     */
    private String toUsername(PersonIdent ident) {
        String email = ident.getEmailAddress();
        if (email != null && email.toLowerCase(java.util.Locale.ROOT).contains("veritas")) {
            return null;
        }
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf('@'));
        }
        String name = ident.getName();
        if (name == null || name.isBlank() || name.trim().contains(" ")) {
            return null;   // not a resolvable username
        }
        return name.trim();
    }
}
