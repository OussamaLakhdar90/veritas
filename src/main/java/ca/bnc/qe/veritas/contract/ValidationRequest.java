package ca.bnc.qe.veritas.contract;

import java.nio.file.Path;
import java.util.List;

public record ValidationRequest(
        String serviceName,
        String appId,
        String repoSlug,
        String gitRef,
        Path repoPath,
        List<SpecInput> specs,
        boolean llmEnabled,
        String owner,
        Thoroughness thoroughness   // per-scan cost/rigour for the reconcile step; never null (defaults to STANDARD)
) {
    public ValidationRequest {
        thoroughness = thoroughness == null ? Thoroughness.STANDARD : thoroughness;
    }

    /** Back-compat constructor — thoroughness defaults to {@link Thoroughness#STANDARD}. */
    public ValidationRequest(String serviceName, String appId, String repoSlug, String gitRef, Path repoPath,
                             List<SpecInput> specs, boolean llmEnabled, String owner) {
        this(serviceName, appId, repoSlug, gitRef, repoPath, specs, llmEnabled, owner, Thoroughness.STANDARD);
    }
}
