package ca.bnc.qe.veritas.settings;

import java.util.List;

/**
 * Result of saving connection settings. {@code restartRequiredFields} lists fields (e.g. {@code jira.edition})
 * that were persisted but need a restart to take effect, so the UI can prompt the user.
 */
public record UpdateConnectionsResponse(boolean applied, List<String> restartRequiredFields) {
}
