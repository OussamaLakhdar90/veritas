# Multi-source test-strategy pipeline (design)

**Status:** proposed (revised after design review) · **Audience:** Veritas engineers + QE leads · **Supersedes the source-routing half of** [`test-strategy-design.md`](test-strategy-design.md) · **Builds on** [`ingestion-jira-confluence.md`](ingestion-jira-confluence.md).

> **Revision note.** This version closes the gaps from the multi-source design review. Two framing corrections run through it: (1) wherever a class is genuinely *reused*, it is named as such; wherever the change is **new control-flow or a model change**, it is called out as new work (the "Build plan" in §8 is the authority on what is new). (2) Consistency and "$0 re-runs" are no longer asserted as free side-effects — each is backed by a concrete mechanism (a per-feature **facts card**, schema-enforced validation, and a defined **source digest**).

## Why

Today the test-strategy generator takes **one** basis (code **or** Jira **or** Confluence) and feeds the whole thing to every section. We want it to:

- accept **any combination** of code / Jira / Confluence — including **code-only**, and the **pre-dev** case (no code yet);
- work while **development is in progress** — code shows what *exists*, the other sources supply the *not-yet-built* pieces, so the strategy covers the whole intended feature set, not just what's coded so far;
- be **cheap token-wise** and **consistent** run-to-run, while giving the model all the material it needs;
- be **evidence-first** — every section is written *from* cited source material, and a companion "why" document records that traceability;
- still carry the **standards-driven, senior-QE content** (security, performance, resilience, compliance) that has no ticket or line of code behind it — see §4 "Standards & mandatory coverage", the fix for evidence-first's biggest risk.

Two product decisions are fixed (confirmed with the team):

- **Scale is large** (hundreds of Jira issues under the selected epics, big Confluence spaces) → we **never feed the whole corpus to the model**.
- **Features are derived from existing structure first** (Jira epics/components/labels, Confluence page titles, code packages) → the clustering is **deterministic-first**, with a cheap LLM only to canonicalise.

## The one idea

> **Extract everything deterministically ($0), index it by *feature* once (one cheap, chunked, completeness-checked pass), then each strategy section pulls only its feature's slice — and writes from a deterministic per-feature *facts card* so independent section calls stay mutually consistent.**

We **extract** everything (deterministic, free) but **feed** each LLM call only the one feature's evidence. The model has everything it needs for the section it's writing; it never sees the whole corpus at once.

Consistency is **not** claimed to fall out of "every call reads the same index" — independent section calls are stochastic and could still disagree. It comes from two explicit mechanisms: (a) a deterministic **facts card** per feature (the canonical unit ids, statuses, and key constraints) injected verbatim into every section prompt for that feature, so "login" carries the same hard facts into the risk register and the test approach; and (b) **schema-enforced, citation-validated** output so a section can't silently invent a fact a sibling section contradicts. `selfReview` (concept retained, but modified per §8 to be feature/merge-aware and scorecard-gated, §5b) is additionally fed the generated sections' facts cards and asked to flag contradicting section pairs.

---

## 1. Source model & prioritisation

Three *evidence* source kinds, each authoritative for a different axis, plus a fourth **non-evidence** kind (`POLICY`, §4) that makes mandatory standards citable. The synthesis prompt is told *which source wins* when they disagree — that is one consistency lever; a deterministic `ConflictDetector` (§4) is the other.

