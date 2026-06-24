# Multi-source test-strategy pipeline (design)

**Status:** proposed · **Audience:** Veritas engineers + QE leads · **Supersedes the source-routing half of** [`test-strategy-design.md`](test-strategy-design.md) · **Builds on** [`ingestion-jira-confluence.md`](ingestion-jira-confluence.md).

## Why

Today the test-strategy generator takes **one** basis (code **or** Jira **or** Confluence) and feeds the whole thing to every section. We want it to:

- accept **any combination** of code / Jira / Confluence — including **code-only**, and the **pre-dev** case (no code yet);
- work while **development is in progress** — code shows what *exists*, the other sources supply the *not-yet-built* pieces, so the strategy covers the whole intended feature set, not just what's coded so far;
- be **cheap token-wise** and **consistent** run-to-run, while giving the model all the material it needs;
- be **evidence-first** — every section is written *from* cited source material, and a companion "why" document records that traceability.

Two product decisions are fixed (confirmed with the team):

- **Scale is large** (hundreds of Jira issues under the selected epics, big Confluence spaces) → we **never feed the whole corpus to the model**.
- **Features are derived from existing structure first** (Jira epics/components/labels, Confluence page titles, code packages) → the clustering is **deterministic-first**, with a cheap LLM only to canonicalise.

## The one idea

> **Extract everything deterministically ($0), index it by *feature* once (one cheap call), then each strategy section pulls only its feature's slice.**

This resolves the central tension ("extract everything *and* feed the LLM everything" vs "cheap"): we **extract** everything (deterministic, free) but **feed** each LLM call only the one feature's evidence. The model has everything it needs for the section it's writing; it never sees the whole corpus at once. Because every call reads from the *same cached index*, sections can't drift from each other.

---

## 1. Source model & prioritisation

Three source kinds, each authoritative for a different axis. The synthesis prompt is told *which source wins* when they disagree — that is the consistency lever.

