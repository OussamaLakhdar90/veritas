package ca.bnc.qe.veritas.snyk.fix;

import java.util.List;

/**
 * One repo's pom edit in the release train. Framework steps (BOM/core/api/web) live in the framework Bitbucket
 * project ({@code APP7488}); consumer steps carry the selected {@code appId}. {@code order} is the merge order
 * (upstream before downstream). {@code manual=true} marks a step the planner couldn't resolve — surfaced to the
 * user rather than silently skipped.
 */
public record CascadeStep(
        int order,
        String bitbucketProject,   // APP7488 (framework) or the app-id (consumer)
        String repoSlug,           // lsist-test-framework-bom | ...-core | ...-api | ...-web | application-tests
        String branch,
        String pomPath,            // repo-relative pom path (e.g. "pom.xml" or "profile-management/pom.xml")
        String moduleLabel,        // BOM | core | api | web | consumer:app7576
        List<PomEdit> edits,
        String newModuleVersion,   // the module's new release version (null for a consumer-only bump)
        String diffPreview,        // friendly "was → now" summary of the edits
        boolean manual,
        String reason) {

    public static CascadeStep manual(int order, String project, String repoSlug, String pomPath,
                                      String moduleLabel, String reason) {
        return new CascadeStep(order, project, repoSlug, "", pomPath, moduleLabel, List.of(), null, "", true, reason);
    }
}
