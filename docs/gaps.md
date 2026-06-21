# Gaps & definition-of-done (updated)

Locally-buildable gaps have been closed and tested (mock LLM + stubbed HTTP). What remains needs your BNC
workspace (real creds/egress) or is a deliberate, documented deferral.

## ✅ Closed (built + unit/slice-tested locally)
- **Real gate workflow** — persisted `GateDecision` (audit: action, approver, timestamp), approve/reject
  API. Outward writes gated via `GateService` (`veritas.gate.auto-approve` toggles strict mode):
  create-defect, **Xray test create (`pushToXray`), Xray step update (review apply), release gap-test
  creation** all go through it (server-mode blocking tested).
- **Per-user secret store** — AES-GCM encrypted-file provider (round-trip + wrong-passphrase tested) in the
  chain; **log masking wired into logback** via `logback-spring.xml` + `MaskingMessageConverter` +
  `SecretRegistry` (Bearer/Basic + resolved-secret redaction, converter tested).
- **Per-action cost ledger** — every LLM call across every skill writes a `CostEntry`
  (`CostRecorder`); `GET /api/v1/costs` + `/costs/summary` expose real spend (tested: a real
  `validate-contract` run records a `reconcile` cost row).
- **Vendored real ISTQB prompts + knowledge-pack wired into the real path** — `PromptComposer` builds every
  skill's prompt from the vendored prompt + sliced knowledge pack (`PromptLibrary`/`KnowledgePackSlicer`),
  the ~50% token saver, and fences dynamic inputs as UNTRUSTED data (prompt-injection defense). Tested.
- **Management report evidence** — each finding renders code snippet, **current-YAML fragment**
  (`SpecFragmentExtractor`), proposed fix, and ISTQB citation in HTML + PDF (tested; fixed a latent
  non-XHTML `<br>` that had been silently failing PDF export whenever an explanation was present).
- **AST meta-annotation flattening** — composed annotations (custom `@ApiGet` → `@GetMapping`) resolved
  (tested) so they aren't false "missing endpoints".
- **Codegen build-verify** — runs the template-derived build command (PASS/FAIL/SKIPPED, tested).
- **Coverage depth** — orphan/unmatched detection + **RTM HTML report** (tested).
- **Resilience** — `RetryTemplate`-backed `Retries` (exponential backoff, retry-on RestClientException/IO),
  applied to the Xray client, tested.
- **Dashboard actions** — create-defect button, trigger-scan from the repo picker, report links (built via npm).
- **PDF report export** (openhtmltopdf) + quiet CLILogging.

## 🔴 Still needs your BNC workspace (live creds/egress — cannot be done here)
- **Real Copilot CLI run** — all LLM output is still mock locally; run one `validate-contract` on a real
  service to prove output quality + confirm CLI flags.
- **Live Atlassian/Bitbucket** — discovery/clone/PR, Jira create/search, Confluence fetch, Xray GraphQL are
  built + builder-tested but never hit a real tenant.
- **Git push + PR for codegen** — `implement-tests` writes + build-verifies files; the branch/commit/push/PR
  step needs Bitbucket creds.

## 🟠 Deliberately scoped-down (documented; to deepen against a real service to avoid false positives)
- **AST depth beyond meta-annotations** — `SymbolSolver` type-resolution, `@ControllerAdvice` error-code
  diffing, and security (`@PreAuthorize`) modeling. These risk false positives without a real codebase to
  tune against, so they're intentionally not auto-flagged yet.
- **L5/L6 + corrected YAML** are real only with Copilot (mock returns placeholders).
- **Coverage tiers** — Tier-1 Xray requirement-coverage links and Tier-3 LLM fuzzy matching, plus
  risk/level/type dimensions (today: deterministic title match + orphan detection).
- **Xray `updateTestSteps`** is additive (full replace is a live enhancement).
- **SSO / per-user dashboards / OS-keychain + Vault** secret providers (encrypted-file + env exist).
- **Postgres profile, Docker, CI, integration tests (WireMock), dashboard e2e.**

## ⏸ Deferred by choice
- **Flyway migrations** — local uses Hibernate `ddl-auto`; Flyway is the Postgres/server-promotion step.
