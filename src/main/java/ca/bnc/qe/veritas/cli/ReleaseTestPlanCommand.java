package ca.bnc.qe.veritas.cli;

import java.util.concurrent.Callable;
import ca.bnc.qe.veritas.testmgmt.ReleaseTestPlanService;
import ca.bnc.qe.veritas.testmgmt.ReleaseTestPlanService.CoverageSummary;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Generate a release test plan and reconcile coverage against existing Xray tests. */
@Component
@Command(name = "release-test-plan",
        description = "Generate a release test plan (ISTQB Test Manager) + reconcile coverage against Xray.")
public class ReleaseTestPlanCommand implements Callable<Integer> {

    private final ReleaseTestPlanService service;

    @Option(names = {"-n", "--name"}, required = true, description = "Service name.")
    private String name;

    @Option(names = "--fix-version", required = true, description = "Jira fixVersion / release name.")
    private String fixVersion;

    @Option(names = "--issues-jql", description = "Override JQL for release issues (default: fixVersion = \"...\").")
    private String issuesJql;

    @Option(names = "--tests-jql", description = "JQL selecting existing Xray tests (default: project = <project>).")
    private String testsJql;

    @Option(names = "--project", description = "Xray/Jira project key (for test discovery and gap creation).")
    private String project;

    @Option(names = "--create", description = "Create gap test cases in Xray (gated outward action).")
    private boolean create;

    public ReleaseTestPlanCommand(ReleaseTestPlanService service) {
        this.service = service;
    }

    @Override
    public Integer call() {
        CoverageSummary s = service.generate(name, fixVersion, issuesJql, testsJql, project, create, "local");
        System.out.println("Plan " + s.planId() + " for " + name + " @ " + fixVersion);
        System.out.println("Coverage: " + s.matched() + " matched, " + s.gaps() + " gaps"
                + (create ? ", " + s.created() + " created" : "") + ", " + s.orphans() + " orphan/unmatched"
                + " of " + s.total() + " required cases");
        if (s.reportPath() != null) {
            System.out.println("RTM report: " + s.reportPath());
        }
        System.out.printf("Est. LLM cost: $%.4f%n", s.estCostUsd());
        return 0;
    }
}
