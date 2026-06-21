# Open questions & risk register

Living list of blind spots and decisions. Severity: 🔴 blocker · 🟠 high · 🟡 medium. Status updated as
they're resolved.

## A. Blockers / critical

| # | Risk / question | Impact | Mitigation / next step | Sev | Status |
|---|---|---|---|---|---|
| A1 | **Approval to send BNC source code + contracts + Jira/Confluence content to GitHub Copilot** | Gates the whole product | Confirmed: approved (data-handling sign-off) | 🔴 | RESOLVED |
| A2 | **Real `copilot` CLI never exercised** — flags, non-interactive behavior, output shape | Core dependency unverified | Early spike on a real seat: confirm `-p/-s/--model/--deny-tool/--max-autopilot-continues`, capture a real reply | 🔴 | OPEN |
| A3 | **Network egress** from the run host to Copilot + Atlassian (Jira/Confluence) + Bitbucket | Tool can't reach its dependencies | Confirmed: egress OK to Copilot/Atlassian/Bitbucket | 🔴 | RESOLVED |
| A4 | **Which models are enabled** on BNC Copilot seats | `models.yaml`/policy may reference unavailable models | Enumerate enabled models per seat; align catalog + tiers | 🔴 | OPEN |

## B. Engine (Pillar A)

| # | Risk / question | Impact | Mitigation | Sev | Status |
|---|---|---|---|---|---|
| B1 | **Target-service stack** | AST extractor correctness | Confirmed: **Spring MVC (Java, annotations)** — annotation-based JavaParser extractor is correct | 🟠 | RESOLVED |
| B2 | **Swagger 2.0 + OpenAPI 3.x** | Parser/diff/render must handle 2.0 | Confirmed: handle **both**; swagger-parser reads 2.0+3.x → one IR; emit corrected YAML in the **source spec's version** to stay drop-in | 🟠 | CONFIRMED |
| B3 | No full classpath for JavaParser (cross-module DTOs, Lombok, MapStruct, generics, inheritance) | Unresolved types → schema blind-spots | SymbolSolver + optional `.m2` jars; syntactic fallback; never fabricate | 🟠 | OPEN |
| B4 | **Security-scheme extraction is lowest-fidelity** (`@PreAuthorize` SpEL, gateway-level auth, scopes elsewhere) | Noisy/false security findings | Make security findings tunable/suppressible; low confidence by default | 🟠 | OPEN |
| B5 | **Contract lives in BOTH the repo AND the Confluence portal** | Two specs that can disagree with each other and with the code | **Three-way reconciliation:** diff each spec vs code AND the two specs vs each other; add a `SPEC_DRIFT` finding type + multi-spec input to the engine; report which spec is stale | 🟠 | CONFIRMED |
| B6 | **Monorepo / multiple services per repo** | "select repo → validate" assumes 1:1 | Allow module/subdir + spec selection | 🟡 | OPEN |

## C. Integrations (Jira / Xray / Confluence / Bitbucket)

