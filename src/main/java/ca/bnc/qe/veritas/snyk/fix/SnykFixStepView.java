package ca.bnc.qe.veritas.snyk.fix;

import java.util.List;

/** Dashboard view of one PR in the release train. */
public record SnykFixStepView(
        int order,
        String moduleLabel,
        String bitbucketProject,
        String repoSlug,
        String branch,
        String pomPath,
        String diffPreview,
        String newModuleVersion,
        String prUrl,
        String prOpenedBy,
        String status,
        boolean manual,
        String reason,
        List<String> reviewers) {
}
