# Veritas — Architecture

Veritas is a **toolbox of independent, on-demand skills** that wrap the GitHub Copilot CLI. Three pillars
(validate the contract · manage the tests · generate automated tests). The LLM is called **only** for
reasoning/generation; everything deterministic (clone, AST, OpenAPI parse, diff, REST calls, file writing,
rendering, cost accounting) runs in Java. Local-first, server-ready.

See also: [cost-and-model-selection.md](cost-and-model-selection.md) ·
[security-auth-and-credentials.md](security-auth-and-credentials.md) ·
[review-test-cases.md](review-test-cases.md) ·
[test-generation-template.md](test-generation-template.md) ·
[prompts-review.md](prompts-review.md).

## 1. Component view

```mermaid
flowchart LR
  user["User"]
  user -->|"veritas <cmd>"| cli["CLI (picocli)"]
  user -->|HTTPS| web["React dashboard (per-user)"]
  web --> rest["REST API"]
  cli --> runner["SkillRunner"]
  rest --> runner

  runner --> handlers["Deterministic StepHandlers"]
  runner --> llm["LlmGateway → Copilot CLI"]
  runner --> gate["GateService (human approval)"]
  runner --> cost["ModelSelector + CostEstimator"]

  handlers --> engine["Engine: AST + OpenAPI + Diff"]
  handlers --> coverage["Coverage / RTM"]
  handlers --> codegen["Codegen: TemplateLearner..PrPublisher"]
  handlers --> integ["Integrations: Bitbucket · Jira · Xray · Confluence"]

  runner --> db["DB: SQLite → Postgres"]
  cost --> db
  secrets["ChainedSecretProvider (per-user)"] --> integ
  secrets --> llm
```

## 2. Skill run — where the LLM (and cost) actually happen

Every pipeline step is tagged `deterministic`, `llm`, or `gate`. Only `llm` steps cost money; the runner
resolves a model from the step's **tier** and records premium-requests/credits + dollar estimate per step.

```mermaid
sequenceDiagram
  actor U as User
  participant R as SkillRunner
  participant H as StepHandler (Java)
  participant M as ModelSelector + Cost
  participant L as Copilot CLI
  participant G as GateService
  participant D as DB
  U->>R: run(skill, inputs)
  R->>D: create SkillRun(RUNNING, owner=user)
  loop each step
    alt deterministic
      R->>H: handle()  %% no LLM, no cost
    else llm
      R->>M: resolve model for step.tier
      R->>L: copilot -p (assembled prompt, -s, --model)
      L-->>R: markdown + fenced json
      R->>R: extract json + schema-validate (free)
      R->>M: record premium requests + $ estimate
    else gate
      R->>G: await human approval (outward action)
    end
    R->>D: persist RunStep (+ model + cost)
  end
  R->>D: SkillRun(COMPLETED, totalCost)
  R-->>U: result
```

## 3. Deployment — local-first, server-ready

The only three things that change between local and server are **storage**, **secrets**, and **auth**;
everything else is identical, isolated behind interfaces + Spring profiles.

```mermaid
flowchart TB
  subgraph Local["Local (single user)"]
    j["veritas.jar (web + CLI)"] --> sq["SQLite"]
    j --> kc["OS keychain / enc-file secrets"]
    j --> cl["copilot device login (user's seat)"]
  end
  subgraph Server["Internal server (multi-user)"]
    px["Reverse proxy + SSO (OIDC)"] --> svc["veritas service"]
    svc --> pg["Postgres"]
    svc --> vault["Vault: per-user secrets"]
    svc --> clr["per-user Copilot auth"]
  end
```

## 4. Data model (high level)

Per-user ownership is present from the start so dashboards are scoped per user (see
[security-auth-and-credentials.md](security-auth-and-credentials.md)). Cost rolls up on `SkillRun`/`RunStep`.

```mermaid
erDiagram
  USERS ||--o{ SKILLRUN : owns
  USERS ||--o{ SCAN : owns
  APP ||--o{ SERVICE : contains
  SERVICE ||--o{ SCAN : has
  SCAN ||--o{ FINDING : produces
  FINDING ||--o| DEFECTLINK : may_have
  SERVICE ||--o{ TESTSTRATEGY : has
  TESTSTRATEGY ||--o{ TESTPLAN : informs
  TESTPLAN ||--o{ TESTCASE : includes
  TESTCASE ||--o| XRAYLINK : may_have
  TESTPLAN ||--o{ COVERAGEITEM : rtm
  SKILLRUN ||--o{ RUNSTEP : has
  SKILLRUN ||--o| CODEGENRUN : may_have
```

## 5. Pillar flows

- **Validate contract (A):** discover repo (Bitbucket app-id) → clone → AST extract → OpenAPI parse →
  deterministic diff (L1–L4 + mechanical L6) → LLM reconcile (explain/fix/L5/L6 + corrected YAML) →
  report + corrected YAML. Detailed in the engine module.
- **Manage tests (B):** strategy / global+release plan with coverage reconciliation / create cases /
  **review cases** — the review flow is detailed in [review-test-cases.md](review-test-cases.md).
- **Generate tests (C):** template-driven — reads an MD template the user authors
  ([test-generation-template.md](test-generation-template.md)); learn template → generate → build-verify
  → gate → PR to the chosen output repo.
