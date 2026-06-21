# ISTQB-Grounded Prompts for Copilot v5

Five prompt files plus a shared knowledge pack. Paste any prompt into your v5 agent; before you do, replace its `[KNOWLEDGE PACK]` marker with the full content of `istqb-knowledge-pack.md` (or make that file readable to the agent).

## Files

| File | Purpose |
|---|---|
| `istqb-knowledge-pack.md` | Distilled CTFL v4.0.1 + CTAL-TM v3.0 + CTAL-TA v4.0. §0 holds the grounding rules every prompt inherits. |
| `generate-test-artifacts.prompt.md` | Generate Test Strategy → Plan → Cases from a feature / API spec / requirements. |
| `score-test-artifacts.prompt.md` | Score pasted Strategy / Plan / Cases 0–100 with weighted rubric, gaps, how-to-improve. |
| `validate-service-contract.prompt.md` | Diff Spring Boot code vs OpenAPI YAML; emit missing / wrong list + corrected YAML. |
| `analyze-automation-coverage.prompt.md` | Audit existing automated tests vs strategy/plan; emit HTML report with prioritized automation backlog. |

## Source precedence (what to cite)

- **Strategy, plan, risk, metrics, defects** → cite **CTAL-TM** (primary). CTFL only for: test pyramid (§5.1.6), testing quadrants (§5.1.7), Definition of Done/Ready, concrete entry/exit examples (§5.1.3), test-case prioritization (§5.1.5), named estimation techniques (§5.1.4).
- **Test design, techniques, quality characteristics** → cite **CTAL-TA** (primary). CTFL only for: floor definitions of EP / BVA / decision table / state transition (§4.2), white-box (§4.3), collaboration-based / ATDD (§4.5), seven principles (§1.3), test levels (§2.2).

Full precedence table lives in knowledge-pack §0.

## Maintenance

When ISTQB publishes a new syllabus version, update the knowledge pack only — the prompts inherit automatically via `[KNOWLEDGE PACK]`.
