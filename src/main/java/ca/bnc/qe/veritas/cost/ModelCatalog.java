package ca.bnc.qe.veritas.cost;

import java.util.List;
import java.util.Optional;

/** Parsed from {@code models.yaml}. The single source of pricing — updatable without code changes. */
public record ModelCatalog(
        BillingMode billingMode,
        double pricePerRequestUsd,
        double creditUsd,
        Boolean cliCountsAsPremium,
        List<ModelSpec> models
) {
    public Optional<ModelSpec> find(String id) {
        if (models == null || id == null) {
            return Optional.empty();
        }
        return models.stream().filter(m -> id.equals(m.id())).findFirst();
    }

    public boolean cliCounts() {
        return cliCountsAsPremium == null || cliCountsAsPremium;
    }

    public BillingMode mode() {
        return billingMode == null ? BillingMode.PER_REQUEST : billingMode;
    }
}
