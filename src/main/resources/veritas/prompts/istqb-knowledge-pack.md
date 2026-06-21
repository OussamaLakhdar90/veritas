# ISTQB Knowledge Pack

Grounding source for the ISTQB-aligned prompts in this folder. Update on new syllabus release; prompts embed via `[KNOWLEDGE PACK]`.

**Sources:** CTFL v4.0.1 (2024-09-15), CTAL-TM v3.0, CTAL-TA v4.0.

---

## 0. Grounding rules for agents

Every prompt that embeds this pack inherits these rules. Do not restate them — reference as *"knowledge pack §0"*.

1. **Cite the NAMED ISTQB concept** — never a section/paragraph number. Format: `(CTAL-TM — Risk-Based Testing)`, `(CTFL — Boundary Value Analysis)`, `(CTAL-TA — Decision Table Testing)`. **Do not emit `§1.3.3`-style numbers in any output field (citation, rationale, notes)** — reviewers don't recognise numbers and they can't be verified; the concept name is what carries weight.
2. **No hallucinated citations.** Cite only concepts the source syllabi actually cover (CTFL v4.0.1, CTAL-TM v3.0, CTAL-TA v4.0). Content outside the syllabi → say *"extension beyond syllabus"*. Never invent a concept or attribute it to the wrong syllabus.
3. **Cite ONE primary syllabus per topic** (named concept). Cite two only when a claim genuinely spans two syllabi (e.g. *test levels × ISO 25010* = CTFL + CTAL-TA). Each topic section names its own primary.
4. **Use ISTQB terminology verbatim:** `test basis`, `test item`, `test condition`, `test case`, `coverage item`, `risk level`, `entry criteria`, `exit criteria`, `test oracle`, `equivalence partitioning`, `boundary value analysis`, `state transition`. Never substitute *"user flow"* for `scenario`, *"validation"* for `test condition`, *"step"* for `coverage item`. Definitions: §1.
5. **No fabrication.** Report only what the inputs actually contain. Missing input → flag as gap or blind spot.
6. **If inputs are critical-missing, ask one consolidated question.** No placeholders, no guessing.

---

## 1. Terminology

| Term | Definition |
|---|---|
| Test basis | Body of knowledge used for test analysis/design (requirements, specs, stories, architecture, code, prior defects, risks). |
| Test item | The work product to be tested. |
| Test condition | A testable aspect identified during test analysis ("what to test"). |
| Test case | Preconditions + inputs + actions + expected results + postconditions, derived from a test condition. |
| Test suite | A set of test cases executed as a group. |
| Coverage | Specified coverage items exercised, as a percentage. |
| Coverage item | Smallest coverage-granularity unit for a technique (EP partition, BVA boundary, branch). |
| Error / Defect / Failure | Human mistake / flaw in a work product / observed deviation when the defect executes. |
| Root cause | Underlying reason; removing it stops the defect class recurring. |
| Test oracle | Source of expected results (spec, regulation, heuristic, prior version, model). |
| Test strategy (CTAL-TM §1.4) | Testing approach in a specific context so quality/organizational objectives are met. |
| Test plan (CTAL-TM §1.4.3) | Documentation vehicle for the strategy. Minimum: scope, objectives, exit criteria. |
| Entry / Exit criteria | Preconditions to start / conditions to complete an activity. Exit must be S.M.A.R.T. (CTAL-TM §1.4.3). |
| Definition of Done / Ready | Agile equivalents (CTFL §5.1.3). |
| Risk level | Combined measure of likelihood × impact. |
| Risk-based testing | Activities selected, prioritized, managed based on risk analysis & control. |

**Traceability — CTFL §1.4.4.** Bi-directional linkage between test basis items (requirements, risks, stories, AC) and testware (conditions, cases, procedures, results, defects). Enables coverage reporting, impact analysis, and confirmation that all risks are addressed. Cited whenever a rubric dimension or report mentions basis ↔ case linkage.

