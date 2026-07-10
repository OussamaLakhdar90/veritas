# Classify a finding type

Veritas' contract-validation engine has detected a **new kind of API discrepancy** but does not yet have a
severity for it — it currently ships as `UNSPECIFIED`. Your job is to **suggest a severity** for this finding
type, applying the **same consumer-impact rubric the engine already uses for every other type**, so the
classification stays consistent with the catalog. Your suggestion is **advisory**: a human maintainer reviews it,
can override it, and a reviewed pull request is what actually changes the engine.

You are given two untrusted inputs:

- **FINDING_TYPE** — the name of the new finding type.
- **FIELD_EVIDENCE** — how human reviewers have classified findings of this type so far (severity → vote count),
  across how many distinct services.

## The rubric (severity by CONSUMER IMPACT)

- **BLOCKER** — the spec is invalid/unresolvable, so no generated client can rely on it (e.g. a parse error, an
  unresolved `$ref`).
- **CRITICAL** — a definite endpoint-level consumer break, or a security-contract gap (OWASP API1/2/5) — e.g. a
  missing endpoint, a verb mismatch, a security-scheme mismatch.
- **MAJOR** — request/response-shape functional risk: parameters, status codes, schema fields/types, or
  validation constraints that a running consumer depends on.
- **MINOR** — dead-spec / additive / positional-naming drift that misleads but does **not** break a running
  client (e.g. an extra documented-but-unimplemented endpoint, a path-variable rename, an additive field).
- **INFO** — documentation / advisory only, with no functional risk.

## How to decide

- Anchor on the **rubric first** — reason from what a running consumer of the contract would actually experience.
- Use the **FIELD_EVIDENCE as corroboration, not as the decision**: if reviewers strongly agree and it matches
  the rubric, be confident; if they disagree, pick the severity the rubric best supports and say why.
- **Breaking-ness is decided separately** by the engine (type-derived) — do **not** reason about it here;
  classify severity only.
- Be brief and concrete in the rationale (1–3 sentences), naming the rubric band you chose and the evidence.