| Source | Authoritative for | Do **not** trust it for |
|---|---|---|
| **Jira** (epics/issues) | business **intent & scope** — what we promised, acceptance criteria, what's in this release | implementation truth (issues drift from code) |
| **Confluence** | **design rationale & rules** — architecture, technical design, functional & business analysis | current scope (pages go stale) |
| **Code** (`JavaSpringExtractor`) | **implementation truth** — endpoints, DTOs, constraints, what actually exists | intent (code can't say what was *meant*) |

**Precedence rule, fed verbatim into every synthesis prompt:**

> Intent & scope: **Jira > Confluence > code**. Implementation truth: **code > Confluence > Jira**. When a risk arises from a *gap between* sources (Jira says X, code does Y, or Jira/Confluence specify something with no code yet), that gap is itself the highest-value risk — cite both unit ids.

A `SourceMix` record `{boolean code, jira, confluence}` is computed at ingest and passed into the prompt so the model never claims something a missing source can't support. The evidence-first rule (§4) makes that **structural**, not just advisory.

### 1.1 Combination rules (any subset works)

- **Code only** — a *factual* strategy. Scope = the implemented endpoints; risk register is structural (validation gaps, error-path coverage, undocumented status codes). **No business-intent claims** (there are no Jira/Confluence units to cite, so evidence-first blocks them). This is a fully supported, first-class mode — useful for legacy services with no docs.
- **Jira only** — intent-driven, no implementation claims. Scope straight from the selected issues. Good pre-dev.
- **Confluence only** — design/rules-driven; strong for risk identification, weak on scope (the UI warns).
- **Jira + Confluence (no code)** — the **pre-dev** case: intent (Jira) + rationale (Confluence). The strongest non-code combination.
- **All three** — full power, and where the *gap-risks* light up: "feature X is *specified* (`JIRA-1012`), *designed* this way (`CONF-A`), and *implemented* as these endpoints (`CODE:FooController`) — coverage gap here."

### 1.2 Development-in-progress (the "missing pieces" case)

When code is **partially built**, the strategy must still cover the *whole intended feature set* — the parts not yet coded come from Jira/Confluence. We make this explicit with a **per-feature implementation status**, derived **deterministically** from which sources cover each feature (see §3.1):

| Feature covered by | Status | What the strategy does |
|---|---|---|
| Jira/Confluence **but no code** | `PLANNED` (not built yet) | Design the tests from intent/design; mark them **pending** ("blocked until implemented"). Keeps the plan complete during dev. |
| Jira/Confluence **and** code | `IMPLEMENTED` | Full coverage; gap-risks where intent ≠ implementation. |
| Code **but no** Jira/Confluence | `UNDOCUMENTED` | Flag as "implemented but unspecified — confirm intent / possible scope creep"; still write tests from the code. |

So a strategy run mid-development produces tests for everything that's *intended*, clearly separates **built vs not-yet-built**, and — because the feature index is cached and cheap to re-run — the status simply **flips to `IMPLEMENTED`** as code lands, without redoing the whole strategy. This is what makes the tool useful *during* a sprint, not only after it.

---

## 2. Extract-once pipeline → normalised `EvidenceUnit`

One new record is the spine of everything:

```java
// new: ca.bnc.qe.veritas.evidence.EvidenceUnit
record EvidenceUnit(
    String id,         // stable, citable: "JIRA-1012", "CONF-A#auth-flow-3", "CODE:AuthController.login"
    SourceKind source, // JIRA | CONFLUENCE | CODE
    UnitType type,     // REQUIREMENT | ACCEPTANCE_CRITERIA | BUSINESS_RULE | DESIGN | ENDPOINT | DTO_CONSTRAINT
    String title,      // one-line label for UI + citation
    String text,       // normalised content (markdown, already trimmed)
    String link,       // deep link back to Jira issue / Confluence page#anchor / repo path#class
    Set<String> hints  // cheap deterministic clustering signals: labels, components, endpoint path-nouns
) {}
```

Adapters reuse what's already shipped — this is a thin layer, **not a rewrite, $0 LLM**:

- **Jira** — `IngestService.fromJira` already yields `TestBasisItem` via `AdfToMarkdown` + `TestBasisExtractor`. The adapter adds `link` (issue URL) and `hints` (issue **labels + components** — requires widening `JIRA_FIELDS` to request them).
- **Confluence** — `IngestService.fromConfluence` + `ConfluenceStorageToMarkdown` + `TestBasisExtractor` already emit **section-anchored ids** (`page#section-n`). The adapter sets `type=DESIGN`; the page *kind* (architecture/technical/functional/business) rides along as a `hint` taken from the page title — no classifier needed. Multi-page: the user selects N page ids; we iterate (already supported).
- **Code** — `JavaSpringExtractor.extract` → `ApiModel`. The adapter emits one `ENDPOINT` unit per endpoint (`id="CODE:Controller.method"`, `hints` = path-nouns like `login`) and one `DTO_CONSTRAINT` per constrained DTO.

A new `EvidenceExtractor` orchestrates the three adapters → `List<EvidenceUnit>`.

---

## 3. Feature index — the cheap + consistent core

Cluster every `EvidenceUnit` under a **feature** (`login`, `transfer`) so per-section generation can pull *only* that feature's units.

| Approach | Consistency | Cost | Verdict |
|---|---|---|---|
| Pure deterministic (keyword/label/path-noun overlap) | high but brittle — "login" (Jira) vs "authentication" (code) never join | $0 | necessary, not sufficient alone |
| **Deterministic seed → one cheap-LLM canonicalisation pass** | high — one call sees all unit *titles* at once → globally consistent feature names | ~1 ECONOMY call | **chosen** |
| Embeddings / vector store | best semantic recall | — | **rejected** — Veritas is LLM-only; don't add a vector store |

**How:**

1. **`FeatureSeeder` ($0)** — bucket units by shared `hints` (Jira components/labels, Confluence section slugs, code path-nouns). Rough buckets that also shrink what the LLM sees.
2. **`FeatureTagger` (one ECONOMY call)** — send only `{id, title, hint}` per unit (**never full text**) + the seed buckets; ask for JSON `{featureName: [unitIds]}` with a closed instruction: *"merge synonyms (login/auth/sign-in → one feature), every unit belongs to exactly one feature, JSON only."* Reuses `PromptComposer` + `JsonBlockExtractor` + `ResponseSchemaValidator` exactly like `generateSection`.

**Output — `FeatureIndex`**, persisted as JSON on the strategy row and **SHA-256-keyed for cache**:

```java
record FeatureIndex(Map<String, List<String>> featureToUnitIds,
                    Map<String, EvidenceUnit> unitsById,
                    SourceMix mix) {}
```

Re-running with unchanged sources is a cache hit → **$0 re-tag** (reuses the existing `PromptCache` pattern).

### 3.1 Per-feature status (drives §1.2)

Computed **deterministically** from the index, no LLM: for each feature, look at which `SourceKind`s its units span → `PLANNED` (no `CODE` unit) / `IMPLEMENTED` (`CODE` + at least one of Jira/Confluence) / `UNDOCUMENTED` (`CODE` only). Stored on the `FeatureIndex` and rendered in the UI preview and the why-doc.

---

## 3b. Retrieve-per-section

Keep the per-section generator (`TestStrategyService.SECTIONS`); change **what basis each section sees**. Today `generateSection` feeds the *entire* basis to every section; instead:

- **Feature-scoped sections** (`riskRegister`, `testApproach`): generate **per feature**, pulling only `featureIndex.unitsFor("login")` (~8 units, ~1.5k tokens) — not all 120 (~24k).
- **Global sections** (`summary`, `scope`, `exitCriteria`): see a **digest** — feature names, per-feature status, unit counts/titles (no full text), ~800 tokens.

A new `EvidenceRetriever.forSection(SectionSpec, feature, FeatureIndex)` returns the trimmed `TEST_BASIS` block; the rest of the `promptComposer.data("TEST_BASIS", …)` path is unchanged. **Cheap** because each call is small and feature-local; **consistent** because every call reads the same cached index — "login" means the same units in the risk register and the test approach.

---

## 4. Evidence-first golden rule — enforced structurally

Before writing a section the model must emit the evidence it relied on (citing unit ids), then the section, **constrained to only cited units.** Three enforcement layers so it can't be skipped:

**(a) Schema makes evidence a required sibling of content** (`test-strategy.schema.json`):

```json
{ "login": {
    "evidence": [ {"unitId":"JIRA-1012","why":"defines the lockout rule"},
                  {"unitId":"CODE:AuthController.login","why":"the endpoint under test"} ],
    "content": { "...the actual section..." } } }
```

`evidence` is `required`, `minItems: 1`. The existing `ResponseSchemaValidator.validate` **rejects an evidence-less section** → it's dropped/regenerated. Empty evidence is structurally impossible to ship.

**(b) Closed-world prompt contract** in `generateSection`:

> You may cite ONLY these unit ids: `[JIRA-1012, CONF-A#auth-3, CODE:AuthController.login]`. First output `evidence[]` (the ids you used + one-line why), then `content` using ONLY facts traceable to those ids. If a claim has no citable unit, do not make it.

Because retrieval (§3b) already fed only the feature's units, the allowed-id list **is** the retrieved set — the model literally cannot cite outside its evidence.

**(c) Deterministic `CitationValidator` ($0)** — every `unitId` in `evidence[]` and every risk's `citation` must exist in the `FeatureIndex`; hallucinated ids (`JIRA-9999`) → section rejected and regenerated once.

This ordering (evidence-first JSON, validated) gives mandatory traceability **without a second LLM pass**.

---

## 5. The "why" document

Assembled **deterministically** from the `evidence[]` blocks captured in §4 — **no extra LLM call** — reusing the existing rationale-rendering pattern (`StrategyRationaleRenderer` + `IstqbGlossary` deterministic concept lookup; *confirm current path before building*). `WhyDocRenderer.render(deliverable, featureIndex)` walks each section's evidence and emits, per section:

```
## Risk register — feature: login  [IMPLEMENTED]
WHY: Lockout risk raised because —
  • JIRA-1012 (REQUIREMENT) "account locks after 5 failed attempts"  → [link]
  • CODE:AuthController.login (ENDPOINT) — no rate-limit annotation found → [link]
  • CONF-A#auth-3 (DESIGN) "lockout is a security control"            → [link]
GAP: Jira requires lockout; code shows no enforcement → highest-value risk.
THEN: <the generated section content>
```

Every bullet is a real `EvidenceUnit` with a clickable `link` (full traceability), plus the section's ISTQB concept via the glossary. Stored as a sibling markdown/HTML doc next to `deliverableJson` on the `TestStrategy` entity.

---

## 6. UI flow (web dashboard)

A single wizard — "extract everything first, then select":

1. **Connect sources** — checkboxes Code / Jira / Confluence; the chosen subset drives `SourceMix`.
2. **Jira → epic-first** — enter project, list epics, pick **one or more epics**; fetch all child issues (`epicLink in (EPIC-1,EPIC-2)`); **multi-select** the issues to include.
3. **Confluence** — list/paste page ids (titles shown, so the user sees architecture/functional/business pages); multi-select.
4. **Code** — confirm repo path/branch.
5. **Extract** (deterministic, $0) → `EvidenceExtractor`, then the one cheap `FeatureTagger` call → show a **Feature-Index preview**: `feature → [unit chips by source] · status (PLANNED/IMPLEMENTED/UNDOCUMENTED)`. The user can **merge / rename / move** units between features *before any spend* — manual edits override the LLM map and are cached.
6. **Generate** → per-feature, per-section synthesis (§3b) + evidence-first (§4) + why-doc (§5). Show a **cost preview** (`CostEstimator`) before committing.

The preview/edit step is the consistency insurance: the user fixes any mis-clustering once, cheaply, before any STANDARD/DEEP token is spent.

---

## 7. Token budget (≈120-unit release)

| Stage | Tier | Tokens | Cost |
|---|---|---|---|
| Extraction (all 3 sources) | deterministic | 0 | $0 |
| Feature tagging (titles only) | ECONOMY | ~3.8k | ~$0.002 |
| `summary` / `scope` / `exitCriteria` (digest) | ECONOMY | ~4.5k | ~$0.003 |
| `testApproach` per feature (~6×2k) | STANDARD | ~12k | ~$0.04 |
| `riskRegister` per feature (~6×2.5k) | **DEEP** | ~15k | ~$0.15 |
| `selfReview` (digest) | STANDARD | ~3k | ~$0.01 |
| **Total per fresh run** | — | **~38k** | **~$0.21** |

vs today's "whole basis into every section" ≈ **144k input tokens** (DEEP risk-register sees the full corpus). Feature-scoping is a **3–4× input-token cut** on the load-bearing tiers. **Re-runs are near-free** (SHA-keyed `FeatureIndex` + `PromptCache`; `regenerateSection` redoes one feature's one section). Tier discipline holds: deterministic does extract/seed/status/validate/why-doc; ECONOMY does tagging + light sections; **STANDARD/DEEP only on the genuinely load-bearing synthesis** — the existing `SECTIONS` tier map, unchanged.