---

## 2. Seven testing principles (CTFL §1.3)

1. Testing shows presence, not absence, of defects.
2. Exhaustive testing is impossible.
3. Early testing saves time and money.
4. Defects cluster together.
5. Tests wear out (pesticide paradox).
6. Testing is context dependent.
7. Absence-of-defects fallacy — defect-free ≠ meets user needs.

---

## 3. Test levels (CTFL §2.2.1)

| Level | Focus | Typical owner |
|---|---|---|
| Component (unit) | Components in isolation; harnesses | Developers |
| Component integration | Interfaces between components | Developers |
| System | Full behavior + capabilities, functional + non-functional | Independent test team |
| System integration | Interfaces with external systems/services | Integration team |
| Acceptance | Validation + readiness (UAT, operational, contractual, regulatory, alpha, beta) | Users |

Distinguished by: test object, objectives, basis, defects/failures, approach, responsibilities.

---

## 4. Test types (CTFL §2.2.2)

- **Functional** — what the system does (completeness, correctness, appropriateness).
- **Non-functional** — how well; maps to ISO 25010 (see §5).
- **Black-box** — specification-based.
- **White-box** — structure-based.
- **Confirmation** — re-run failing test after fix.
- **Regression** — no adverse side effects from a change; prime automation candidate.

---

## 5. Quality characteristics — ISO 25010 (CTAL-TA §4)

| Characteristic | Sub-characteristics | CTAL-TA testing focus |
|---|---|---|
| Functional suitability | completeness, correctness, appropriateness | Any black-box; shift left (§4.1) |
| Performance efficiency | time behavior, resource utilization, capacity | Deferred to ISTQB-PT / CTAL-TTA |
| Compatibility | interoperability, coexistence | **Interoperability** via data-based + behavior-based at integration level (§4.4) |
| Usability / interaction | effectiveness, efficiency, satisfaction, accessibility (WCAG A/AA/AAA) | Usability reviews, task-based sessions, SUMI/WAMMI (§4.2) |
| Reliability | maturity, availability, fault tolerance, recoverability | — |
| Security | confidentiality, integrity, non-repudiation, authenticity, accountability | Deferred to ISTQB-Security / CTAL-TTA |
| Maintainability | modularity, reusability, analysability, modifiability, testability | — |
| Flexibility / portability | adaptability, scalability, installability, replaceability | **Adaptability + installability** via combinatorial across environments (§4.3) |
| Safety | — | — |

Technique-to-characteristic selection → see §6.5.

---

## 6. Test techniques catalog

**Primary: CTAL-TA §3.** CTFL §4.2 provides floor definitions for EP / BVA / decision table / state transition. White-box is CTFL-only (§4.3). Collaboration-based is CTFL-only (§4.5).

### 6.1 Black-box (specification-based)

#### Equivalence Partitioning (EP) — CTFL §4.2.1
Divide data into partitions processed identically; one value per partition suffices. Applies to inputs/outputs/config/internal/time/interface values. Partitions non-empty, non-overlapping.
- **Coverage item:** each partition (valid + invalid).
- **Coverage:** partitions exercised ÷ total.
- **Multi-parameter:** *Each Choice* — every partition of every parameter at least once (no combinations).
- **Example:** Age `0–17` invalid, `18–64` valid, `65+` senior → 3 partitions.

#### Boundary Value Analysis (BVA) — CTFL §4.2.2
Exercise boundaries of ordered partitions.
- **2-value:** boundary + nearest neighbour (adjacent partition).
- **3-value:** boundary + both neighbours. Detects `>` vs `≥` off-by-one that 2-value misses.
- **Coverage:** boundary values (+ neighbours) exercised ÷ total.
- **Example (18–64):** 2-value → {17, 18, 64, 65}; 3-value → {17, 18, 19, 63, 64, 65}.

