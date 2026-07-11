package ca.bnc.qe.veritas.codegen;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.secret.SecretProvider;
import ca.bnc.qe.veritas.vcs.GitHost;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.TransportHttp;
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
    private final ConnectionsProperties connections;

    public PrPublisher(GitHost gitHost, SecretProvider secrets, ConnectionsProperties connections) {
        this.gitHost = gitHost;
        this.secrets = secrets;
        this.connections = connections;
    }

    /**
     * Authenticate a JGit transport (push/clone) the SAME way the clone does — otherwise a clone can succeed while
     * the push fails "not authorized". A Bitbucket Server/DC HTTP access token (PAT, the default) authenticates as a
     * {@code Bearer} header; Basic with an EMPTY {@code GIT_USERNAME} is exactly what Server rejects. Basic
     * (username + token) is used for CLOUD and when auth is explicitly configured BASIC.
     */
    private void applyGitAuth(TransportCommand<?, ?> cmd) {
        ConnectionsProperties.Endpoint bb = connections.getBitbucket();
        String token = secrets.get("GIT_TOKEN").orElse("");
        boolean bearer = "SERVER_DC".equalsIgnoreCase(bb.getEdition())
                && !"BASIC".equalsIgnoreCase(bb.getAuthType());
        if (bearer) {
            cmd.setTransportConfigCallback(transport -> {
                if (transport instanceof TransportHttp http) {
                    http.setAdditionalHeaders(Map.of("Authorization", "Bearer " + token));
                }
            });
        } else {
            cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                    secrets.get("GIT_USERNAME").orElse(""), token));
        }
    }

    /**
     * Branch + commit + push a working copy WITHOUT opening a PR. Used by the Snyk fix cascade, which must push the
     * version-bump branch even when a breaking change is flagged (the PR is then opened later, by the user or Veritas).
     */
    public void pushBranch(Path workingCopy, String repoSlug, String branch, String commitMessage) {
        try (Git git = Git.open(workingCopy.toFile())) {
            git.checkout().setCreateBranch(!branchExists(git, branch)).setName(branch).call();
            git.add().addFilepattern(".").call();
            if (!git.status().call().isClean()) {
                git.commit().setMessage(commitMessage)
                        .setAuthor("Veritas", "veritas@bnc.ca").setCommitter("Veritas", "veritas@bnc.ca").call();
            }
            PushCommand push = git.push().setRemote("origin").add(branch).setForce(true);
            applyGitAuth(push);   // Bearer for Server/DC PAT (same as the clone) — Basic-with-empty-username is rejected
            push.call();
            log.info("Pushed branch {} to {}", branch, repoSlug);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            String low = msg.toLowerCase(Locale.ROOT);
            String hint = (low.contains("not authorized") || low.contains("401") || low.contains("403"))
                    ? " — the credentials were accepted for read but the push was refused; verify GIT_TOKEN has "
                            + "repository WRITE scope (a read-only token can clone but not push)."
                    : "";
            throw new IllegalStateException("Push failed for '" + repoSlug + "': " + msg + hint, e);
        }
    }

    public PrResult publish(PrRequest req) {
        pushBranch(req.workingCopy(), req.repoSlug(), req.branch(), req.commitMessage());
        String prUrl;
        try {
            prUrl = gitHost.openPullRequest(
                    req.repoSlug(), req.branch(), req.targetBranch(), req.title(), req.description());
        } catch (RuntimeException e) {
            // The branch is already pushed; only PR creation failed. Return the branch (prUrl=null) as a breadcrumb
            // so the caller persists it instead of losing the pushed work — the user can retry the PR (the branch is
            // reused, so it's idempotent).
            log.warn("Branch {} pushed to {}, but opening the PR failed: {} — the branch is up; retry to open the PR.",
                    req.branch(), req.repoSlug(), e.getMessage());
            return new PrResult(req.branch(), null);
        }
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
