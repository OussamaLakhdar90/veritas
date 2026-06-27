package ca.bnc.qe.veritas.codegen.plan;

import ca.bnc.qe.veritas.engine.model.HttpMethod;

/**
 * One HTTP endpoint a pre-existing test project appears to exercise — a path literal found in a test source file,
 * with the HTTP verb detected near it (null when no verb could be inferred). Best-effort static signal: it tells the
 * reconciler "this path is referenced by a test", not "this path is correctly tested". Path matching downstream is
 * heuristic, so the plan is always presented for human review before anything is generated.
 *
 * @param method the HTTP verb inferred for this reference, or {@code null} when none was found nearby
 * @param path   the raw path literal as written in the test (e.g. {@code /policies} or {@code /policies/})
 * @param sourceFile the test file the reference was found in, relative to the test project root
 */
public record TestReference(HttpMethod method, String path, String sourceFile) {}
