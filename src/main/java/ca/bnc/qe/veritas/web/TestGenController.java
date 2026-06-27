package ca.bnc.qe.veritas.web;

import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.codegen.plan.TestGenService;
import ca.bnc.qe.veritas.codegen.plan.TestPlan;
import ca.bnc.qe.veritas.codegen.plan.TestPlanService;
import ca.bnc.qe.veritas.codegen.plan.TestPlanService.RepoRef;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    private final TestGenService testGen;

    public TestGenController(TestPlanService service, TestGenService testGen) {
        this.service = service;
        this.testGen = testGen;
    }

    @PostMapping("/services/{service}/test-gen/plan")
    public TestPlan plan(@PathVariable String service, @RequestBody PlanRequest req) {
        return this.service.plan(service,
                new RepoRef(req.appId(), req.serviceRepoSlug(), req.serviceBranch(), req.serviceRepoPath()),
                new RepoRef(req.appId(), req.testRepoSlug(), req.testBranch(), req.testRepoPath()));
    }

    /**
     * Generate the selected tests into a clone of the output repo and build-verify — returns the run (202). This does
     * NOT push: opening the PR is the separate, explicitly user-triggered {@code /codegen-runs/{id}/publish} step
     * (gated by an OPEN_PR approval + a git-write-scope check). {@code endpoints} scopes generation; empty = whole
     * service. {@code outputRepoSlug} is where the tests are written and the PR will later be opened.
     */
    @PostMapping("/services/{service}/test-gen/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CodegenRun generate(@PathVariable String service, @RequestBody GenerateRequest req) {
        Set<String> scope = req.endpoints() == null ? Set.of() : new java.util.LinkedHashSet<>(req.endpoints());
        return testGen.generate(service,
                new RepoRef(req.appId(), req.serviceRepoSlug(), req.serviceBranch(), req.serviceRepoPath()),
                new RepoRef(req.appId(), req.outputRepoSlug(), req.outputBranch(), req.outputRepoPath()),
                scope, req.owner() == null ? "api" : req.owner(), req.jiraKey());
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

    /**
     * @param outputRepoSlug where the generated tests are written and the PR will later be opened (required)
     * @param outputBranch   base branch in the output repo (null = default)
     * @param outputRepoPath local path override for the output repo (dev)
     * @param endpoints      selected {@code "METHOD /path"} signatures to generate (empty = whole service)
     * @param jiraKey        the work item the tests commit under (required) — prefixes the branch/commit/PR title
     */
    public record GenerateRequest(String appId, String serviceRepoSlug, String serviceBranch, String serviceRepoPath,
                                  String outputRepoSlug, String outputBranch, String outputRepoPath,
                                  List<String> endpoints, String owner, String jiraKey) {}
}