#### Decision Table Testing — CTAL-TA §3.3.1 (floor: CTFL §4.2.3)
Combinations of conditions → actions (business rules). Rows = conditions/actions; columns = rules. `T/F`, `–` irrelevant, `N/A` infeasible, `X` action.
- **Minimization:** merge action-equivalent columns differing in one condition (→ `–`). Disregard infeasible.
- **Coverage item:** feasible condition-combination column.
- **Review:** consistency, feasibility, completeness, correctness. Checksum: Σ rule scores in minimized = Σ in full.
- **High-risk:** coverage on full (not minimized) table.

#### State Transition Testing — CTAL-TA §3.2.2 (floor: CTFL §4.2.4)
States (nodes) + `event [guard] / action` (edges). State table makes invalid transitions explicit.
- **All states** — every state visited.
- **0-switch / valid transitions** — every valid transition (most common).
- **All transitions** — every valid transition + every invalid attempted (test one invalid per case to avoid defect masking).
- **N-switch** (CTAL-TA) — every valid sequence of N+1 consecutive transitions. N≥2 explodes.
- **Round-trip** — loops from a start state back to itself, no other repeats.
- **Mission/safety-critical minimum:** all-transitions.

#### Use Case / Scenario — CTAL-TA §3.2.3
End-to-end from user perspective. Main / extension / exception scenarios.
- **Coverage:** scenarios executed ÷ identified. Add simple loop coverage (0, 1, >1, max) if loops exist.
- **Levels:** system, acceptance; also component integration for protocols.

#### Domain Testing — CTAL-TA §3.1.1
Generalises EP/BVA across multi-variable interacting domains.
- **Simplified:** ON, OFF for `<, ≤, >, ≥`; ON + 2 OFF for `=`; OFF + 2 ON for `≠`.
- **Reliable:** ON, OFF, IN, OUT per border.

#### Pairwise / Combinatorial — CTAL-TA §3.1.2
~97% of interaction failures involve ≤2 parameters.
- **Coverage:** base-choice or pairwise. Tool-assisted (minimal set is NP-hard). EP first if values are many.

#### Random — CTAL-TA §3.1.3
Volume / bias-free selection per probability distribution (operational profile for validation). **No recognised coverage criterion.** Exit on count/time.

#### CRUD — CTAL-TA §3.2.1
Verify entity lifecycle. Matrix: entities × functions. Completeness = every entity has C+R+U+D. Consistency = interactions + negatives.

#### Metamorphic — CTAL-TA §3.3.2
Follow-up cases derived from a source via a metamorphic relation. Used when no oracle exists (AI/ML, non-deterministic).

### 6.2 White-box — CTFL §4.3 only
- **Statement coverage** — every executable statement ≥1. 100% statement ≠ 100% branch.
- **Branch coverage** — every branch exercised; subsumes statement coverage.

### 6.3 Experience-based — CTAL-TA §3.4 (floor: CTFL §4.4)
- **Error guessing / fault attacks** — enumerate error classes (input, output, logic, computation, interfaces, data) + attack.
- **Exploratory (session-based)** — time-boxed, guided by test charter `Explore [target] With [resources] To discover [info]`; session sheet captures observations.
- **Checklist-based** — read-do or do-confirm; group by area/role/level; update via missed-defect retrospectives.
- **Crowd testing (§3.4.3)** — diverse testers add coverage diversity, don't replace techniques.

### 6.4 Collaboration-based — CTFL §4.5 only
- **Collaborative user story writing** — 3 C's: Card, Conversation, Confirmation. INVEST.
- **Acceptance criteria** — scenario-oriented (Given/When/Then) or rule-oriented.
- **ATDD** — tests before implementation; positive → negative → non-functional.

### 6.5 Technique selection — CTAL-TA §3.5.1

Based on: objectives, product risks, required coverage, test basis, recurring defects, tester knowledge, SDLC, regulatory/contract requirements, constraints.

