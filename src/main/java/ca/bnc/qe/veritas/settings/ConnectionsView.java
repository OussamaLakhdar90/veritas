package ca.bnc.qe.veritas.settings;

/** Connection settings for every integration (no secrets). */
public record ConnectionsView(EndpointView bitbucket, EndpointView jira, EndpointView confluence,
                              EndpointView xray, EndpointView snyk) {
}
