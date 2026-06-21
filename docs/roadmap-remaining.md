# Veritas — Remaining Work Plan (all pillars)

> **STATUS (2026-06-21): A ✅ · B (B1–B6) ✅ · C (C1–C4) ✅, C5 deferred (cosmetic) · D ✅ · E token-scope ✅,
> Flyway deferred · F = live-BNC only. 129 tests green; fat jar (82.7 MB) + dashboard packaged.**
> Flyway deferral rationale: app uses Hibernate `ddl-auto=update` (SQLite local + Postgres server); a
> hand-maintained dual-dialect baseline for 15+ still-evolving entities is high-churn/high-risk and would
> likely break the @SpringBootTest contexts. Migrate to Flyway + `ddl-auto=validate` once the schema settles.

Baseline: 113 tests green; all three pillars run end-to-end (mock LLM + WireMock'd integrations). This plan
covers everything still missing vs. the original plan + the two verified audits (Tier-1–4 gap audit, and the
72-agent consistency review). Each workstream lists **goal → tasks (files) → tests → effort/risk → needs-BNC?**

Legend: 🟢 buildable locally · 🔵 needs BNC creds/egress · ⚙️ effort S/M/L.

Recommended order: **A → C → B → D → E**, with **F** running continuously in the BNC workspace.

---

## Workstream A — Pillar A: deepen the contract engine ⚙️L 🟢
**Goal:** `validate-contract` truly proves spec↔code fidelity at L1–L6 (today it's shallower than the plan).

- **A1 — SymbolSolver type resolution** (`engine/extract/java/JavaSpringExtractor`): configure a
  `CombinedTypeSolver` (`ReflectionTypeSolver` + `JavaParserTypeSolver` over the cloned `src/`); resolve DTO
  field types, generics, `ResponseEntity<T>` payloads. Never hard-fail — syntactic fallback + record a blind
  spot on unresolved symbols.
- **A2 — `@ControllerAdvice` / `@ExceptionHandler`** → error-response models (status + body schema) into
  `ApiModel`; diff against the spec's declared error responses.
- **A3 — Security extraction**: `@PreAuthorize`/`@Secured`/`@RolesAllowed` + `HttpSecurity` → required
  scopes/roles in a `SecuritySchemeModel`; emit `SECURITY_MISMATCH` when the spec's security differs.
- **A4 — Finish the declared-but-unemitted diff taxonomy** (`engine/diff/DiffEngine`):
  `RESPONSE_SCHEMA_MISMATCH` (compare response body fields), `STATUS_CODE_EXTRA`, deep `CONSTRAINT_GAP`
  (numeric min/max, enum, pattern *value* mismatches, not just presence), `CONSUMES_PRODUCES_MISMATCH`.
- **A5 — L5/L6 LLM boundary**: a reasoning pass over the IR + load-bearing snippets producing design-quality
  (L5) findings + the weak-test-basis call-out (L6); wire into `ContractValidationService` behind `llmEnabled`,
  cost-recorded, schema-validated.
- **A6 — Corrected-YAML**: deterministic merge (code wins, preserve `x-*`) → LLM reconcile → **round-trip
  re-validate** with deterministic fallback on failure.

**Tests:** expand `fixtures/policies` with advice/security/constraint cases; per-finding-type assertions in
`DiffEngineTest`; a SymbolSolver-resolution test; corrected-YAML round-trip test. **Needs-BNC:** no.

---

## Workstream B — Pillar B: release test-management plumbing ⚙️M-L 🟢
**Goal:** a real release test plan — resolve the release, reconcile coverage, create + attach tests idempotently.

- **B1 — Jira version APIs** on `JiraClient` (`getVersion`, `listVersions`) for Server v2 + Cloud v3; a
  `resolveRelease(link|fixVersion)` → `(projectKey, versionId, fixVersion)` handling released/unreleased/
  archived/same-name-across-projects.
- **B2 — `classifyIssues`**: testable vs non-testable (infra/docs/spike) with a recorded reason (no silent drop).
- **B3 — Paged release-issue fetch** (JQL pagination; today single-page).
- **B4 — Idempotent + dedup `createApprovedTests`**: `dedup_fingerprint`, `SKIPPED_DUP`, partial-failure-safe
  per-case results; re-run creates nothing new.
- **B5 — Attach-to-plan**: add `addTestsToTestPlan` to the `XrayClient` interface; Raven impl
  (`/rest/raven/1.0/api/testplan/{key}/test`) + Cloud GraphQL; create the Test Plan if missing; link to release.
- **B6 — Multi-dimensional RTM**: populate `CoverageItem` for RISK/LEVEL/TYPE/TECHNIQUE (today REQUIREMENT only);
  enforce "HIGH/VERY-HIGH risk ⇒ ≥2 tests".

**Tests:** WireMock for version APIs + attach-to-plan; coverage multi-dimension; idempotent re-run. **Needs-BNC:** no.

---

## Workstream C — "Revolutionary" rollout: structured, self-verifying deliverables ⚙️L 🟢
**Goal:** apply the test-plan showcase standard (structured JSON + risk/RTM/exit + **self-review confidence +
blind-spots** + dashboard view) to the remaining skills. Pattern per skill: rewrite prompt (KNOWLEDGE-PACK
marker **at the bottom**), new JSON schema, matching `MockLlmGateway` reply, service parse+persist
(`deliverableJson`), dashboard render, tests.

- **C1 — test-strategy** → strategy deliverable (scope, risk register, test approach, levels/types, entry/exit,
  estimation, self-review).
- **C2 — create-test-cases** → cases with technique justification + coverage-item traceability + per-case
  self-review.
- **C3 — review-test-cases** → ISTQB C1–C6 scored rubric + corrected steps + confidence (structured `ReviewResult`).
- **C4 — contract report** → add self-review/confidence + a structured deliverable view (findings already carry
  evidence).
- **C5 — Split `generate-test-artifacts`** into per-skill prompts (strategy vs cases) to remove the conflicting
  shared output contract.

**Tests:** per skill, mirror `ReleaseTestPlanServiceTest`/`TestPlanDetail` assertions. **Needs-BNC:** no.

---

## Workstream D — Cleanups & coherence (from the consistency review) ⚙️M 🟢
- **D1 — Skill framework decision**: the manifest/`SkillRunner` path is echo-only dead infra. Either migrate ≥1
  real skill onto it, or **delete** the subtree (+ `/runs`, `SkillRun`/`RunStep`, `PromptAssembler`) for
  coherence. (Recommend delete unless it earns its keep.)
- **D2 — Honor or remove unused `auth-type` knobs** (Confluence Bearer branch; Bitbucket/Xray) so config can't
  imply unsupported modes.
- **D3 — Dashboard/API completeness**: `GET /api/v1/defects` (DefectLink read-API) + a **Defects** page; a
  **Gates** page (list PENDING, approve/reject); server-mode **live-multiplier refresh** (`@Scheduled` or
  post-auth).
- **D4 — Remove dead code** (`CostResult.zero`, `LiveModelMultipliers.isPremium` or consume it, unused
  `XrayCloudClient` methods); route `JiraServerClient.attachFile` through `CorpHttp`; configurable `out/` dir;
  refresh README + prompts/README.
- **D5 — Web tests** (`@WebMvcTest` slices for controllers; status codes + JSON shapes) + a no-token-leak
  end-to-end log test.

**Needs-BNC:** no.

---

## Workstream E — Enterprise & security ⚙️L 🔵(partly)
- **E1 — Gate-approver authn**: derive approver from the authenticated principal (not the request body);
  protect `/api/v1/gates/**`; per-user dashboards/scoping.
- **E2 — SSO (OIDC)** + bind beyond `127.0.0.1`; add **OS-keychain** and **Vault** providers to the
  `ChainedSecretProvider` chain.
- **E3 — Token-scope pre-flight**: verify git/Jira/Xray token scopes before any outward write (extends
  `ConfigDoctor`/`Preflight`).
- **E4 — Flyway migrations** (replace `ddl-auto=update`) for a controlled, audited bank schema.

**Needs-BNC:** SSO/keychain/Vault need BNC infra; Flyway + token-scope checks are buildable locally.

---

## Workstream F — Live validation (BNC workspace only) 🔵
Cannot be done on the dev box; the code paths exist and are WireMock/mock-tested.
- **F1 — Copilot**: `veritas copilot-login` (device flow) + one real `validate-contract` run; confirm output
  quality and the exact `gh.ts` `chat/completions` body — switch `CopilotHttpGateway` to streaming only if
  required (non-streaming works today).
- **F2 — Atlassian/Bitbucket/Xray** against the real tenants (create/fetch/clone), confirming editions + token
  scopes.
- **F3 — Git push + PR** end-to-end (PrPublisher against `git.bnc`).
- **F4 — Confluence Server/DC** client variant if BNC Confluence is DC (today Cloud-only).

---

## Sequencing & dependencies
1. **A** (core promise; unblocks richer evidence for C4 and codegen). 
2. **C** (visible "wow"; reuses A's evidence). 
3. **B** (release functionality; independent of A/C). 
4. **D** (cleanup once features stabilize). 
5. **E** (hardening for production). 
6. **F** continuous in BNC.

Effort rough order: A ≈ C ≈ E (L) > B (M-L) > D (M). A, B, C, D and most of E are fully buildable + testable
here; only SSO/keychain/Vault and all of F need the BNC environment.