- Data handling / domain / calculations → data-based (EP, BVA, domain, combinatorial).
- User requirements / protocols / processing → behaviour-based (CRUD, state transition, scenario).
- Logic / control flow → rule-based (decision table, metamorphic).
- No oracle → metamorphic, experience-based.
- Cyclical business processes → scenario-based + round-trip.

---

## 7. Static testing & reviews (CTFL §3, CTAL-TA §5.2.2)

- **Examinable:** requirements, user stories, architecture, design, code, test basis, test cases.
- **Review types (CTFL §3.2.4):** informal, walkthrough, technical review, inspection (most formal).
- **Review process (CTFL §3.2.2):** planning → initiate → individual review → issue communication/analysis → fix & report.
- **Techniques for test analysts (CTAL-TA §5.2.2):** ad-hoc, checklist-based, scenario-based, role-based (personas), perspective-based reading.

---

## 8. Test planning

**Primary: CTAL-TM §1.1.1, §1.4, §2.2.** CTFL additions (tagged below): test pyramid, quadrants, DoD/DoR, concrete entry/exit examples, named estimation techniques (TM defers to CTFL §5.1.4), test case prioritization.

### 8.1 Strategy vs plan — CTAL-TM §1.4
- **Strategy** = approach (org or project level). Guides activities, objectives, resources, schedule, responsibilities.
- **Plan** = document operationalizing the strategy. Variants: master / level-specific / quality-char / iteration. Lightweight in Agile.

### 8.2 Required plan content — CTAL-TM §1.4.3, §1.1.1
Context (scope, objectives, basis); assumptions/constraints; stakeholders; communication; **risk register** (product + project); **test approach** (levels, types, techniques, deliverables, entry/exit, independence, metrics, data, environment); schedule + estimation; budget; deviations from policy/strategy.

### 8.3 S.M.A.R.T. exit criteria — CTAL-TM §1.4.3
Specific, Measurable, Achievable, Relevant, Timely — derived from test objectives.

### 8.4 Typical entry/exit — CTFL §5.1.3 *(CTFL addition)*
- **Entry:** resources available, testware ready, smoke tests pass.
- **Exit:** thoroughness (coverage %, unresolved defects, density, failed cases) + binary gates (tests executed, static done, defects reported, regression automated). Time/budget exhaustion valid if risk accepted.

### 8.5 Estimation — CTAL-TM §2.2; named techniques CTFL §5.1.4 (TM defers)
- **Ratios** (metric) — e.g., dev:test 3:2.
- **Extrapolation** (metric) — average recent iterations.
- **Wideband Delphi** (expert) — iterative consensus; sequential SDLC.
- **Planning Poker** (expert) — Delphi variant with cards; Agile.
- **Three-point** (expert) — `E = (a + 4m + b) / 6`, `SD = (b − a) / 6`.
- **Factors (CTAL-TM §2.2.2):** product (basis quality, size, domain), process (maturity, SDLC, automation), people (skills, stability), results (defect volume, rework), context (distribution, complexity).

### 8.6 Test case prioritization — CTFL §5.1.5 *(CTFL addition — distinct from §9.6 risk prioritization)*
Risk-based · Coverage-based · Requirements-based. Dependencies override priority.

### 8.7 Test pyramid & quadrants — CTFL §5.1.6–7 *(CTFL addition)*
- **Pyramid:** many small/fast low-level, few slow high-level.
- **Quadrants (Marick/Crispin):**
  - Q1 tech/support-team — component + integration; automated in CI.
  - Q2 business/support-team — functional / user-story / example / API; automate or manual.
  - Q3 business/critique — exploratory, usability, UAT; mostly manual.
  - Q4 tech/critique — smoke + non-functional; often automated.

### 8.8 Stakeholders & RACI — CTAL-TM §1.2.2
Identify test stakeholders and map them on a **Power × Interest grid**: *Promoters* (high power, high interest — engage closely), *Latents* (high power, low interest — keep satisfied), *Defenders* (low power, high interest — keep informed), *Apathetics* (low power, low interest — monitor). Assign RACI per test activity (who is Responsible / Accountable / Consulted / Informed).

