package ca.bnc.qe.veritas.codegen.plan;

import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.springframework.stereotype.Service;

/**
 * Produces the test-generation {@link TestPlan} the wizard shows before generating anything: clone the service repo
 * (read-only) and extract its API model, clone the test repo (read-only) and inventory it, then reconcile the two.
 * No LLM, no writes — this is the cheap "what would we do" preflight. When no test repo is supplied the inventory is
 * empty and the plan is a from-scratch one (every endpoint a GAP).
 *
 * <p>Both clones are temp working copies and are cleaned up before returning; a user-supplied local path is left
 * untouched (see {@link WorkspaceService#cleanup}).
 */
@Service
public class TestPlanService {

    private final WorkspaceService workspace;
    private final JavaSpringExtractor extractor;
    private final TestInventoryExtractor inventoryExtractor;
    private final TestReconciler reconciler;

    public TestPlanService(WorkspaceService workspace, JavaSpringExtractor extractor,
                           TestInventoryExtractor inventoryExtractor, TestReconciler reconciler) {
        this.workspace = workspace;
        this.extractor = extractor;
        this.inventoryExtractor = inventoryExtractor;
        this.reconciler = reconciler;
    }

    public TestPlan plan(String serviceName, RepoRef service, RepoRef testRepo) {
        Path serviceCopy = null;
        Path testCopy = null;
        try {
            serviceCopy = workspace.resolve(service.appId(), service.repoSlug(), service.branch(), service.localPath());
            ApiModel api = extractor.extract(serviceCopy);

            TestInventory inventory = TestInventory.empty();
            if (testRepo != null && testRepo.hasSource()) {
                testCopy = workspace.resolve(testRepo.appId(), testRepo.repoSlug(), testRepo.branch(),
                        testRepo.localPath());
                inventory = inventoryExtractor.scan(testCopy);
            }
            return reconciler.reconcile(serviceName, api, inventory);
        } finally {
            workspace.cleanup(serviceCopy);
            workspace.cleanup(testCopy);
        }
    }

    /**
     * Where a repo lives: either a Bitbucket coordinate ({@code appId} + {@code repoSlug} [+ {@code branch}]) or a
     * local {@code localPath} (dev override). {@code hasSource} is false when none is set — used to skip a test repo
     * the user didn't pick (the from-scratch case).
     */
    public record RepoRef(String appId, String repoSlug, String branch, String localPath) {

        public boolean hasSource() {
            return notBlank(localPath) || (notBlank(appId) && notBlank(repoSlug));
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }
}
