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
        /** A browsable Bitbucket link to the pushed branch, so a held (pushed-but-no-PR) step is verifiable. */
        String branchUrl,
        String status,
        /** The live line while this module is active (e.g. "Pushing core…"); null once the step is terminal. */
        String stageDetail,
        boolean manual,
        String reason,
        List<String> reviewers) {
}
