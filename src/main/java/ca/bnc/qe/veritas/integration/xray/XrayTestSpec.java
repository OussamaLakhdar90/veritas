package ca.bnc.qe.veritas.integration.xray;

import java.util.List;

public record XrayTestSpec(String projectKey, String summary, String testType, List<XrayStep> steps) {}
