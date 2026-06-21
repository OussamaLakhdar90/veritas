package ca.bnc.qe.veritas.integration.xray;

import java.util.List;

public record XrayTest(String key, String issueId, String summary, String testType, List<XrayStep> steps) {}
