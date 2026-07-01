package ca.bnc.qe.veritas.secret;

import java.util.List;

/** The secret keys Veritas understands — single source for the Settings UI, write validation, and ConfigDoctor. */
public final class KnownSecretKeys {

    public static final List<String> ALL = List.of(
            "GIT_TOKEN", "GIT_USERNAME",
            "JIRA_API_TOKEN", "JIRA_USERNAME",
            "CONFLUENCE_API_TOKEN",
            "XRAY_API_TOKEN", "XRAY_CLIENT_ID", "XRAY_CLIENT_SECRET",
            "SNYK_API_TOKEN");

    private KnownSecretKeys() {
    }

    public static boolean isKnown(String key) {
        return key != null && ALL.contains(key);
    }
}
