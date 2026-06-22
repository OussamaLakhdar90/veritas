package ca.bnc.qe.veritas.settings;

/** Connection settings for all four integrations (no secrets). */
public record ConnectionsView(EndpointView bitbucket, EndpointView jira, EndpointView confluence, EndpointView xray) {
}
