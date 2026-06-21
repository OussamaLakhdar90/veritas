package ca.bnc.qe.veritas.integration.jira;

import java.util.List;

/**
 * Result of Jira's create-meta discovery: which fields are allowed on the Create screen for a
 * project+issuetype, plus the auto-discovered custom-field keys (Epic Link, Team) — mirrors the BNC
 * contract-validator's field-discovery so we never submit a field the screen rejects.
 */
public record CreateMeta(List<String> allowedFields, String epicLinkFieldKey, String teamFieldKey) {
    public static CreateMeta empty() {
        return new CreateMeta(List.of(), null, null);
    }
}
