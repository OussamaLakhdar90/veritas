package ca.bnc.qe.veritas.cost;

/** How Copilot bills. Switchable by config because GitHub is mid-transition (token credits from 2026-06). */
public enum BillingMode {
    PER_REQUEST,
    USAGE_CREDITS
}
