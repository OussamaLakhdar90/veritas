package ca.bnc.qe.veritas.integration.jira;

/**
 * An available workflow transition for an issue: the id to POST, the transition's display name, and the destination
 * status name ({@code to.name}). Matching on the destination status is more reliable than the transition label.
 */
public record JiraTransition(String id, String name, String toStatus) {

    /** Back-compat: a transition with no known destination status. */
    public JiraTransition(String id, String name) {
        this(id, name, null);
    }
}
