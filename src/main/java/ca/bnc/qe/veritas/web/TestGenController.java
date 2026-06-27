package ca.bnc.qe.veritas.web;

import ca.bnc.qe.veritas.codegen.plan.TestPlan;
import ca.bnc.qe.veritas.codegen.plan.TestPlanService;
import ca.bnc.qe.veritas.codegen.plan.TestPlanService.RepoRef;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-generation preflight: given a service repo and (optionally) an existing test repo, return the reconciliation
 * plan (GAP / CURRENT / ORPHAN) WITHOUT generating or writing anything — the "what would we do" step the wizard shows
 * before the user commits. Separate from {@link TestPlanController} (release test plans / RTM); this is about
 * generating automated test code. Both repos usually share one Bitbucket {@code appId}; {@code *RepoPath} fields are
 * local-dev overrides.
 */
@RestController
@RequestMapping("/api/v1")
public class TestGenController {

    private final TestPlanService service;

    public TestGenController(TestPlanService service) {
        this.service = service;
    }

    @PostMapping("/services/{service}/test-gen/plan")
    public TestPlan plan(@PathVariable String service, @RequestBody PlanRequest req) {
        return this.service.plan(service,
                new RepoRef(req.appId(), req.serviceRepoSlug(), req.serviceBranch(), req.serviceRepoPath()),
                new RepoRef(req.appId(), req.testRepoSlug(), req.testBranch(), req.testRepoPath()));
    }

    /**
     * @param appId           Bitbucket app the repos live under (shared by both; ignored when local paths are used)
     * @param serviceRepoSlug the service-under-test repo
     * @param serviceBranch   branch to read the service from (null = default branch)
     * @param serviceRepoPath local path override for the service repo (dev)
     * @param testRepoSlug    the existing test repo; blank = from-scratch (no inventory)
     * @param testBranch      branch to read the tests from (null = default branch)
     * @param testRepoPath    local path override for the test repo (dev)
     */
    public record PlanRequest(String appId, String serviceRepoSlug, String serviceBranch, String serviceRepoPath,
                              String testRepoSlug, String testBranch, String testRepoPath) {}
}
