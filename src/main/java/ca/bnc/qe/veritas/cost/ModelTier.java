package ca.bnc.qe.veritas.cost;

/** Capability/cost tier. A skill step declares a tier; the {@code ModelSelector} resolves it to a model. */
public enum ModelTier {
    ECONOMY,
    STANDARD,
    DEEP,
    FRONTIER
}