---

## 8. Build plan (each phase shippable; mostly reuse)

1. **Evidence model** — new `evidence` package: `EvidenceUnit`, `SourceKind`, `UnitType`, `EvidenceExtractor` (three adapters over the **existing** `JavaSpringExtractor`, `IngestService`, `TestBasisExtractor`); widen `JIRA_FIELDS` to include `labels,components`; `BasisBuilder.fromEvidence(...)`. No behaviour change.
2. **Multi-source blending** — replace the binary code-or-Jira routing (`TestStrategyCommand`) with `SourceMix`; all four combinations + code-only work; `EvidenceExtractor` runs whichever sources are present.
3. **Feature index** — `FeatureSeeder` ($0) + `FeatureTagger` (one ECONOMY call) + cached `FeatureIndex` + the deterministic per-feature **status** (§3.1).
4. **Evidence-first synthesis** — extend `test-strategy.schema.json` with required `evidence[]`; add `EvidenceRetriever.forSection` + `CitationValidator`; rewrite the `generateSection` contract to feed feature-scoped units + the closed-id list; thread the PLANNED/IMPLEMENTED status into the prompt so pending tests are marked.
5. **Why-doc + wizard UI** — `WhyDocRenderer` (deterministic) + the source-selection/preview wizard (§6).
6. **(Optional, demand-driven)** real Jira `epicLink in (…)` epic-child query and Confluence `getChildren(pageId, maxDepth)` for deep spaces — only if the explicit-page-id / manual-issue path hits a ceiling.

**Genuinely new pieces:** `EvidenceUnit` model + `EvidenceExtractor`; `FeatureSeeder` + `FeatureTagger` + `FeatureIndex` (+ status); `EvidenceRetriever` + `CitationValidator`; `WhyDocRenderer`; the wizard UI. Everything else — the per-section synthesis engine, cost routing, prompt composition, injection defence, caching, versioning, schema validation — is **already shipped and reused as-is**.

---

## Open questions / caveats

- **Jira epic-child query and Confluence tree-walk don't exist yet.** The wizard works with explicitly-selected issue/page ids until Phase 6 fills them (see [`ingestion-jira-confluence.md`](ingestion-jira-confluence.md)).
- **`StrategyRationaleRenderer` / `IstqbGlossary` path** must be confirmed before Phase 5 (the *pattern* is reused; the exact location wasn't pinned during design).
- **Feature granularity** — the cheap-LLM canonicalisation decides how coarse "features" are. The preview/edit step (§6) is the human override; if mis-grained, tighten the tagger prompt rather than re-architecting.
- **`PLANNED` tests** are designed from intent and may need revision once code lands — that's expected; a cached re-run flips the status and lets the user regenerate just that feature's affected sections cheaply.