---

## 9. Risk management — CTAL-TM §1.3

CTFL §5.2 subsumed. Cite CTAL-TM only.

### 9.1 Types
- **Project** — organisational, people, technical, supplier. Impacts schedule/budget/scope.
- **Product** — maps to ISO 25010 (wrong/missing functionality, calculations, runtime, architecture, algorithms, response time, UX, security).

### 9.2 Risk level
- **Quantitative:** `likelihood × impact` (likelihood ∈ (0,1); impact monetized/scaled).
- **Qualitative:** ordinal (VL, L, M, H, VH) via risk matrix.

### 9.3 Workflow
Identification (expert interviews, independent assessment, retrospectives, workshops, brainstorming, checklists, historical data) → Assessment (categorize, score likelihood + impact, compute level) → Mitigation (test higher-risk earlier + more rigorously) → Monitoring (residual + emerging risks).

### 9.4 Likelihood factors (§1.3.3)
Tech/tool/architecture complexity; org maturity; personnel; team conflict; supplier issues; geographic distribution; leadership; time/resource/budget pressure; late QA; high change rate.

### 9.5 Impact factors (§1.3.3)
Usage frequency; criticality; reputation; revenue; liability; legal; integration; lack of workarounds; safety.

### 9.6 Prioritization strategies (§1.3.4)
- **Depth-first** — strict descending risk; mitigates top first.
- **Breadth-first** — at least one high-priority test per risk; broad view.

### 9.7 Methods (§1.3.5)
- **Heavyweight** — Hazard analysis, Cost-of-exposure, FMEA/FMECA, Fault tree analysis.
- **Lightweight** — SST, PRAM, PRISMA.

### 9.8 Risk register — minimum fields
ID, description, category (product/project + quality char.), likelihood (VL-VH or probability), impact (VL-VH or monetized), level, owner, mitigation, residual level, monitoring frequency.

---

## 10. Monitoring, control, metrics — CTAL-TM §2.1 (reporting sub-metrics: §2.1.3)

**Test completion activities — CTAL-TM §1.1.3.** Archive testware (cases, results, logs, reports); clean test environment and restore to pre-defined state; remove test data / tools / drivers / stubs / scripts; handover to stakeholders; capture lessons learned. Cited whenever a rubric references cleanup, handover, or closure.

### 10.1 Metric categories

| Category | Examples |
|---|---|
| Project progress | task completion, resource usage, effort actual vs planned |
| Test progress | planned/implemented/executed/passed/failed/blocked/skipped, execution time |
| Product quality | availability, response time, MTTF |
| Defect | count, severity/priority distribution, density, detection %, resolved vs total trend |
| Risk | residual risk level, % risks fully/partly/not tested |
| Coverage | requirements, product-risk, code (statement/branch/path) |
| Cost | actual vs planned, cost of quality |

### 10.2 Control directives (CTFL §5.3)
Re-prioritize tests; re-evaluate entry/exit criteria; adjust schedule; add resources.

### 10.3 Progress report — CTFL §5.3.2 *(CTFL content template)*
Period · progress vs plan · impediments + workarounds · metrics · new/changed risks · next-period plan.

### 10.4 Completion report — CTFL §5.3.2 *(CTFL content template)*
Summary · quality evaluation vs plan · deviations · impediments · final metrics · unmitigated risks · unfixed defects · lessons learned.

---

## 11. Defect management — CTAL-TM §2.3

CTFL §5.5 subsumed.

### 11.1 Lifecycle (§2.3.1)
`OPEN → IN PROGRESS → RESOLVED → CLOSED` with branches `REJECTED`, `RE-OPENED`, `DEFERRED`, `CLARIFICATION`, `ACCEPTED`. Single terminal state (CLOSED) with reason. Consecutive states belong to different roles.

