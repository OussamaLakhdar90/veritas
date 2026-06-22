package ca.bnc.qe.veritas.settings;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Default single-user principal for the local-first deployment (no login). Active on every profile except
 * {@code server}, where an authenticated request-scoped {@link CurrentUser} takes over.
 */
@Component
@Profile("!server")
public class LocalCurrentUser implements CurrentUser {
    @Override
    public String principalId() {
        return "local";
    }
}