| # | Risk / question | Impact | Mitigation | Sev | Status |
|---|---|---|---|---|---|
| C1 | **Xray Cloud (GraphQL) vs Server/DC (REST)** divergence; existing client is Cloud-only | "both" is real work | Confirmed: **Xray Cloud (GraphQL)** first. Corollary: Xray Cloud ⇒ **Jira Cloud** (descriptions = **ADF**, REST **v3**) ⇒ prioritize `AdfToMarkdown` + likely **Confluence Cloud** (storage-XHTML). Build the Cloud path end-to-end first | 🟠 | RESOLVED |
| C2 | **Xray test types** (Manual / Cucumber / Generic), steps model, test repo folders, versions | Review/update + create must match the type | Confirm types in use; map step model | 🟠 | OPEN |
| C3 | **Jira required fields / workflows / screens** on issue create | create-defect / create-test fail on mandatory custom fields | Per-project field config + a field-validation/dry-run step | 🟠 | OPEN |
| C4 | **Token permissions** (read-only tokens can't create/update) | Outward actions fail | Pre-flight scope check with exact missing permission | 🟠 | OPEN |
| C5 | **Rate limits** (Atlassian Cloud, Xray Cloud GraphQL especially) | Bulk load/create throttled | Backoff + honor Retry-After + batch | 🟡 | OPEN |
| C6 | **Bitbucket auth specifics** | Discovery/clone/PR | Confirmed **Bitbucket Cloud**: REST /2.0, workspace + project-key discovery, app password/OAuth, https clone with app password/token | 🟡 | RESOLVED |
| C7 | **All base URLs must be user-configurable** (internal hosts: `git.bnc`, `jira.bnc`, `confluence.bnc`) | Hardcoding breaks portability/environments | Confirmed: `veritas.connections` config (baseUrl + edition + authType) per system; tokens stay in the secret store. See [configuration.md](configuration.md) | 🟠 | RESOLVED |
| C8 | **`jira.bnc` vs earlier "Xray Cloud"** | Flips ADF-vs-wiki + Xray API | Confirmed: **all Atlassian/Bitbucket = Cloud** behind `*.bnc` DNS → ADF + Jira REST v3 + Confluence storage + Xray GraphQL. No DC variants for v1 | 🔴 | RESOLVED |

## D. Cost / model

| # | Risk / question | Impact | Mitigation | Sev | Status |
|---|---|---|---|---|---|
| D1 | **Cost is an estimate, not GitHub's invoice** (CLI exposes no real usage) | Expectation mismatch | Label as estimate; reconcile against the real bill; log everything | 🟠 | OPEN |
| D2 | **Usage-based credit rates (post 2026-06) not yet published** | Estimates rough in USAGE_CREDITS mode | Config-driven; update `creditUsd`/rates when published | 🟡 | OPEN |
| D3 | **Budget enforcement / mid-run exhaustion** designed, not built | A long run can blow the monthly allowance mid-way | Budget guard + cost gate + per-run cap; resumable runs | 🟡 | OPEN |
| D4 | **Per-endpoint LLM calls scale with service size** | Cost/latency balloon on big services | Batch findings per service; up-front cost preview | 🟠 | OPEN |

## E. Test generation (Pillar C)

| # | Risk / question | Impact | Mitigation | Sev | Status |
|---|---|---|---|---|---|
| E1 | **MD template doesn't exist yet** (user authors later) | Codegen can't be fully built/tested | Build the contract + parser + graceful errors now; finish when template lands | 🟡 | OPEN |
| E2 | **Generated tests only proven to compile, not pass** | "green build" ≠ working tests | State scope; running needs env/auth/test-data (out of PR scope) | 🟡 | OPEN |
| E3 | **Regeneration may clobber human-edited generated tests** | Lost edits in the output repo | Merge strategy + collision detection; PR diff review | 🟡 | OPEN |

## F. Multi-user / ops

| # | Risk / question | Impact | Mitigation | Sev | Status |
|---|---|---|---|---|---|
| F1 | **SSO / IdP choice** for server mode (Entra/Azure AD? Ping?) | Server auth design | Confirm IdP; OIDC by default | 🟠 | OPEN |
| F2 | **Per-user Copilot auth on a shared server** is genuinely hard | Server LLM execution attribution | v1: run LLM steps locally per user; server adds per-user Copilot tokens later | 🟠 | OPEN |
| F3 | **Gate pause/resume durability** for async runs (hours/days) | Lost/orphaned runs | Persisted job state + restart-safe resume; a job table | 🟠 | OPEN |
| F4 | **SQLite single-writer** under concurrent local runs | Contention | Fine for single-user; Postgres on server | 🟡 | OPEN |
| F5 | **PII / sensitive data in ingested requirements/test data** sent to LLM | Compliance | Redaction/scrub pass before LLM; policy + allowlist of fields | 🟠 | OPEN |
| F6 | **Audit of LLM prompts/responses** (bank may require it — or prohibit storing sensitive content) | Compliance tension | Configurable prompt/response audit with redaction + retention policy | 🟠 | OPEN |

## G. Quality / trust / process

| # | Risk / question | Impact | Mitigation | Sev | Status |
|---|---|---|---|---|---|
| G1 | **False positives erode trust** (esp. L5 design-quality, security) | Report ignored | Confidence scoring + "mark false positive"/suppress + tuning | 🟠 | OPEN |
| G2 | **No real BNC service validated yet** | Extraction gaps unknown | Validate engine against one real service + its real spec early | 🟠 | OPEN |
| G3 | **Success criteria / acceptance** (precision/recall bar) undefined | "Done" is fuzzy | Agree a target + a labelled sample to measure against | 🟡 | OPEN |
| G4 | **Maintenance ownership** of prompts, knowledge-pack, `models.yaml` as ISTQB + Copilot evolve | Drift | Assign an owner; version + review cadence | 🟡 | OPEN |