### 11.2 Mandatory report fields (§2.3.5)
Title · detailed description with repro steps · severity (impact) · priority (to fix). Auto: ID, date, reporter, project, SDLC phase, state, owner, change history, references.

### 11.3 Process improvement (§2.3.6)
Phase containment; cost of quality; defect-introduction-phase analysis; root cause (5 whys, cause-effect, Pareto); cluster analysis; re-open rate; duplicate/rejected rate.

### 11.4 Agile specifics (§2.3.3)
Lighter, less formal. Report when: blocks other sprint work; not fixable within iteration; multi-team; supplier-sourced; explicitly requested. Otherwise → backlog.

---

## 12. API testing — canonical heuristics (derived; cite CTFL §2 / CTAL-TA §3)

Not a syllabus section. Referenced by the generate prompt.

- **EP** on every input field (path, query, header, body) — valid × invalid per spec.
- **BVA-3** on every bounded primitive (`@Size`, `@Min`, `@Max`, array size, range). Default 3-value for off-by-one safety.
- **Decision table** when behaviour depends on ≥2 boolean/categorical conditions (role × state × flag × auth).
- **State transition** for resource lifecycles (`pending → active → suspended → closed`) and sessions/tokens; 0-switch minimum, all-transitions for mission-critical.
- **CRUD matrix** for every REST resource; include negatives (read-missing, update-stale, delete-twice).
- **Scenario-based** for multi-endpoint workflows.
- **Pairwise** when a config-like endpoint has ≥3 orthogonal params.
- **Security cases** per endpoint with auth: unauthenticated, under-privileged role, token-expired, token-tampered.
- **Quality-characteristic mapping:** functional correctness (per endpoint), security (authZ matrix), reliability (retries/idempotency), compatibility (content-type negotiation, versioning).
- **Test-basis adequacy (CTFL §2):** OpenAPI with no constraints, no examples, no error responses is a weak test basis — call it out.

---

## 13. Standards referenced

- ISO/IEC/IEEE 29119-3 — test documentation.
- ISO/IEC/IEEE 29119-4 — test techniques.
- ISO/IEC 25010 — product quality model.
- ISO 31000 — risk management.
- IEEE 1044 — defect classification.

---

## 14. Source-precedence reference

Tie-breaker for which syllabus to cite as primary (rule §0.3). Each topic section also carries its own *Primary:* header; consult this table only for cross-topic or dual-citation decisions. (Sliceable — request §14 only when citation routing is in play; not injected on every call.)

| Topic | Primary | Secondary (only when it adds content) |
|---|---|---|
| Test strategy, plan, planning, estimation | **CTAL-TM** | CTFL: pyramid §5.1.6, quadrants §5.1.7, DoD/DoR, entry/exit examples §5.1.3, case prioritization §5.1.5, estimation techniques §5.1.4 |
| Risk management | **CTAL-TM §1.3** | CTFL §5.2 subsumed |
| Monitoring, control, metrics, reports | **CTAL-TM §2.1** | CTFL §5.3.2 report templates |
| Defect management | **CTAL-TM §2.3** | CTFL §5.5 subsumed |
| Stakeholder/role management | **CTAL-TM §1.2** | — |
| Test design, conditions, case specification | **CTAL-TA §1.2–1.3** | — |
| Techniques — black-box | **CTAL-TA §3.1–3.3** | CTFL §4.2 floor (EP, BVA, decision table, state transition) |
| Techniques — white-box | **CTFL §4.3** | — |
| Techniques — experience-based | **CTAL-TA §3.4** | CTFL §4.4 floor |
| Collaboration-based / ATDD / user stories | **CTFL §4.5** | — |
| Quality characteristics (ISO 25010) | **CTAL-TA §4** | CTFL §2.2.2 raw list |
| Reviews — process | **CTFL §3** | — |
| Reviews — techniques for test analysts | **CTAL-TA §5.2.2** | — |
