package ca.bnc.qe.veritas.integration.snyk;

import java.util.List;

/**
 * Read-only Snyk API access for the dependency-security module. Authenticates with the per-user personal API
 * token / PAT ({@code SNYK_API_TOKEN}, sent as {@code Authorization: token <key>}). All calls are idempotent
 * reads. The hierarchy mirrors the Snyk UI: <b>org (app-id) → target (repo) → project (pom) → issues</b>.
 */
public interface SnykClient {

    /** Cheap authenticated call for the Settings "Test Connection" probe; returns the account name. */
    String whoAmI();

    /** Orgs the token can see — one per BNC app-id (slug e.g. {@code app7576}). */
    List<SnykOrg> listOrgs();

    /** Source repositories (targets) under an org — the repos a user can choose to watch. */
    List<SnykTarget> listTargets(String orgId);

    /** Scanned manifests (projects) under a target, e.g. each {@code <consumer>/pom.xml}. */
    List<SnykProjectRef> listProjects(String orgId, String targetId);

    /** Aggregated vulnerabilities for one project (severity + package + CVE/CVSS + fix info). */
    List<SnykIssue> aggregatedIssues(String orgId, String projectId);
}
