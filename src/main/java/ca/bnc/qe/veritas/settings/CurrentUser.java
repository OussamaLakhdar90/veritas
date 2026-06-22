package ca.bnc.qe.veritas.settings;

/**
 * The principal whose settings/secrets a request operates on. Local-first: {@link LocalCurrentUser} returns a
 * fixed {@code "local"} id. On EC2 (multi-user) a request-scoped implementation derived from the authenticated
 * session replaces it (marked @Primary / profile-gated), and persisted secrets/connections become per-principal
 * — without touching any call site.
 */
public interface CurrentUser {
    String principalId();
}
