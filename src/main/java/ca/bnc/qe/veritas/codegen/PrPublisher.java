package ca.bnc.qe.veritas.codegen;

import java.nio.file.Path;
import ca.bnc.qe.veritas.secret.SecretProvider;
import ca.bnc.qe.veritas.vcs.GitHost;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

/**
 * Publishes generated tests: in the output repo working copy it creates/updates a branch, commits the
 * generated files, pushes with the per-user git token, then opens a pull request via {@link GitHost}.
 * Deterministic (no LLM). Outward action — callers must gate it behind human approval; idempotent re-runs
 * reuse the same branch (force-update) and the host de-dupes the PR.
 */
@Component
@Slf4j
public class PrPublisher {

    private final GitHost gitHost;
    private final SecretProvider secrets;

    public PrPublisher(GitHost gitHost, SecretProvider secrets) {
        this.gitHost = gitHost;
        this.secrets = secrets;
    }

    public PrResult publish(PrRequest req) {
        try (Git git = Git.open(req.workingCopy().toFile())) {
            git.checkout()
                    .setCreateBranch(!branchExists(git, req.branch()))
                    .setName(req.branch())
                    .call();
            git.add().addFilepattern(".").call();
            if (!git.status().call().isClean()) {
                git.commit()
                        .setMessage(req.commitMessage())
                        .setAuthor("Veritas", "veritas@bnc.ca")
                        .setCommitter("Veritas", "veritas@bnc.ca")
                        .call();
            }
            git.push()
                    .setRemote("origin")
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                            secrets.get("GIT_USERNAME").orElse(""), secrets.get("GIT_TOKEN").orElse("")))
                    .add(req.branch())
                    .setForce(true)
                    .call();
            log.info("Pushed branch {} to {}", req.branch(), req.repoSlug());
        } catch (Exception e) {
            throw new IllegalStateException("Push failed for '" + req.repoSlug() + "': " + e.getMessage(), e);
        }
        String prUrl = gitHost.openPullRequest(
                req.repoSlug(), req.branch(), req.targetBranch(), req.title(), req.description());
        return new PrResult(req.branch(), prUrl);
    }

    private boolean branchExists(Git git, String branch) throws Exception {
        for (Ref ref : git.branchList().call()) {
            if (ref.getName().equals("refs/heads/" + branch)) {
                return true;
            }
        }
        return false;
    }

    /** Where to publish from and what PR to open. */
    public record PrRequest(Path workingCopy, String repoSlug, String branch, String targetBranch,
                            String title, String description, String commitMessage) {}

    /** Result of a publish: the pushed branch and the PR's web URL. */
    public record PrResult(String branch, String prUrl) {}
}
