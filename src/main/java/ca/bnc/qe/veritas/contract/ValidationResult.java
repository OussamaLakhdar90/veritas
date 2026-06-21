package ca.bnc.qe.veritas.contract;

import java.util.Map;

public record ValidationResult(
        String scanId,
        String status,
        int totalFindings,
        Map<String, Long> bySeverity,
        String reportPath,
        String reportPdfPath,
        String correctedYamlPath,
        double estCostUsd
) {}
