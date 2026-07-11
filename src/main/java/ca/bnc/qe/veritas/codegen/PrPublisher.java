package ca.bnc.qe.veritas.codegen;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.secret.SecretProvider;
import ca.bnc.qe.veritas.vcs.GitHost;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

/**
 * Publishes generated tests: in the output repo working copy it creates/updates a branch, commits the
 * generated files, pushes with the per-user git token, then opens a pull request via {@link GitHost}.
 * Deterministic (no LLM). Outward action — callers must gate it behind human approval. A brand-new branch is
 * force-created; an EXISTING shared branch (a second Snyk fix for the same Jira key + project) is fetched and
 * merged so the new fix accumulates onto it instead of overwriting it. The host de-dupes the PR.
 */
@Component
@Slf4j
public class PrPublisher {

    private final GitHost gitHost;
    private final SecretProvider secrets;
    private final ConnectionsProperties connections;

    /** Per-(repoSlug/branch) monitors so concurrent bulk pushes to the SAME shared branch serialize instead of racing
     *  the fetch→merge→push into a lost-commit clobber. Keyed by "{repoSlug}/{branch}"; the set of distinct fix
     *  branches over a server's life is small, so the map is effectively bounded. */
    private final ConcurrentHashMap<String, Object> branchLocks = new ConcurrentHashMap<>();

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
        // Bulk safety: several bulk trains (running on the fix pool) resolve to the SAME (repoSlug, branch) and push
        // concurrently. Serialize the whole fetch→merge→push per branch so two threads can't both see the branch as
        // new, both force-push, and clobber each other's commit (a TOCTOU race). Serialized, each push sees the prior's
        // remote commit and ACCUMULATES onto it (see incorporateExistingRemoteBranch).
        synchronized (branchLocks.computeIfAbsent(repoSlug + "/" + branch, k -> new Object())) {
            pushBranchLocked(workingCopy, repoSlug, branch, commitMessage);
        }
    }

    private void pushBranchLocked(Path workingCopy, String repoSlug, String branch, String commitMessage) {
        try (Git git = Git.open(workingCopy.toFile())) {
            configureCommitIdentity(git);   // deterministic author/committer for the fix commit AND any merge commit
            git.checkout().setCreateBranch(!branchExists(git, branch)).setName(branch).call();
            git.add().addFilepattern(".").call();
            if (!git.status().call().isClean()) {
                git.commit().setMessage(commitMessage)
                        .setAuthor("Veritas", "veritas@bnc.ca").setCommitter("Veritas", "veritas@bnc.ca").call();
            }
            // The branch is per-(Jira key, Bitbucket project); each train clones fresh from develop, so a blind
            // force-push would ERASE the earlier train's commit. Pull the existing remote branch in first (merge) and
            // only force-push when the branch is genuinely new — so a second fix ACCUMULATES onto the first.
            boolean accumulated = incorporateExistingRemoteBranch(git, branch, repoSlug);
            PushCommand push = git.push().setRemote("origin").add(branch).setForce(!accumulated);
            applyGitAuth(push);   // Bearer for Server/DC PAT (same as the clone) — Basic-with-empty-username is rejected
            verifyPushAccepted(push.call(), branch, repoSlug);   // JGit doesn't THROW on a rejected ref — inspect the result
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

    /**
     * JGit's {@code push.call()} does NOT throw when the remote rejects a ref (branch-permission / branching-model hook,
     * non-fast-forward, …) — it reports the per-ref outcome in the result. Without this check a rejected push is logged
     * as "pushed" while Bitbucket created nothing. Throw with the remote's own reason so the failure is real + diagnosable.
     */
    static void verifyPushAccepted(Iterable<PushResult> results, String branch, String repoSlug) {
        for (PushResult r : results) {
            for (RemoteRefUpdate u : r.getRemoteUpdates()) {
                RemoteRefUpdate.Status st = u.getStatus();
                if (st != RemoteRefUpdate.Status.OK && st != RemoteRefUpdate.Status.UP_TO_DATE) {
                    String why = u.getMessage() != null && !u.getMessage().isBlank() ? " — " + u.getMessage() : "";
                    throw new IllegalStateException("Bitbucket rejected branch '" + branch + "' on " + repoSlug + " ("
                            + st + why + "). Check the branching model / branch permissions allow creating this branch.");
                }
            }
        }
    }

    /**
     * If the shared branch already exists on the remote (a prior fix train for the same Jira key + Bitbucket project
     * pushed it), fetch it and MERGE it into the just-committed local branch so the second fix ADDS to the first
     * instead of clobbering it. Returns {@code true} when an existing remote branch was incorporated (so the caller
     * pushes WITHOUT force), {@code false} when the branch is new (a force-create is safe — nothing to preserve).
     * Throws on a genuine content conflict between the two fixes rather than silently dropping the earlier one.
     */
    private boolean incorporateExistingRemoteBranch(Git git, String branch, String repoSlug) throws Exception {
        LsRemoteCommand ls = git.lsRemote().setRemote("origin").setHeads(true);
        applyGitAuth(ls);
        boolean remoteHasBranch = ls.call().stream()
                .anyMatch(r -> r.getName().equals("refs/heads/" + branch));
        if (!remoteHasBranch) {
            return false;
        }
        String remoteRef = "refs/remotes/origin/" + branch;
        FetchCommand fetch = git.fetch().setRemote("origin")
                .setRefSpecs(new RefSpec("refs/heads/" + branch + ":" + remoteRef));
        applyGitAuth(fetch);
        fetch.call();
        ObjectId remoteTip = git.getRepository().resolve(remoteRef);
        if (remoteTip == null) {
            return false;
        }
        MergeResult merge = git.merge().include(remoteTip)
                .setMessage("Merge existing " + branch + " — accumulate the earlier Snyk fix").call();
        if (!merge.getMergeStatus().isSuccessful()) {
            throw new IllegalStateException("Can't accumulate onto the existing branch '" + branch + "' on " + repoSlug
                    + " — merge conflict (" + merge.getMergeStatus() + "). Resolve the shared branch manually so the "
                    + "earlier fix isn't overwritten.");
        }
        return true;
    }

    /** Pin a deterministic identity for the fix commit and any accumulation merge commit — never rely on ambient
     *  git config (which may be absent on a CI/headless worker and would fail the merge commit). */
    private void configureCommitIdentity(Git git) throws Exception {
        StoredConfig cfg = git.getRepository().getConfig();
        cfg.setString("user", null, "name", "Veritas");
        cfg.setString("user", null, "email", "veritas@bnc.ca");
        cfg.save();
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
