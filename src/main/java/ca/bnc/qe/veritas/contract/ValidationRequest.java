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
        String owner
) {}
