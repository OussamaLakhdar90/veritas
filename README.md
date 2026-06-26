# Veritas

Copilot-CLI-wrapped API quality platform for National Bank of Canada (BNC). A toolbox of independent,
on-demand skills; the LLM (GitHub Copilot CLI) is called **only** for reasoning/generation — all
deterministic work (clone, AST extraction, OpenAPI parse, diff, REST calls, rendering, cost accounting)
runs in Java. Local-first, server-ready. Atlassian/Bitbucket **Cloud**.

## Build & run

Maven is installed but not on PATH; use the bundled distribution:

```bash
# PowerShell
& "$env:M2_HOME\bin\mvn.cmd" -q -DskipTests package      # build the jar
& "$env:M2_HOME\bin\mvn.cmd" -q test                     # run the test suite

# Dashboard (built into the jar's static resources)
cd dashboard; npm install; npm run build

java -jar target/veritas-0.1.0-SNAPSHOT.jar serve        # web + dashboard on http://127.0.0.1:8080
java -jar target/veritas-0.1.0-SNAPSHOT.jar --version     # CLI
```

## CLI commands

| Command | What it does |
|---|---|
| `validate-contract --repo <path> --spec <path>...` (or `--app-id --repo-slug`) | Diff code vs OpenAPI/Swagger spec(s); findings + report + corrected YAML. Multiple `--spec` → spec-vs-spec drift. |
| `create-defect --finding <id> --project <KEY>` | Create a Jira defect from a finding. |
| `test-strategy --name <svc>` (`--repo`/`--app-id` or `--jql`/`--confluence`) | Global ISTQB Test Manager strategy from code or Jira/Confluence. |
| `multi-source-strategy --name <svc>` (any combo of `--repo`/`--app-id`, `--jql`, `--confluence`) | Strategy synthesized from code + Jira + Confluence together (feature index → scorecard). |
| `analyze-test-conditions --name <svc>` (`--repo`/`--app-id` or `--jql`/`--confluence`) | ISTQB **test analysis**: identify + prioritize the test conditions ("what to test") from the approved strategy + basis; each traces a basis item + a risk and carries an automation candidacy. The work product between the strategy and the cases. |
| `release-test-plan --name <svc> --fix-version <v> --project <KEY> [--create]` | Release plan + coverage reconciliation (RTM); `--create` raises gap tests in Xray. |
| `create-test-cases --name <svc> ... [--project --push]` | ISTQB Test Analyst cases; `--push` creates them in Xray. |
| `review-test-cases --jql "<jql>" [--apply]` | Review/score Xray tests; `--apply` writes corrected steps back. |
| `implement-tests --name <svc> --service-repo <path> --template <md> --output-dir <dir>` | Template-driven test generation. |
| `doctor` | Check configuration readiness (connections + secrets + LLM) before running a skill. |
| `copilot-login` | GitHub Copilot device-flow sign-in (for `veritas.llm.mode=copilot`/`http`). |
| `echo --text <s>` | Phase-0 smoke test of the skill framework. |

## REST API (dashboard)

The full, always-current REST reference is served live by springdoc: **`/swagger-ui`** (interactive) and
**`/v3/api-docs`** (raw OpenAPI) when the server is running (`veritas serve`). ~60 endpoints across scans,
findings, defects, gates, strategies, test plans/cases, reviews, codegen, costs and settings.

## LLM mode

`veritas.llm.mode=mock` (default, local) returns canned, schema-valid JSON so every skill runs without the
`copilot` binary. Set `veritas.llm.mode=copilot` on a machine with the GitHub Copilot CLI to use the real
engine. Cost is recorded per LLM action (`models.yaml` / `model-policy.yaml`).

## Configuration

All endpoints are configurable (`veritas.connections.*` — Bitbucket / Jira / Confluence / Xray base URL +
edition + auth type); tokens come from the per-user secret store (env / keychain / Vault), never config.
See [docs/configuration.md](docs/configuration.md).

## Status

Implemented & tested (mock LLM + stubbed HTTP where live systems are required): the orchestration
framework, cost/model selection, the contract-validation engine (+ validate-contract skill, report,
corrected YAML, spec drift), Jira/Confluence ingestion (ADF/storage → markdown → test basis), Bitbucket
Cloud discovery/clone, Jira/Xray clients, create-defect, test-strategy, analyze-test-conditions (test
analysis → Test Condition List + automation candidacy), release-test-plan + coverage, create-test-cases
(linked back to their test conditions), review-test-cases, implement-tests, and the React dashboard.

Management report exports to **HTML and PDF** (openhtmltopdf).

**Deliberately deferred:** Flyway migrations (the app uses Hibernate `ddl-auto` locally, which is fine;
Flyway is the Postgres/server-promotion step and is kept out to avoid destabilizing the working SQLite
build) and **live validation** against the real Copilot CLI + Atlassian/Bitbucket Cloud tenants (no
credentials/egress for those on this dev box — every such path is built and stub/mock-tested). See
[docs/open-questions-and-risks.md](docs/open-questions-and-risks.md).

## Docs

[architecture](docs/architecture.md) · [how it works: AST/diff/cost](docs/concepts-ast-diff-cost.md) ·
[cost & model selection](docs/cost-and-model-selection.md) · [security/auth](docs/security-auth-and-credentials.md) ·
[review-test-cases](docs/review-test-cases.md) · [ingestion](docs/ingestion-jira-confluence.md) ·
[test-generation template](docs/test-generation-template.md) · [test-strategy design](docs/test-strategy-design.md) ·
[multi-source strategy pipeline](docs/test-strategy-multisource-pipeline.md) ·
[prompt review](docs/prompts-review.md) · [configuration](docs/configuration.md) · [CI](docs/ci.md) ·
[BNC contract-validator reference](docs/reference-contract-validator.md) ·
[open questions & risks](docs/open-questions-and-risks.md).

> Historical planning notes: [gaps & definition-of-done](docs/gaps.md) and [remaining-work roadmap](docs/roadmap-remaining.md)
> predate the recent remediation waves and are kept for provenance only — they no longer reflect current status.
