# Test Strategy — Design (ISTQB Advanced Test Manager)

> Answers four questions: per-section vs one-shot LLM generation, template or not, how a user revises a
> generated strategy, and how it relates to an Epic. Governing rule unchanged: **the LLM is called only where
> judgement is needed; everything structural/deterministic is Java.** Bank constraints: the approved automation
> framework is TestNG + Rest-Assured over `ca.bnc.lsist.api` — **Postman/Newman are prohibited**; ISTQB is cited
> by **named concept**, never by paragraph number.

## 1. What a Test Strategy is here (scope)

A **Test Strategy** is one **organisation/service-level** deliverable (the "how we test this service" charter),
authored once and revised over time. It is the parent of the **release Test Plans** (which are per-fixVersion).
This maps cleanly to a **Jira Epic**:

```
Epic: "CIAM-Policies — Test Strategy"   (the strategy deliverable; status DRAFT→IN_REVIEW→APPROVED, versioned)
 ├─ Story/Plan: Release 8.2 Test Plan    (references strategy vN)
 ├─ Story/Plan: Release 8.3 Test Plan
 └─ Risk items / NFR items linked from the strategy's risk register
```

So "one epic" = the strategy is a single, long-lived, versioned epic; plans and risks hang off it and each plan
pins the **strategy version** it was built against (so a later strategy edit never silently changes a shipped plan).

## 2. Sections (CTAL-TM as the guide)

Generated as a **structured deliverable** (first-class JSON, rendered to a document), each section tied to a named
ISTQB Advanced Test Manager concept:

| # | Section | ISTQB concept (named, not numbered) | Source |
|---|---|---|---|
| 1 | Scope & test items | *Test Scope* | deterministic from code/Jira + LLM |
| 2 | Quality characteristics / objectives | *ISO 25010 Quality Characteristics* | LLM |
| 3 | **Risk register** (product + project) | *Risk-Based Testing* | LLM (the load-bearing section) |
| 4 | Test approach | *Test Approaches (analytical/risk-based)* | LLM |
| 5 | Test levels | *Test Levels* | LLM |
| 6 | Test types | *Functional / Non-functional / Structural test types* | LLM |
| 7 | Techniques | *Test Design Techniques* | LLM |
| 8 | Entry / exit criteria | *Entry & Exit Criteria* | LLM, must be S.M.A.R.T. |
| 9 | Test environment & data | *Test Environment / Test Data Management* | LLM + deterministic ($sensitive refs) |
| 10 | Automation approach | *Test Automation* — TestNG+Rest-Assured for automated; Bruno / IntelliJ HTTP (`.http`) for ad-hoc; **no Postman/Newman** | LLM, framework-pinned |
| 11 | Roles & responsibilities | *Roles / RACI* | LLM |
| 12 | Estimation & schedule | *Test Estimation (three-point)* | LLM |
| 13 | Risks to the strategy + mitigations | *Project Risk* | LLM |
| 14 | Self-review (confidence + blind spots) | — (our honesty layer) | LLM |

The structural scaffold, the section list, ordering, traceability, and rendering are **deterministic Java**. The
LLM only produces the *content* of judgement sections.

## 3. Per-section vs one-shot generation — **Recommendation: per-section (orchestrated)**

| | One LLM call (whole strategy) | **Per-section calls (recommended)** |
|---|---|---|
| Quality | later sections thin out; generic | each section gets focused context + the right ISTQB concept |
| Revisability | must regenerate everything | regenerate **one** section in isolation |
| Cost | cheapest once | cheap model for boilerplate sections, deep model only for the **risk register** |
| Consistency | self-consistent | needs a **coherence pass** |
| Latency | one round-trip | parallel section calls |

**Decision:** generate **per section**, then run **one cheap "coherence pass"** that checks cross-section
consistency (e.g. every exit criterion maps to a risk; techniques cover the stated levels/types) and flags drift —
no rewrite. This mirrors the contract report: deterministic structure, targeted LLM, honest self-review. It also
makes section-level revision (next) natural, and lets us route the risk register to a DEEP model and the rest to a
cheaper tier (cost-aware, like the report's translation step).

## 4. Template — **Recommendation: yes, a structured-deliverable schema (not a prose template)**

- **Not** a fill-in-the-blanks prose template (can't be validated, rendered, diffed, or revised section-wise).
- **Yes** a JSON Schema (`test-strategy.schema.json`) that defines the sections above as typed fields, plus an
  optional **org house-style template** that seeds defaults (mandatory sections, approved tools = TestNG/Rest-
  Assured for automation + Bruno / IntelliJ HTTP Client for ad-hoc, banned tools = Postman/Newman, default
  environments, RACI roles). The LLM fills the schema; a
  deterministic renderer produces the document (HTML/PDF, bilingual EN/FR), exactly as the test-plan deliverable
  works today. One house-style file = one place to rebrand/retune.

## 5. Revision workflow (how a user revises a generated strategy)

Persist the strategy as **immutable versions** of structured `deliverableJson` (never overwrite):

```
TestStrategy { id, serviceName, version, status(DRAFT|IN_REVIEW|APPROVED), deliverableJson, confidence, createdBy, createdAt }
```

User actions (each creates a new version; history is kept and diffable):

1. **Edit a section inline** — `PATCH /strategies/{id}/sections/{key}` with the new content → new version, no LLM.
2. **Regenerate a section** — `POST /strategies/{id}/sections/{key}:regenerate` with optional free-text guidance
   ("emphasise auth-Z risk", "we have no perf environment") → LLM produces a **candidate** the user accepts or
   rejects; only the chosen section changes.
3. **Comment / request changes** — reviewer notes per section (IN_REVIEW).
4. **Approve** — `POST /strategies/{id}/approve` locks the version; it becomes the basis release plans pin to.
5. **Diff** — `GET /strategies/{id}/versions/{a}..{b}` renders a side-by-side section diff.

Guarantees: release Test Plans reference a **specific strategy version**, so revising the strategy later never
silently changes an already-approved plan; the dashboard shows "plan built against strategy v3 (current is v5 —
review?)".

## 6. Build order (incremental, each slice green)

1. `test-strategy.schema.json` with the 14 sections + the house-style template resource (tools allow/deny list).
2. Per-section generation in `TestStrategyService` (cheap tier per section, DEEP for the risk register) + coherence pass.
3. Versioned persistence (`TestStrategy.version` + immutable rows) + `StrategyController` section PATCH /
   regenerate / approve / diff.
4. Deterministic renderer (bilingual, like the contract/test-plan reports); **no Postman anywhere**.
5. Dashboard Strategy workspace: section editor, per-section regenerate, version diff, approve.
6. Epic mapping: export the strategy as a Jira Epic; plans link to the Epic and pin the strategy version.
