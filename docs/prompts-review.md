# Prompt review & optimization plan

Review of the existing Copilot prompts (in `MGL8707/.github/copilot-prompts`) that Veritas vendors. They
are invoked head-less via `copilot -p`, where **tokens cost money**, so this is also a cost document.

## Sizes (char/4 is the safer planning number — these files are table/code-heavy)

| File | Lines | ~tokens (char/4) |
|---|---|---|
| validate-service-contract | 179 | ~2,116 |
| generate-test-artifacts | 185 | ~1,770 |
| score-test-artifacts | 189 | ~2,063 |
| analyze-automation-coverage | 132 | ~1,842 |
| **istqb-knowledge-pack** | 357 | **~5,603** |
| automate-service-tests | 304 | ~3,438 |
| generate-test-plan | 84 | ~887 |

**Headline:** the 4 ISTQB task prompts are well-engineered; the dominant cost is that each pastes the
**entire** knowledge-pack (~5.6k tok) while using only 3–5 of its 13 sections. The 2 legacy "SDET API LSI"
prompts are interactive and will misbehave under `copilot -p`.

## Findings (condensed)

- **validate-service-contract** — strong extraction tables; but output is human markdown + embedded YAML
  (no terminal json), no `{{var}}`, and "Requirements on corrected YAML" duplicates the self-check (~120 tok).
- **generate-test-artifacts** — excellent per-case template; but restates likelihood/impact factors,
  estimation formula, defect lifecycle and metric categories that already live in the pack (~300 tok waste);
  markdown-only output; no `{{var}}`.
- **score-test-artifacts** — best-designed (weighted rubric + worked formula); but the scores/verdict/Top-3
  are the product and are markdown-only → **strongest need for terminal json**. Trim the gap-block example (~100 tok).
- **analyze-automation-coverage** — crisp HTML spec + the only strict "output exactly: summary, one html
  block, one line." Add a trailing **json** mirror of the exec-summary numbers so Veritas needn't scrape HTML.
- **istqb-knowledge-pack** — great §0 grounding rules; but a monolith with "paste the full pack." Make it
  section-addressable; §2/§7/§13 are rarely needed by task prompts.
- **automate-service-tests** — practical and project-specific, has frontmatter; but **interactive
  (multiple STOP/ask gates → will hang under `-p`)**, has a **duplicate "Phase 3"** numbering bug, and a
  ~600-tok bespoke strategy template that re-implements the pack with a *divergent* taxonomy (P0/P1 vs VL–VH).
- **generate-test-plan** — lean and practical; interactive tail; overlaps `generate-test-artifacts` Phase 3.
  Decide: make it the explicit **economy** "quick plan" path, or fold it in.

## Cross-cutting fixes (highest ROI first)

1. **Section-addressable knowledge-pack includes.** Replace "paste full pack" with a per-prompt directive
   `<!-- KNOWLEDGE-PACK-SECTIONS: 0,1,5,6.1,12 -->`; Veritas's vendoring step slices the pack by its
   `##/###` anchors and substitutes at the `[KNOWLEDGE PACK]` marker (§0 always included). **~11k tokens
   saved per suite run (~50% of grounding cost), recurring.** Fallback if slicing is too much: ship a
   trimmed "core pack" (~2.5k tok) default + full pack only for `generate-test-artifacts` (~40% saving).
2. **Standard OUTPUT CONTRACT** appended to every task prompt: human markdown first, then **exactly one
   fenced ```json block last** (nothing after), validating a per-prompt shape; uncomputable → null, never
   invent. Add a self-check line: "ends with exactly one valid json block."
3. **De-interactivize the two legacy prompts:** replace each "STOP and ask" with
   `if {{auto_approve}} proceed; else emit {"status":"needs_input","questions":[...]} and stop` — never block on stdin.
4. **Fix the duplicate Phase-3 bug** in automate-service-tests (renumber DATA/IMPLEMENT/VERIFY → 4/5/6).
5. **`{{var}}` templating** across all prompts; a build-time check fails the vendoring if any `{{...}}`
   remains unsubstituted.
6. **Cut inline ISTQB restatements** (point to pack §§ instead) + trim illustrative examples (~1k tok total).
7. **Resolve the two-plan overlap** (economy variant vs fold-in).
8. **Assign model tiers** per prompt (see [cost-and-model-selection.md](cost-and-model-selection.md) §4).

## Standard output-contract shapes (per prompt)

- validate-service-contract: `{endpoints_code, endpoints_yaml, matching, missing, dead_spec,
  signature_mismatches, dto_constraint_gaps, findings:[{severity,kind,location,msg,cite}],
  corrected_yaml_emitted, blind_spots:[]}`
- generate-test-artifacts: `{strategy_present, plan_present, cases_total, requirements_coverage_pct,
  risk_coverage_pct, techniques:{}, high_risks_uncovered:[], orphan_cases}`
- score-test-artifacts: `{artifacts:{...}, combined_score, combined_verdict, gaps:[...], top3_actions:[]}`
- analyze-automation-coverage: keep the ```html block, add `{overall_pct, critical_pct,
  risk_weighted_pct, pyramid, automate_next, keep_manual, dead_automation, orphaned_modules, blind_spots}`

## `{{var}}` per prompt

| Prompt | Variables |
|---|---|
| validate-service-contract | `{{spring_source}}`, `{{openapi_yaml}}`, `{{auto_approve}}` |
| generate-test-artifacts | `{{feature_spec}}`, `{{requirements}}`, `{{api_contract}}`, `{{acceptance_criteria}}`, `{{context}}` |
| score-test-artifacts | `{{artifacts}}` (or `{{strategy}}`/`{{plan}}`/`{{test_cases}}`) |
| analyze-automation-coverage | `{{test_strategy}}`, `{{test_plan}}`, `{{test_cases}}`, `{{automated_tests}}`, `{{production_code}}` |
| automate-service-tests | `{{service_source}}`, `{{swagger_url}}`, `{{env}}`, `{{service_repo_name}}`, `{{auto_approve}}` |
| generate-test-plan | `{{service_source}}`, `{{swagger_url}}`, `{{env}}` |

## How Veritas consumes this

The vendored prompts live in `resources/veritas/prompts/`. The vendoring/build step (a) slices the pack per
the `KNOWLEDGE-PACK-SECTIONS` directive, (b) verifies every prompt ends with an output-contract block and a
matching schema exists in `resources/veritas/schemas/`, (c) fails on any leftover `{{var}}`. `PromptAssembler`
then substitutes runtime values; `ResponseSchemaValidator` enforces the json shape; `ModelSelector` picks the
tier. **Improved prompt versions are produced as a follow-up task — this doc is the spec for them.**
