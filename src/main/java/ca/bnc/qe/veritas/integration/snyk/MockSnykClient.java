package ca.bnc.qe.veritas.integration.snyk;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic Snyk stand-in for offline dev and demos (active when {@code veritas.snyk.mock=true}). Mirrors the
 * real BNC shape from the export: org {@code app7576}, target {@code application-tests}, a {@code profile-management}
 * project with a mix of fixable and (the common) no-supported-fix vulnerabilities. Never touches the network.
 */
@Component
@ConditionalOnProperty(name = "veritas.snyk.mock", havingValue = "true")
public class MockSnykClient implements SnykClient {

    @Override
    public String whoAmI() {
        return "Mock Snyk User";
    }

    @Override
    public List<SnykOrg> listOrgs() {
        return List.of(
                new SnykOrg("org-7576", "app7576", "APP7576 - CIAM Profile Management"),
                new SnykOrg("org-7571", "app7571", "APP7571 - CIAM Access Management"));
    }

    @Override
    public List<SnykTarget> listTargets(String orgId) {
        return List.of(new SnykTarget("target-apptests", "application-tests"));
    }

    @Override
    public List<SnykProjectRef> listProjects(String orgId, String targetId) {
        return List.of(
                new SnykProjectRef("proj-profile", "application-tests(profile-management/pom.xml)",
                        "profile-management/pom.xml", "develop"),
                new SnykProjectRef("proj-eligibility", "application-tests(ciam-eligibility/pom.xml)",
                        "ciam-eligibility/pom.xml", "develop"));
    }

    @Override
    public List<SnykIssue> aggregatedIssues(String orgId, String projectId) {
        return List.of(
                new SnykIssue("SNYK-JACKSON-DESER", "critical", "Deserialization of Untrusted Data",
                        "com.fasterxml.jackson.core:jackson-databind", "3.1.1", "CVE-2020-9999", "CWE-502",
                        9.2, 298, false, List.of()),
                new SnykIssue("SNYK-COMMONS-LANG3", "high", "Uncontrolled Recursion",
                        "org.apache.commons:commons-lang3", "3.12.0", "CVE-2024-1111", "CWE-674",
                        7.5, 182, true, List.of("3.18.0")),
                new SnykIssue("SNYK-RHINO-XSS", "medium", "Improper Input Validation",
                        "org.mozilla:rhino", "1.7.14", "CVE-2023-2222", "CWE-20",
                        5.3, 80, true, List.of("1.7.15.1")));
    }
}
