# Cost tracking & model selection

Goal: a **dollar cost per LLM action**, recorded for every run, and a policy that picks the **cheapest
model that can do the job**. Because GitHub Copilot is **mid-transition in its billing model**, this is
config-driven and pluggable, not hardcoded.

## 1. How Copilot bills (verified June 2026)

- **Premium requests** with monthly allowances: Free 50 · Pro 300 · Pro+ 1,500 · Business 300/user ·
  Enterprise 1,000/user. Extra requests historically **$0.04** each.
- **Per-model multiplier** (≈0.25× Gemini Flash → 1× Sonnet → 10× Opus → up to 50× GPT-4.5).
- **The Copilot CLI always consumes ≥1 premium request — even on "free" base models** (the CLI is a
  premium feature). So a single `copilot -p` ≈ `max(1, multiplier)` premium requests.
- **Change on 2026-06-01:** Copilot moves to **usage-based "GitHub AI Credits" tied to token
  consumption**, replacing fixed request units.

**Design consequence:** support two billing strategies behind one interface and switch by config.

## 2. Config-driven model catalog

`resources/veritas/models.yaml` — editable as Copilot adds/removes models (no code change):

```yaml
billingMode: PER_REQUEST          # PER_REQUEST (legacy) | USAGE_CREDITS (>= 2026-06)
pricePerRequestUsd: 0.04
creditUsd: 0.00                   # set when usage-based credit $ rate is published
cliCountsAsPremium: true          # CLI always burns >=1 request, even mult 0
models:
  - { id: gpt-4.1,           tier: economy,  requestMultiplier: 0,    creditsPerMTokIn: 0,  creditsPerMTokOut: 0 }
  - { id: gemini-2.0-flash,  tier: economy,  requestMultiplier: 0.25 }
  - { id: claude-sonnet-4,   tier: standard, requestMultiplier: 1 }
  - { id: o3,                tier: deep,     requestMultiplier: 5 }
  - { id: claude-opus-4,     tier: frontier, requestMultiplier: 10 }
```

> Multipliers/credit rates above are working values — confirm against the live
> [model-multipliers doc](https://docs.github.com/en/copilot/reference/copilot-billing/request-based-billing-legacy/model-multipliers-for-annual-plans)
> at deploy time. They live only in this file.

## 3. Cost estimator

`CostEstimator` has two strategies selected by `billingMode`:

- **PER_REQUEST:** `requests = cliCountsAsPremium ? max(1, multiplier) : multiplier`;
  `costUsd = requests * pricePerRequestUsd`. One `copilot -p` = one call.
- **USAGE_CREDITS:** `credits = (tokIn/1e6)*creditsPerMTokIn + (tokOut/1e6)*creditsPerMTokOut`;
  `costUsd = credits * creditUsd`. The CLI doesn't reliably return token counts, so we **estimate tokens
  from character counts** (~4 chars/token) of the assembled prompt + response until real counts are exposed.

Recorded **per `llm` step** on `RunStep`: `model, billingMode, premiumRequests, estTokensIn, estTokensOut,
estCostUsd`. Rolled up on `SkillRun`: `totalPremiumRequests, totalEstCostUsd`. Surfaced in the dashboard
(per action, per skill, per user, per day) and in the management report footer.

Deterministic and gate steps record **zero** cost — making the "LLM only when needed" savings visible.

## 4. Model selection by tier

Each `llm` step declares a **tier** (`economy | standard | deep | frontier`) instead of a hard model.
`resources/veritas/model-policy.yaml` maps tier → concrete model + fallbacks (config-driven):

```yaml
tiers:
  economy:  { primary: gpt-4.1,         fallbacks: [gemini-2.0-flash] }
  standard: { primary: claude-sonnet-4, fallbacks: [gpt-4.1] }
  deep:     { primary: o3,              fallbacks: [claude-sonnet-4] }
  frontier: { primary: claude-opus-4,   fallbacks: [o3] }
```

`ModelSelector` resolves `tier → model` at runtime (honoring an explicit `model:` override on the step,
availability, and the budget guard). **Rule of thumb:** reshape/extract → economy/standard; synthesize or
judge → deep; never frontier unless a quality gate fails twice.

### Tier per skill/prompt (from the prompt review)

| Skill / prompt | Tier | Why |
|---|---|---|
| create-defect (prose), echo, simple json reshape | economy | mechanical |
| validate-contract (reconcile) | standard | rule-driven extraction + diff; YAML correctness matters |
| generate-test-plan (legacy "quick plan") | economy | mechanical endpoint→case reshaping |
| score-test-artifacts (review-test-cases) | standard→deep | deterministic rubric, but fair multi-dim judgment benefits from a stronger model |
| analyze-automation-coverage | standard | rule-table decisions; cost is in HTML output volume — pick cheap-output model |
| generate-test-artifacts (strategy/plan/cases) | deep | genuine ISTQB synthesis across most of the pack |
| automate-service (composite) | mixed | route phases to economy/standard, only strategy to deep |

## 5. Verification strategy (the "light model reviews the big model?" question)

Tiered, configurable — avoids both waste and the weak-judge pitfall:

- **L0 — deterministic, free (always on):** JSON-schema validation, OpenAPI round-trip re-parse, and
  cross-checking LLM findings against the deterministically-extracted `ApiModel` (so the model can't invent
  endpoints/fields). This catches most errors at zero cost.
- **L1 — light-model self-check (economy, default-on for cheap skills):** semantic consistency only —
  "does the explanation reference the cited snippet? does the fix match the finding?" Cheap, bounded.
- **L2 — strong cross-check (deep/frontier, opt-in, high-risk only):** before the human gate, for outputs
  with real blast radius. Not a weak model judging a strong one.

Conclusion: **don't use a weak model to judge deep reasoning** (false confidence) — push structural/factual
checks into free deterministic code, use a light model only for cheap semantic sanity, reserve expensive
cross-checks for high-risk outputs, and always end with the human gate for outward actions.

## 6. Budget guard (optional)

Config `veritas.cost.monthly-budget-usd` (per user). Before each `llm` step the runner checks projected
spend; on breach it either downgrades the tier or raises a **cost gate** (human approval) — never silently
overspends. All estimates are logged so the team can reconcile against the real Copilot invoice.

## 7. Token-cost reductions already identified (see prompts-review.md)

- **Section-addressable knowledge-pack includes** → ~11k input tokens saved per suite run (~50% of
  grounding cost). The single biggest lever; implemented in the prompt vendoring step, not at model time.
- Trimming inline ISTQB restatements + illustrative examples → ~600–900 tokens/suite.
