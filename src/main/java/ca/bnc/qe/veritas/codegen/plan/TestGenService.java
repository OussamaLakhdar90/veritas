package ca.bnc.qe.veritas.codegen.plan;

import java.nio.file.Path;
import java.util.Set;
import ca.bnc.qe.veritas.codegen.CodegenService;
import ca.bnc.qe.veritas.codegen.plan.TestPlanService.RepoRef;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import ca.bnc.qe.veritas.persistence.CodegenRunRepository;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.springframework.stereotype.Service;

/**
 * Git-native generation for the wizard: clone the service repo (read-only) and the output/test repo (a working copy),
 * generate the selected tests INTO the output working copy, build-verify, and return the run — but DO NOT push.
 * Pushing is a separate, explicitly user-triggered step ({@link CodegenService#publish}, gated by an OPEN_PR approval
 * and a git-write-scope check). The output working copy is left in place so that gated publish can push from it; the
 * read-only service clone is cleaned up immediately. Orphaned output clones are swept by the orphan-clone reaper.
 */
@Service
public class TestGenService {

    private final WorkspaceService workspace;
    private final CodegenService codegen;
    private final CodegenRunRepository runs;

    public TestGenService(WorkspaceService workspace, CodegenService codegen, CodegenRunRepository runs) {
        this.workspace = workspace;
        this.codegen = codegen;
        this.runs = runs;
    }

    /**
     * Generate scoped tests for {@code service} into a clone of {@code output}. {@code scope} is the set of
     * {@code "METHOD /path"} signatures the user selected (empty = the whole service). {@code jiraKey} is the work item
     * the tests commit under — required, so the branch/commit/PR reference it and bank commit hooks accept the push;
     * it's recorded on the run for the gated publish step to use. The returned run records the output working copy as
     * its repo, which {@link CodegenService#publish} pushes from later.
     */
    public CodegenRun generate(String serviceName, RepoRef service, RepoRef output, Set<String> scope, String owner,
                               String jiraKey) {
        if (jiraKey == null || jiraKey.isBlank()) {
            throw new IllegalArgumentException("A Jira ticket is required to generate tests — the branch and commit "
                    + "reference it so the PR links to the work item.");
        }
        Path serviceCopy = null;
        try {
            serviceCopy = workspace.resolve(service.appId(), service.repoSlug(), service.branch(), service.localPath());
            // Clone the output/test repo into its own working copy and generate into it. It is intentionally NOT
            // cleaned up here: the gated publish step pushes from this exact directory (run.outputRepo points to it).
            Path outputCopy = workspace.resolve(output.appId(), output.repoSlug(), output.branch(), output.localPath());
            CodegenRun run = codegen.generate(serviceName, serviceCopy, null, outputCopy, owner, scope);
            run.setJiraKey(jiraKey.trim());   // remembered for the gated publish → prefixes the branch/commit/PR title
            return runs.save(run);
        } finally {
            workspace.cleanup(serviceCopy);   // read-only clone — safe to drop now (distinct temp dir from outputCopy)
        }
    }
}