| Source | Authoritative for | Do **not** trust it for |
|---|---|---|
| **Jira** (epics/issues) | business **intent & scope** — what we promised, acceptance criteria, what's in this release | implementation truth (issues drift from code) |
| **Confluence** | **design rationale & rules** — architecture, technical design, functional & business analysis | current scope (pages go stale) |
| **Code** (`JavaSpringExtractor`) | **implementation truth** — endpoints, DTOs, constraints, what actually exists | intent (code can't say what was *meant*) |
| **Policy** (pre-authored, §4) | **mandatory quality controls** — OWASP API, OSFI/PCI, ISO 25010, house rules | anything project-specific (it's generic by design) |

**Precedence rule, fed verbatim into every synthesis prompt:**

> Intent & scope: **Jira > Confluence > code**. Implementation truth: **code > Confluence > Jira**. When a risk arises from a *gap between* sources (Jira says X, code does Y, or Jira/Confluence specify something with no code yet), that gap is itself the highest-value risk — cite both unit ids. Mandatory-control claims cite **Policy** units.

A `SourceMix` record `{boolean code, jira, confluence}` is computed **from what actually fetched** (§1.3), not from what the user selected, and passed into the prompt so the model never claims something a missing source can't support. The evidence-first rule (§4) makes that **structural**, not just advisory.

### 1.1 Combination rules (any subset works)

- **Code only** — a *factual* strategy. Scope = the implemented endpoints; risk register is structural (validation gaps, error-path coverage, undocumented status codes) **plus** Policy-driven mandatory controls (§4). **No business-intent claims** (no Jira/Confluence units to cite). A first-class mode for legacy services with no docs.
- **Jira only** — intent-driven, no implementation claims. Scope straight from the selected issues. Good pre-dev.
- **Confluence only** — design/rules-driven; strong for risk identification, weak on scope (the UI warns).
- **Jira + Confluence (no code)** — the **pre-dev** case: intent (Jira) + rationale (Confluence). The strongest non-code combination.
- **All three** — full power, and where the *gap-risks* light up (deterministically — see §3.3, not by hoping the model notices): "feature X is *specified* (`JIRA-1012`), *designed* this way (`CONF-A#auth-3`), and *implemented* as these endpoints (`CODE:AuthController#POST /login`) — coverage gap here."

### 1.2 Development-in-progress (the "missing pieces" case)

When code is **partially built**, the strategy must still cover the *whole intended feature set* — the parts not yet coded come from Jira/Confluence. The per-feature implementation status is **two-axis**, not coverage-derived (the §3.1 status engine), so a "done but not built" item reads as a gap rather than as "planned":

| Lifecycle (Jira workflow state) | Code presence (per planned endpoint) | Status | What the strategy does |
|---|---|---|---|
| In-progress / To-do (not DESCOPED) | none of the feature's endpoints exist | `PLANNED` | Design tests from intent/design; mark **pending** ("blocked until implemented"). Generated at STANDARD, not DEEP (§7). |
| any active state | **every** planned endpoint has a `CODE` unit | `IMPLEMENTED` | Full coverage; gap-risks where intent ≠ implementation. |
| active state | **some** endpoints exist | `PARTIAL` | Implemented parts get full coverage; missing parts stay pending — the feature does **not** flip fully green on one code unit. |
| **Done / closed** in Jira | no `CODE` unit | `COVERAGE_GAP` | The highest-value RTM signal: "marked done, no implementation found." Raised as a citable gap-risk, not silently labelled planned. |
| `DESCOPED` (Won't-Do / rejected) | — | excluded | Dropped from scope so it doesn't inflate the plan. |
| Code exists, no Jira/Confluence | — | `UNDOCUMENTED` | Flag "implemented but unspecified — confirm intent / possible scope creep"; still write tests from the code. |

Because the feature index is cached and the **source digest** (§3) detects code landing, a code commit is a *deliberate, cheap re-tag* (not a free cache hit) that transitions `PLANNED`/`PARTIAL` → `IMPLEMENTED` and lets the user regenerate just that feature's affected sections (§3.2). This is what makes the tool useful *during* a sprint, not only after it.

### 1.3 Partial-source-fetch semantics (new)

Ingestion of hundreds of issues / many pages **will** partially fail (a single Confluence 500 on page 7 of 12 must not discard the Jira work). A new `EvidenceExtractor` contract:

- each Jira issue / Confluence page / code file is fetched in its **own** try/catch; a failure records a blind spot, not an aborted run;
- a `FetchProvenance{requested, fetched, failed}` record is captured per source and surfaced as a banner in the §6 preview and the why-doc;
- `SourceMix` is recomputed from **successes** — a source that fetched zero usable units is dropped from the mix (so the evidence-first guarantee can't be violated by a half-empty corpus that still claims `confluence=true`);
- a source the user **selected** but which fetched **nothing** is a hard-fail before any spend (clear error, no silent degrade).

---

## 2. Extract-once pipeline → normalised `EvidenceUnit`

One new record is the spine of everything. Its `id` must be **stable across source edits** — every downstream guarantee (cache, citations, overrides, the PLANNED→IMPLEMENTED flip, why-doc links) depends on it.

```java
// new: ca.bnc.qe.veritas.evidence.EvidenceUnit
record EvidenceUnit(
    String id,         // STABLE, content-derived, citable (see below)
    SourceKind source, // JIRA | CONFLUENCE | CODE | POLICY
    UnitType type,     // REQUIREMENT | ACCEPTANCE_CRITERIA | BUSINESS_RULE | DESIGN | ENDPOINT
                       //  | DTO_CONSTRAINT | GLOBAL_CAVEAT | STANDARD
    String title,      // one-line label for UI + citation
    String text,       // normalised, REDACTED content (markdown, already trimmed — see §2.1)
    String link,       // deep link back to Jira issue / Confluence page#anchor / repo path#class
    String lifecycle,  // Jira workflow state (TO_DO|IN_PROGRESS|DONE|DESCOPED…); null for non-Jira
    String priority,   // Jira priority; null otherwise
    Set<String> links, // related-unit ids (Jira "blocks"/"relates"); for traceability + status
    Set<String> hints  // cheap deterministic clustering signals: labels, components, endpoint path-nouns
) {}
```

**Content-derived ids (the spine fix).** Today `TestBasisExtractor` mints `sourceId#<slug>-<n>` with a counter that never resets per section and a 24-char slug truncation — inserting one line near the top renumbers everything below it, invalidating every cached id. New scheme:

```
JIRA-1012                                   (issue-level: the natural key, already stable)
CONF-A#<full-slug>-<base32(sha256(normText))[0..7]>   (section-level: survives reordering)
CODE:<Class>#<HTTP> <path>                  (endpoint-level: see below)
POLICY:<catalog>-<control>                  (standards: a fixed catalog key)
```

The section/unit hash is over the **normalized unit text**, so unchanged content keeps its id and only an *edited* unit re-mints. **Acceptance criterion:** a regression test that ingests a page, inserts a bullet at the top, re-ingests, and asserts every prior unit id survives.

**Code ids must be unique and derivable.** Today an `Endpoint` carries only the bare method name (`operationId`) and a file-path `SourceRef`; the declaring controller class is a transient extraction local, so two `login()` methods (or overloads) collide and silently overwrite in `Map<String, EvidenceUnit>`. Fix (new work, §8): add the controller **simple class name** to `Endpoint` at construction (the `TypeDeclaration` is in scope in `JavaSpringExtractor`), build the id `CODE:<Class>#<HTTP> <path>`, and have `CitationValidator` **reject duplicate ids** rather than overwrite.

Adapters reuse the existing ingest/extract layer to produce raw items — **but the per-kind id, lifecycle, links, redaction, and DTO/caveat emission below are new**:

- **Jira** — `IngestService.fromJira` already yields `TestBasisItem` via `AdfToMarkdown` + `TestBasisExtractor`. New: thread issue **status, priority, issue-links**, the **labels + components** hints (requires widening `JIRA_FIELDS` and read-side parsing in *both* edition clients — see §8), and the issue URL as `link`.
- **Confluence** — `IngestService.fromConfluence` + `ConfluenceStorageToMarkdown` + `TestBasisExtractor` already emit section-anchored ids; new content-hash suffix (above). The page *kind* (architecture/technical/functional/business) rides along as a `hint` from the page title. Multi-page: the user selects N page ids; we iterate.
- **Code** — `JavaSpringExtractor.extract` → `ApiModel`. New: one `ENDPOINT` unit per endpoint (`id="CODE:Class#HTTP path"`, `hints` = path-nouns), one `DTO_CONSTRAINT` per constrained DTO (a **new traversal** of `ApiModel.schemas()`), and — the global signal the code-basis builder (`BasisBuilder.fromRepo`) currently ignores (it lists only endpoint signatures and never surfaces `ApiModel.blindSpots()`; the contract-validation path *does* consume them) — one `GLOBAL_CAVEAT` unit per entry in `ApiModel.blindSpots()` (centralized-security, unresolved-type warnings). `GLOBAL_CAVEAT` units live in a `crossCutting` bucket force-injected into every feature's retrieval slice and closed-id set (§3b), so each feature's risk register keeps the "authz is centralized and invisible to static analysis" caveat the why-doc markets.

A new `EvidenceExtractor` orchestrates the adapters under the §1.3 fetch contract → `List<EvidenceUnit>` + `FetchProvenance`.

### 2.1 Data handling — redaction before evidence is built (new)

Developer-pasted Jira/Confluence content can contain PII or secrets, and the synthesis path feeds full `EvidenceUnit.text` to the LLM. `PromptComposer` only injection-defangs and budget-trims; `LogMasker` only touches log output — **neither redacts evidence text**. For a regulated bank this is approval-blocking, so a deterministic `Redactor` runs **inside the three adapters, before text reaches an `EvidenceUnit`**:

- patterns: PAN (Luhn-checked), email, government-id, bearer/JWT/API-key, IP/hostname;
- **never silent**: each hit increments a `redactionCount` and logs a blind spot;
- the totals ("N redactions across M units") surface in the §6 preview for QE attestation before any spend.

What the model is and isn't allowed to see is summarised in §4 "Data handling"; the redaction mechanism lives here, at ingestion.

---

## 3. Feature index — the cheap + consistent core

Cluster every `EvidenceUnit` under a **feature** (`login`, `transfer`) so per-section generation can pull *only* that feature's units.

| Approach | Consistency | Cost | Verdict |
|---|---|---|---|
| Pure deterministic (keyword/label/path-noun overlap) | high but brittle — "login" (Jira) vs "authentication" (code) never join | $0 | necessary, not sufficient alone |
| **Deterministic seed → cheap-LLM canonicalisation (chunked + completeness-checked)** | high — name-merge across buckets, every unit accounted for | ~1 ECONOMY call/bucket | **chosen** |
| Embeddings / vector store | best semantic recall | — | **rejected** — Veritas is LLM-only; don't add a vector store |

**How:**

1. **`FeatureSeeder` ($0)** — bucket units by shared `hints` (Jira components/labels, Confluence section slugs, code path-nouns). Degrades gracefully to title/path-noun overlap when labels/components are absent. Rough buckets that also shrink what the LLM sees and bound the tagger call.
2. **`FeatureTagger` (chunk-and-merge, one ECONOMY call *per seed bucket*)** — a single global call does **not** scale: `data()` trims to 16 000 chars and elides the *middle* of a long unit list, and `JsonBlockExtractor` has no truncation recovery, so beyond a few hundred units the middle silently vanishes. Instead: one ECONOMY call **per bucket** over `{id, title, hint}` (never full text), with a deterministic name-merge across buckets (synonyms login/auth/sign-in → one feature). Inputs are built via `untrusted()` (no 16k trim) with a pre-send token assert.
   **Completeness invariant (enforced, $0):** every `unitsById` id appears in **exactly one** feature *or* an explicit `unclustered` bucket; a non-empty `unclustered`/`unassignedUnitIds` set is surfaced fail-loud in the §6 preview (units never silently vanish).

**Output — `FeatureIndex`** (a **lineage-level** artifact, §8): the *machine-generated* index is shared across a strategy's versions and content-addressed by `sourceDigest`, so the base artifact is never re-serialized per revision. *User edits* (merge/rename/move) do **not** mutate it — they persist in a separate **override layer** (keyed `featureId+unitId`, below) re-applied over the shared index on read. Persisted as JSON and keyed for re-run by a **source digest** (below):

```java
record FeatureIndex(
    Map<String, Feature> features,        // featureId -> {displayName, unitIds, status}
    Map<String, EvidenceUnit> unitsById,
    Set<String> unassignedUnitIds,        // completeness escape hatch (must be empty or surfaced)
    SourceMix mix,
    String sourceDigest) {}               // see below
```

**Feature identity vs display name.** A feature is keyed by a **content-derived `featureId`** (a hash of its sorted core unit ids), distinct from the LLM-minted `displayName`. A re-run that renames "login" → "authentication" keeps the same `featureId`, so cached manual overrides (kept keyed by `featureId+unitId`) survive and are re-applied as a post-pass over every fresh tag.

**Source digest (the real re-run key).** `PromptCache` is an in-memory, per-process LRU keyed on *trimmed prompt text* — it is a same-process optimization only, not a cross-session "$0 re-run" guarantee. The persistent key is `sourceDigest` = SHA over the sorted per-source revision tokens (Jira `updated`, Confluence `version`, code blob/commit SHA). Equal digest → load the persisted `FeatureIndex` ($0, no LLM). Mismatch → re-tag. A code landing **changes** the digest, so the PLANNED→IMPLEMENTED transition is a *deliberate cheap re-tag*, not a cache hit.

### 3.1 Per-feature status engine (drives §1.2)

Computed **deterministically** from the index, no LLM, on **two axes**: (a) the Jira **lifecycle** of the feature's requirement units, and (b) **per-endpoint code presence**. The full enumeration matching the §1.2 table: `IMPLEMENTED` only when *every* planned endpoint of the feature has a `CODE` unit; `PARTIAL` when some do; `PLANNED` when an active-lifecycle feature has no endpoint built yet; `COVERAGE_GAP` when a feature's requirements are `DONE` but no `CODE` unit exists; `UNDOCUMENTED` for a `CODE`-only feature with no Jira/Confluence unit; `DESCOPED` (Won't-Do/rejected) features are excluded entirely. Status is stored on each `Feature` and rendered in the §6 preview and the why-doc.

**Zero-feature guard.** If extraction yields **zero features** (an empty or unparseable corpus), the run halts before any spend with a clear error — a guard beyond today's non-blank `Preflight` check; a vacuous strategy is never produced.

### 3.2 Incremental re-run (new)

"Status flips, regenerate just that feature" needs a real diff, not hand-waving:

- `FeatureIndex.diff(prior)` → `{addedUnits, removedUnits, movedUnits, statusTransitions}`, triggered when `sourceDigest` changes;
- a deterministic **diff → stale-sections** map (which features changed → which sections are now stale);
- override / id-shift carry-forward: overrides keyed by `featureId+unitId` survive; a citation to a **removed** unit is flagged stale (its section is marked for regeneration), never left dangling;
- only stale features' sections are regenerated (the cheap "surgical redo", §7) — wired through a feature/index-aware `regenerateSection`/`regenerateFeature` (§8), not the index-blind endpoint that exists today.

### 3.3 GapDetector — the headline gap-risk, made deterministic (new)

"Jira says X, code does Y" must **not** depend on the tagger happening to co-locate the units *and* the model happening to notice. A `GapDetector` runs after the `FeatureIndex`, before synthesis:

- (a) promote §3.1 statuses into explicit **presence-gap** candidates (`COVERAGE_GAP`, `UNDOCUMENTED`, `PLANNED`-with-acceptance-criteria) — nearly free;
- (b) a `$0` **cross-feature lexical near-match guard**: if a Jira `REQUIREMENT` and a `CODE` `ENDPOINT` look related but landed in *different* features, flag "possible mis-cluster" into the §6 preview (so the most-marketed output doesn't silently vanish on a synonym miss);
- (c) for `IMPLEMENTED` features, emit a `CONTRADICTION-CHECK-REQUIRED` marker that forces the risk-section prompt to **either** assert intent==implementation **or** emit a gap-risk citing both ids.

Note: the real `DiffEngine.diffCodeVsSpec` is reused **only** where a Confluence page embeds *literal OpenAPI* (it needs a structured `ApiModel` on both sides, which prose lacks). Prose-vs-code reconciliation is **LLM-judged-but-forced** (the (c) marker), not a deterministic structural diff — we don't over-claim.

---

## 3b. Retrieve-per-section

Keep the per-section generator's *concept* (`TestStrategyService.SECTIONS`), but this is **engine surgery, not a drop-in**: the flat `for (SectionSpec s : SECTIONS)` loop becomes a **feature × section** loop for feature-scoped sections, with a **feature-keyed merge** back into the flat deliverable arrays, plus matching edits to `renderStrategyMarkdown`, the rationale renderer, and `selfReview` (the three downstream consumers, §8). A new `generate(serviceName, FeatureIndex, source, owner)` replaces the single-`basisText` entry point.

- **Feature-scoped sections** (`riskRegister`, `testApproach`): generate **per feature**, pulling only that feature's units (~8 units, ~1.5k tokens) + the `crossCutting` bucket — not the whole corpus.
- **Global sections** (`summary`, `scope`, `exitCriteria`): see a **digest** — feature names, per-feature status, unit counts/titles (no full text), ~800 tokens.
- **Cross-cutting SectionSpecs** (new): test levels, quality characteristics, environment/data, regression, entry/exit — fed the **full digest** (not the 800-token one), so systemic concerns aren't fragmented across features.

A new `EvidenceRetriever.forSection(SectionSpec, feature, FeatureIndex)` returns the trimmed `TEST_BASIS` block. **Consistency anchor:** a deterministic **per-feature facts card** (canonical unit ids + statuses + key constraints) is derived once and injected verbatim into every section prompt for that feature, so independent stochastic calls share the same hard facts. (The old "the rest of the `TEST_BASIS` path is unchanged" claim is withdrawn — the merge and the facts card are new.)

---

## 4. Evidence-first golden rule — enforced structurally

Before writing a section the model emits the evidence it relied on (citing unit ids), then the section, **constrained to only cited units.**

**(a) Schema makes evidence a required sibling — on the *flat* shapes, per item** (`test-strategy-section.schema.json`, new). The deliverable stays the existing flat `{summary, scope, riskRegister[], testApproach{}, exitCriteria[], selfReview{}}` shape that `renderStrategyMarkdown`, `StrategyRationaleRenderer`, revise/regenerate, and `selfReview` all read — we do **not** introduce a feature-keyed top level (the earlier `{"login": {...}}` sketch would silently break those four consumers). Evidence attaches **per item**:

```json
{ "riskRegister": [
    { "feature": "login",
      "evidence": [ {"unitId":"JIRA-1012","quote":"account locks after 5 failed attempts","gloss":"defines the lockout rule"},
                    {"unitId":"CODE:AuthController#POST /login","quote":"no @RateLimiter present","gloss":"the endpoint under test"} ],
      "description": "…the actual risk…", "level": "HIGH", "citation": "OWASP API4" } ] }
```

**Control flow (new — the existing validator does *not* do this).** Today `ResponseSchemaValidator.validate` runs **once on the whole assembled deliverable and throws**, and a per-section parse failure silently returns `null`; the shipped schema requires only `markdown`. So required-evidence is *new* per-section machinery: validate each section against `test-strategy-section.schema.json` → run `CitationValidator` on it → on failure, **bounded regenerate-once** (reusing `regenerateSection`'s internal prompt build, not the public endpoint) → if it still fails, **drop the section** (preserving today's graceful degradation), never abort the whole run. (§4a is therefore listed as new work in §8; it is not "schema validation reused as-is".)

**(b) Closed-world prompt contract** in the section generator, with a **split** so mandatory content isn't strangled:

> You may cite ONLY these unit ids: `[…the retrieved feature slice + crossCutting + applicable POLICY ids…]`. First output `evidence[]` (id + verbatim `quote` ≤120 chars + one-line `gloss`), then `content` using ONLY facts traceable to those ids. Evidence-grounded claims cite JIRA/CONF/CODE; **mandatory-control claims cite POLICY**. If a claim has no citable unit, do not make it.

**(c) Deterministic `CitationValidator` ($0)** — every cited `unitId` must exist in the `FeatureIndex`; duplicate code ids are **rejected** (not overwritten); the normalized `quote` must be a **substring of `unitsById.get(id).text()`** (closes the "cite a real id, fabricate the claim" hole, since retrieval already narrows to ~8 ids so `minItems:1` alone is free padding); evidence entries not referenced in `content` are dropped. On a quote/citation miss the **adversarial retry** feeds back the failing `{unitId, quote}`, retries once, then drops the claim — recorded, never silent.

### 4 — Standards & mandatory coverage (the evidence-first risk fix)

A pure closed-world rule structurally **forbids** the security/performance/resilience/compliance risks (rate-limiting, PII-in-logs, BOLA, OSFI/PCI/data-residency) that have **no** Jira/Confluence/code unit — exactly the senior-QE content that distinguishes a strategy from a requirements echo, and which today's `generate-test-artifacts.prompt.md` already emits. Fix: a fourth `SourceKind = POLICY` of **deterministic, pre-authored** `STANDARD` units (OWASP API, OSFI, PCI, ISO 25010, house policy — $0, no LLM), seeded onto features by test-type / endpoint signal so standards become **citable**. "A POLICY-required control with no enforcing code unit" is itself emitted as a citable gap-risk.

### 4 — Same-axis conflict resolution (new — there is no existing dedup to "extend")

When two sources disagree on the *same* axis (Jira "lock after 5" vs Confluence "lock after 3"), precedence prose alone isn't auditable. A `$0` per-feature `ConflictDetector` emits a structured `CONFLICT` finding, the why-doc gets a `CONFLICT:` line, and §6 shows a **"pin source"** chip so the QE lead resolves it before spend. (No cross-source dedup pass exists today; this is net-new, not an extension.)

### 4 — Risk consolidation (so per-feature DEEP registers are globally rankable)

Fanning `riskRegister` into ~6 closed-world per-feature DEEP calls means "HIGH" means different things across features, and a systemic risk (shared session store, gateway, IdP) has no single feature's id set to cite. So per-feature risk passes produce **un-ranked candidates**, then **one** final DEEP **consolidation** call over the union of candidates + the whole-index digest + top-N feature texts dedups them, assigns **one global severity scale**, and emits systemic risks. This consolidation call is the **one sanctioned closed-world exception** — its allowed-ids are the full union — still guarded by `CitationValidator`.

### 4 — Data handling (what the model may see)

The model only ever sees **redacted** evidence text (§2.1) — never raw Jira/Confluence bodies — and, per the closed-world contract, only the feature-scoped + `crossCutting` + applicable `POLICY` ids it is allowed to cite. The §6 preview surfaces the `redactionCount` for QE attestation before any spend; the mechanism (deterministic `Redactor` in the three adapters) lives in §2.1.

---

## 5. The "why" document

Assembled **deterministically** from the `evidence[]` blocks captured in §4 — **no extra LLM call**. `WhyDocRenderer.render(deliverable, featureIndex)` walks each item's evidence and emits, per section:

```
## Risk register — feature: login  [IMPLEMENTED]
WHY: Lockout risk raised because —
  • JIRA-1012 (REQUIREMENT) "account locks after 5 failed attempts"  → [link]
  • CODE:AuthController#POST /login (ENDPOINT) — no rate-limit annotation found → [link]
  • CONF-A#auth-3 (DESIGN) "lockout is a security control"            → [link]
  • POLICY:owasp-api4 (STANDARD) "unrestricted resource consumption"  → [link]
GAP: Jira requires lockout; code shows no enforcement → highest-value risk.
CONFLICT: Jira "lock after 5" vs CONF-A "lock after 3" — pinned: Jira.
THEN: <the generated section content>
```

Reuse is scoped honestly: **`IstqbGlossary.explain`** supplies the ISTQB concept lookup (a genuine reuse); `StrategyRationaleRenderer` is a **structural reference** for the layout, not a base class to extend. The why-doc is a **lineage-level** artifact (§8), stored next to `deliverableJson` keyed by `lineageId`.

### 5b. Observability & independent evaluation (new)

The pipeline adds *more* silent-failure surfaces (mis-cluster, dropped section, rejected citation, partial fetch) yet today only a final row + `CostEntry` is persisted. Two additions:

- **Run record** — reuse the existing `SkillRun` / `RunStep` model to persist: realized `SourceMix`, `FetchProvenance`, unit/feature counts, per-status tally, failed/regenerated/dropped sections with rejected ids, cache hits, per-stage cost. Add a terminal **`DEGRADED`** status so the why-doc header can state "riskRegister(login) dropped after 1 regen."
- **`StrategyScorecard` ($0)** — the only quality signal today is `selfReview.confidence` (the model grading itself; cheap tiers rubber-stamp, as the code already notes). A deterministic scorecard checks: every `IMPLEMENTED` feature has a risk + an approach; every `PLANNED` feature has pending tests; every load-bearing unit is cited at least once; every exit criterion maps to a risk. `setConfidence` is made to **depend on** the scorecard, not just the self-grade.
- **`selfReview` drift check (new)** — beyond today's self-grade, `selfReview` is fed the generated sections' facts cards and asked to **flag contradicting section pairs** (e.g. an exit criterion that contradicts a risk's severity). This is the cross-section drift check "The one idea" refers to; flagged pairs feed the `DEGRADED`/scorecard signal, not a silent pass.

---

## 6. UI flow (web dashboard) — *extends* `TestStrategy.tsx`

A single wizard (an extension of the existing strategy page + `ui.tsx` component library, not greenfield):

1. **Connect sources** — checkboxes Code / Jira / Confluence; the chosen subset is the *requested* set (the realized `SourceMix` comes from §1.3 fetch results).
2. **Jira → epic-first** — enter project, list epics, pick **one or more epics**; fetch all child issues (paged); **multi-select** the issues.
3. **Confluence** — list/paste page ids (titles shown); multi-select.
4. **Code** — confirm repo path/branch.
5. **Extract** (deterministic, $0) → `EvidenceExtractor` (with redaction §2.1 + `FetchProvenance` §1.3), then the chunked `FeatureTagger` → show a **Feature-Index preview**: `feature → [unit chips by source] · status` plus three banners: **redactions** ("N redactions across M units"), **unassigned units** (must be zero or acknowledged), **fetch failures**. The user can **merge / rename / move** units between features and **pin a source** on any `CONFLICT` *before any spend* — manual edits override the LLM map, persist keyed by `featureId+unitId`, and survive re-tags.
6. **Generate** → per-feature, per-section synthesis (§3b) + evidence-first (§4) + consolidation + why-doc (§5). Show a **cost preview** (`CostEstimator`) before committing.

The preview/edit step is the consistency insurance: the user fixes mis-clustering, conflicts, and redaction surprises once, cheaply, before any STANDARD/DEEP token is spent.

---

## 7. Token budget (≈120-unit release)

> **The win is "feature-local, higher-signal calls + a cheaper global tier," not a 144k→38k collapse.** The old "~144k input / 3–4× cut" baseline was impossible: `data()` trims every block to 16 000 chars and `compose()` caps the prompt at ~60k tokens, so no section *ever* saw the full corpus. The honest gain is that the load-bearing tiers now read ~1.5k feature-local tokens instead of a 16k-trimmed blob, and the global tier is consolidated.

Per call carries a **~1k fixed scaffold** (template + output contract), itemized below rather than hidden; feature-scoped calls additionally inject the per-feature **facts card**, while global / cross-cutting calls carry the **digest** instead (so the scaffold isn't uniform across tiers).

| Stage | Tier | Tokens (in) | Cost |
|---|---|---|---|
| Extraction (all 3 sources) + redaction + seed + status + GapDetector | deterministic | 0 | $0 |
| Feature tagging — chunk-and-merge (~3 buckets × titles-only) | ECONOMY | ~4.5k | ~$0.003 |
| `summary` / `scope` / `exitCriteria` (digest) | ECONOMY | ~4.5k | ~$0.003 |
| `testApproach` per feature (~6 × (1.5k + 1k scaffold)) | STANDARD | ~15k | ~$0.05 |
| `riskRegister` per feature — **candidates**, un-ranked (~6 × ~2.5k) | STANDARD | ~15k | ~$0.05 |
| risk **consolidation** (union + digest + top-N) | **DEEP** | ~8k | ~$0.08 |
| cross-cutting SectionSpecs (full digest) | STANDARD | ~4k | ~$0.015 |
| `selfReview` + `StrategyScorecard` wiring | STANDARD | ~3k | ~$0.01 |
| **Total per fresh run** | — | **~54k** | **~$0.21** |
| Tagger re-run on an edit (one bucket) | ECONOMY | ~1.5k | ~$0.001 |
| Per-section regenerate-once retry (when triggered) | section tier | ~2.5k | ~$0.01 |

Notes: per-feature `riskRegister` is downgraded to **STANDARD candidates**; the single global-scale ranking is the one **DEEP** call (consolidation). **Re-runs** with an unchanged `sourceDigest` are a true $0 index load; a changed digest is a deliberate cheap re-tag. **Degenerate scale:** a doc-heavy / no-code space yields many `PLANNED`-only features — these route to **STANDARD + a single consolidated "Pending coverage" section** (not a DEEP register each), and feature count is **capped** (excess → flagged in §6), so a large-N space can't fan out into dozens of DEEP calls. (The earlier "cross-feature merge" budget line is removed — the only merge is the sanctioned consolidation pass above.)

---

## 8. Build plan (each phase shippable)

The framing rule: phases below mark **new** vs **reused**. Reused as-is: `IngestService`/`TestBasisExtractor` raw-item production, `IstqbGlossary.explain`, `CostEstimator`, `PromptComposer`/cost-routing/injection-defence, the dashboard component library. **Everything else named here is new or modified.**

1. **Evidence model** — new `evidence` package: `EvidenceUnit` (with content-hash ids, lifecycle/priority/links), `SourceKind` (incl. `POLICY`), `UnitType` (incl. `DTO_CONSTRAINT`, `GLOBAL_CAVEAT`, `STANDARD`), `EvidenceExtractor` (three adapters + §1.3 fetch contract + §2.1 `Redactor`); **`Endpoint` gains a controller class name** + the `CODE:<Class>#<HTTP> <path>` id; the `DTO_CONSTRAINT` schema traversal of `ApiModel.schemas()`; `GLOBAL_CAVEAT` emission from `ApiModel.blindSpots()`. **Jira widening is multi-file**, not a constant edit: extend `JiraIssue` (status/priority/components/labels/links/url), parse them in **both** edition clients (v3/ADF nested-name components vs v2/wiki), fix the two hardcoded `?fields=summary,description` lists, add a Cloud + Server round-trip fixture test.
2. **Multi-source blending** — replace binary code-or-Jira routing with `SourceMix` computed from `FetchProvenance`; all four combinations + code-only; the §1.3 partial-fetch + zero-fetch-hard-fail semantics.
3. **Feature index** — `FeatureSeeder` ($0) + chunked `FeatureTagger` (per-bucket, completeness invariant, `unassignedUnitIds`) + `featureId`-vs-`displayName` + the **`sourceDigest`** re-run key + the **two-axis status engine** (§3.1) + `GapDetector` (§3.3). `FeatureIndex` is a **lineage-level** artifact (new `featureIndexJson` column keyed by `lineageId`, reference-copied on edit) + a `whyDoc` column.
4. **Evidence-first synthesis** — `test-strategy-section.schema.json` (per-item `feature`+`evidence[]` with verbatim `quote`); `EvidenceRetriever.forSection` + per-feature **facts card**; the **new control flow** (per-section validate → per-section `CitationValidator` → bounded regenerate-once → drop-on-fail, never whole-run abort); the closed-world split contract incl. `POLICY`; the `ConflictDetector`; the risk **consolidation** pass; thread PLANNED/PARTIAL/COVERAGE_GAP status into prompts. Rewrite `generate()` into `generate(serviceName, FeatureIndex, source, owner)` (feature×section loop + feature-keyed merge) and update the three consumers (`renderStrategyMarkdown`, `StrategyRationaleRenderer`, `selfReview`). Extend `regenerateSection` (or add `regenerateFeature`) to be feature/index-aware.
5. **Wizard + observability** — this splits into two real slices, the **backend** being the larger:
   - **5a backend** — wizard endpoints (epics list, epic-children paged query, extract+preview, **feature-index edit-persist with cache invalidation**, cost-preview); the **override store** layered over the shared `FeatureIndex` (§3); the `SkillRun`/`RunStep` run record + `DEGRADED` status + `StrategyScorecard` (§5b); `FeatureIndex.diff` + incremental re-run (§3.2).
   - **5b frontend** — extend `TestStrategy.tsx` with the source-selection / preview / merge-rename-move / pin-source / banners flow (§6).
6. **(Optional, demand-driven)** real Jira `epicLink in (…)` epic-child query and Confluence `getChildren(pageId, maxDepth)` for deep spaces — these route through the **already-paged** `JiraClient.search` / Confluence fetch (the companion doc's paging *is* implemented), so they inherit paging; build only if the explicit-id path hits a ceiling.

**Genuinely new pieces (the §8 inventory is the authority):** `EvidenceUnit`/`SourceKind`/`UnitType`/`EvidenceExtractor`/`Redactor`/`FetchProvenance`; the `Endpoint` class-name + code-id change; `FeatureSeeder`/`FeatureTagger`/`FeatureIndex` (+ `featureId`, `sourceDigest`, two-axis status, lineage persistence); `GapDetector`; `EvidenceRetriever` + facts card; `test-strategy-section.schema.json` + per-section validate/regen/drop control flow; `CitationValidator` (existence + quote-substring + dup-reject); `ConflictDetector`; the risk consolidation pass; `WhyDocRenderer`; `StrategyScorecard` + run record + `DEGRADED`; the wizard + the mutable persisted index. Reused: the raw ingest/extract layer, `IstqbGlossary.explain`, `CostEstimator`, prompt composition/cost-routing/caching/versioning/schema-validation *infrastructure*, the dashboard component library.

---

## Open questions / caveats

- **Jira epic-child query and Confluence tree-walk don't exist yet.** The wizard works with explicitly-selected issue/page ids until Phase 6 (see [`ingestion-jira-confluence.md`](ingestion-jira-confluence.md)); both route through the already-paged search/fetch.
- **Feature granularity** — the cheap-LLM canonicalisation decides coarseness. The §6 preview/edit step is the human override; if mis-grained, tighten the tagger prompt rather than re-architecting. The decomposition spine stays **feature** (not test level) — the fixes above are additive (POLICY units + cross-cutting SectionSpecs + consolidation), not a re-spine.
- **`PLANNED` tests** are designed from intent and may need revision once code lands — expected; a digest-triggered re-tag flips the status and regenerates just that feature's affected sections (§3.2).
- **`Redactor` is pattern-based**, so novel secret formats can slip; the non-silent `redactionCount` + the §6 attestation banner make residual risk visible rather than hidden, but redaction is defence-in-depth, not a guarantee.
- **Cross-cutting membership** stays single for `ENDPOINT`/`DTO_CONSTRAINT` units (no blanket multi-membership); only `GLOBAL_CAVEAT`/`STANDARD` units are force-injected into every feature slice.
