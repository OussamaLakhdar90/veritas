package ca.bnc.qe.veritas.llm;

/**
 * The single seam to the LLM. Swappable: {@code CopilotCliClient} (real) or {@code MockLlmGateway} (local
 * dev / tests). The engine never talks to the LLM except through this interface, which keeps every other
 * component unit-testable without the {@code copilot} binary.
 */
public interface LlmGateway {

    /** True if the underlying engine is reachable/authenticated. */
    boolean isAvailable();

    /** Send a fully-assembled prompt; return the raw textual response (may contain prose + a fenced json block). */
    String complete(String prompt, String model);
}
