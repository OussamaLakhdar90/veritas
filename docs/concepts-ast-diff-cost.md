# How Veritas Works: AST Extraction, Comparison, and Cost

Veritas is governed by a single principle: **LLM only when needed**. Cloning a repository, extracting the API surface from source via AST analysis, parsing the OpenAPI/Swagger spec, diffing the two, and computing cost are all **deterministic Java** — no model in the loop, no guessing. The LLM is reserved for the work that genuinely needs judgment: human-readable explanations and design/test-basis reconciliation.

The keystone that makes this possible is the canonical `ApiModel` intermediate representation (IR). Both the code extractor and the spec extractor produce the *identical* IR — the same `Endpoint`, `ParamModel`, `ResponseModel`, and `SchemaModel` structures. Because both sides speak the same shape, the code-vs-spec diff is genuinely apples-to-apples: differences in the IR are real contract differences, not artifacts of two different parsers. Everything downstream — fidelity score, report, and the live progress stages — consumes these deterministic outputs.

## Table of Contents

1. [AST extraction — reading the API out of the code](#1-ast-extraction--reading-the-api-out-of-the-code)
2. [Comparison — the deterministic diff engine](#2-comparison--the-deterministic-diff-engine)
3. [Cost calculation — how LLM spend is estimated and tracked](#3-cost-calculation--how-llm-spend-is-estimated-and-tracked)
4. [How they connect](#4-how-they-connect)

## 1. AST extraction — reading the API out of the code

### 1.1 JavaParser + JavaSymbolSolver Setup

The extraction pipeline starts in `JavaSpringExtractor.extract()` (line 69) with a symbol-aware parser built by `symbolAwareParser()` (lines 227–238). The JavaParser is configured with `LanguageLevel.JAVA_21` and wrapped with a `JavaSymbolSolver` backed by a `CombinedTypeSolver` that chains:
- `ReflectionTypeSolver` for JDK types
- `JavaParserTypeSolver(sourceRoot)` over the cloned sources

If the symbol solver fails to initialize, parsing degrades gracefully to syntactic-only mode (line 236) — **no hard failure**. The solver is consulted during type resolution (e.g., `resolvesViaSolver()` at line 196) to confirm unresolved type references; if resolution fails, the extractor records a blind spot rather than guessing.

### 1.2 Controller Detection

A type is identified as a controller (lines 250–265) when:
1. **Direct annotation**: carries `@RestController` **OR** (`@Controller` AND `@ResponseBody`)
2. **Custom stereotype**: carries a custom annotation whose declaration (in scanned sources) is itself meta-annotated with `@RestController` or the combo above

The check handles Spring's meta-annotation semantics by walking the annotation declaration tree (line 256) — so composed annotations like a project's `@ApiV1Controller` are recognized. The type map (`types`, populated at line 71) ensures only declared types in the scanned sources are consulted; unresolved stereotypes are not silently dropped (line 254).

### 1.3 Endpoint Mapping

Each controller method's request mapping is resolved in `toEndpoints()` (lines 289–364). The logic fan-outs to produce all combinations of:
- **Base paths** from class-level `@RequestMapping` or composed meta-annotations (lines 267–282)
- **Method paths** from `@*Mapping` shortcuts (`@GetMapping`, `@PostMapping`, etc.) or composed method-level meta-annotations (lines 405–443)
- **HTTP verbs** extracted from method or meta-annotation (line 445: `verbsFrom()`)
- **Multi-path literals** resolved from annotation arrays or **constant references** (lines 459–498)

**Constant resolution** (line 92: `collectStringConstants()`) scans for static String fields across all types, storing them by simple name and owner-qualified name (e.g., `Routes.API_V1` → `/api/v1`). Path expressions in annotations are resolved via `resolvePathExpr()` (lines 481–498) — handles string literals, field accesses, and binary concatenation (+). If a constant or placeholder cannot be resolved, a blind spot is recorded (line 469) with the original expression text, never fabricated.

**Path normalization** (lines 1010–1019) strips regex constraints from placeholders (e.g., `{id:\d+}` → `{id}`) to match OpenAPI's canonical form.

**Inherited mappings** (lines 371–393) from abstract base classes are emitted using the subclass's path base and security context. If a base class is unresolved, a blind spot is recorded (lines 122–124) rather than silently omitting its endpoints.

### 1.4 Parameters

Method parameters are classified by annotation (lines 304–325):
- **@PathVariable** → `ParamLocation.PATH`, required=true by default
- **@RequestParam** → `ParamLocation.QUERY`, required based on `required=` or presence of `defaultValue=`
- **@RequestHeader** → `ParamLocation.HEADER`, required via `bindingRequired()` (line 584)
- **@CookieValue** → `ParamLocation.COOKIE`, required via `bindingRequired()`

**Composed param annotations** are recognized via `hasMeta()` (line 927) — checks both direct presence and meta-annotation on custom types in the sources. The parameter model (`ParamModel` at line 599) includes type, OpenAPI format, required flag, and a `ConstraintSet` derived from Bean Validation annotations (see below).

### 1.5 Return Types and Status Codes

Response types are unwrapped by `unwrap()` (lines 865–898) to extract the real body type behind wrappers:
- **Transparent wrappers** (`Optional`, `Mono`, `CompletableFuture`, `Callable`, `DeferredResult`, `Future`, `CompletionStage`): unwrapped to the inner type
- **Envelope wrappers** (`ApiResponse<T>`, `Result<T>`, `Response<T>`, etc.): unwrapped only if parameterized (generic); a bare `ApiResponse` is left as-is
- **Array wrappers** (`List<T>`, `Flux<T>`, `Page<T>`, `Slice<T>`): marked as array body with element type
- **Map/dictionary types** (`Map`, `HashMap`, etc.): returned as null body (free-form object, no schema ref)
- **Raw ResponseEntity/HttpEntity** without generics: marked as unknown body (line 888) — `noBody=false, responseEntity=true`, never synthesizes a phantom schema

**Status codes** are determined (lines 776–814):
1. If method carries explicit `@ResponseStatus(value=HttpStatus.X)`, use that code (line 781)
2. Else if return type is `ResponseEntity` with builder calls (`created()`, `accepted()`, `noContent()`, `status(...)`, etc.), extract codes from the method body (lines 791–814)
3. Else default to 200

The `responseEntityStatuses()` method searches for `ResponseEntity.<factory>(...)` calls (line 793), mapping factory names to HTTP status codes and resolving `HttpStatus.X` constants. When a method uses multiple status-code branches, all detected codes are emitted as separate `ResponseModel` responses (one per status).

### 1.6 Schemas (DTO fields and enums)

Referenced types are collected during endpoint extraction (line 319: `referenced.add()`) and built into schemas at lines 163–179. For each referenced type:
- **Enums** (`EnumDeclaration`): modeled as `type=string` with `enumValues` list (line 609), not `type=object`
- **Classes/Records**: fields extracted via `collectFields()` (lines 622–650), including inherited fields from superclasses present in the source set; subclass fields shadow superclass fields by name
- **Records**: parameters treated as fields (lines 628–634); `@JsonIgnore` excludes them
- **Static/transient fields** and `@JsonIgnore` fields are skipped (line 639) — they do not appear in the JSON contract
- **Collection fields** (`List<T>`, `Set<T>`, `Page<T>`, etc., incl. arrays `T[]`): modeled as `type=array` with `refSchema=T[]` (lines 667–670); the IR lets the corrected-YAML renderer emit `items` only for DTO elements
- **Enum-typed fields**: modeled as `type=string` with inline `enumValues` (line 674), not a phantom object ref
- **Map/dictionary bodies**: returned as null (line 894) — never emitted as a named schema

Field type mapping (`openApiType()`, lines 993–1008) handles primitives, wrappers, JDK temporal types (`LocalDate`, `Instant`, etc.), and `UUID` format.

**Unresolved types** (those referenced but not in scanned sources) are checked via the symbol solver (line 175: `resolvesViaSolver()`). If the solver confirms it is a JDK/library type, it is **not** a blind spot. Otherwise, a blind spot is recorded (line 176).

### 1.7 Bean Validation → ConstraintSet

Constraint annotations on fields and parameters are extracted via `constraintsOf()` (lines 698–727) and stored in `ConstraintSet`:
- **@Size(min=X, max=Y)** → `minLength`, `maxLength`
- **@Min/@Max(value=X)** → `minimum`, `maximum`
- **@Pattern(regexp=...)** → `pattern`
- **@Email** → `format=email`
- **@NotNull / @NotBlank / @NotEmpty** → captured via the field/param's `required` flag, NOT synthesized as minLength constraint (comment at line 702)

### 1.8 Error Responses from @ControllerAdvice

Methods annotated `@ExceptionHandler` on `@ControllerAdvice` or `@RestControllerAdvice` classes are extracted (lines 729–757) and their responses appended to every endpoint. Status is determined by `@ResponseStatus(value=HttpStatus.X)` or defaults to 500 (line 762). Duplicate status codes are suppressed (line 748).

### 1.9 Security

Security declarations are collected at both class level (line 102) and method level (line 350) from:
- **@PreAuthorize("expr")** → expression string
- **@Secured("role")** → role string
- **@RolesAllowed("role")** → role string
- **Custom composed annotations** meta-annotated with one of the above (lines 569–571)

The `securityOf()` method (lines 566–575) walks both literal annotations and the declarations of custom annotations in the scanned sources (via `types` map). Method-level security adds to class-level. The IR stores security as a list of role/scope/expression strings; empty list means unsecured.

**Centralized Spring Security** (`SecurityFilterChain`, `HttpSecurity`, `WebSecurityConfigurerAdapter` beans) is detected by searching for these class names in the sources (lines 242–247). When found, a blind spot is recorded (lines 143–145) to flag to the diff engine that per-endpoint authorization is enforced by URL pattern in configuration, not annotation — thus not visible to AST analysis.

### 1.10 Blind Spots (Honest Recording)

The IR model includes a `blindSpots` list (line 73 in `ApiModel`) that records unresolved or uncertain elements **instead of guessing**:
- Parse failures on source files (line 80)
- Unresolved type references after symbol-solver consultation (line 176)
- Unresolved path expressions (line 469)
- Property placeholders in paths (line 471)
- Unresolved/missing base class mappings (lines 122–124)
- Implemented interfaces with unanalyzed mapped methods (lines 131–134)
- Centralized authorization configuration (line 143)

### 1.11 OpenAPI Extractor → Same IR

The `OpenApiModelExtractor` (lines 36–75) reads OpenAPI 3.x or Swagger 2.0 specs (auto-detected and 2.0→3.x converted) into the **identical** `ApiModel` structure. It uses the same `Endpoint`, `ParamModel`, `ResponseModel`, `SchemaModel`, and `SourceRef` classes. The key difference:
- Code extractor: `source="code"`, `SourceRef` carries file + line numbers + snippet
- Spec extractor: `source=specId`, `SourceRef.spec()` stores JSON-pointer location + fragment
- Both populate the same endpoint/parameter/response/schema model fields, enabling apples-to-apples diff

The parser deliberately does **not** use `setResolveFully(true)` (comment at line 42) because full resolution inlines all `$ref` values, losing DTO names and producing false schema mismatches. Instead, plain `resolve()` preserves component references (line 40).

## 2. Comparison — the deterministic diff engine

The DiffEngine (`src/main/java/ca/bnc/qe/veritas/engine/diff/DiffEngine.java`) is the core of Pillar A: a **100% deterministic** comparison engine with zero LLM involvement. It operates on two canonical `ApiModel` instances (extracted code IR vs parsed spec IR) and produces deterministic findings grouped across layers L1–L4. Code is the source of truth for behavior. (L5/L6 design findings are added later by LLM reconciliation.)

### 2.1 Core Architecture & Invocation

The DiffEngine receives invocations from `ContractValidationService` (lines 158, 161, 167):
- `diffEngine.l1FromMessages(s.id(), parse.messages())` — parser structural errors (line 158)
- `diffEngine.diffCodeVsSpec(code, parse.model())` — code vs each spec (line 161)
- `diffEngine.diffSpecVsSpec(specModels.get(i), specModels.get(j))` — repo YAML vs Confluence YAML (line 167)

### 2.2 Deterministic Path Normalization & Endpoint Matching

All path normalization is applied **identically** to both code and spec sides, ensuring that equivalent contracts yield zero findings.

The normalization function (`static String normPath(String path)`, lines 453–462):
1. Converts to lowercase (`Locale.ROOT`)
2. Replaces all path-variable expressions `{varName}` with the uniform token `{}`
3. Removes trailing slash (if length > 1)
4. Returns `/` for empty paths

Example: `GET /users/{userId}/posts/{postId}` and `get /Users/{id}/Posts/{pid}` both normalize to `get /users/{}/posts/{}`.

Endpoint matching is by **normalized path + HTTP method** (the `key()` function, lines 445–447; indexing at lines 433–443). When a code endpoint is not found by exact key match, the engine checks for path-only matches (line 74) to detect `VERB_MISMATCH` before labeling as `MISSING_ENDPOINT`.

### 2.3 Complete Finding Taxonomy

The `FindingType` enum (`src/main/java/ca/bnc/qe/veritas/finding/FindingType.java`) defines **24 finding types**:

**L1 — Structural** (lines 6–8):
- `OPENAPI_PARSE_ERROR` — parser failure
- `UNRESOLVED_REF` — unresolved `$ref`
- `MISSING_INFO_FIELD` — spec lacks `info.title` or `info.version` (emitted as separate findings)

**L2/L3 — Coverage** (lines 10–11):
- `MISSING_ENDPOINT` — in code, not in spec
- `EXTRA_ENDPOINT` — in spec, not in code (dead spec)
- `SPEC_DRIFT` — endpoint-set differences between two specs

**L4 — Signature & Constraints** (lines 13–29):
- `VERB_MISMATCH` — different HTTP method for the same path
- `PATH_VAR_NAME_MISMATCH` — path variable names differ
- `PARAM_MISSING`, `PARAM_EXTRA` — parameter presence
- `PARAM_TYPE_MISMATCH`, `PARAM_REQUIRED_MISMATCH` — parameter type or required flag
- `REQUEST_BODY_PRESENCE_MISMATCH` — body present/absent divergence
- `STATUS_CODE_MISSING`, `STATUS_CODE_EXTRA` — response code coverage
- `RESPONSE_SCHEMA_MISMATCH` — success-response schema ref differs
- `SCHEMA_FIELD_MISSING`, `SCHEMA_FIELD_EXTRA`, `SCHEMA_FIELD_TYPE_MISMATCH` — field-level schema drift
- `CONSUMES_PRODUCES_MISMATCH` — media type divergence
- `CONSTRAINT_GAP` — code constraints not exposed in spec (min/max/len/pattern/enum)
- `SECURITY_MISMATCH` — authz presence/absence divergence

**L5/L6 — LLM Judgment** (lines 33–34):
- `DESIGN_QUALITY` — REST design violations (LLM, added by `ContractValidationService.parseDesignFindings()`)
- `TEST_BASIS_GAP` — test-basis adequacy (LLM, added by `ContractValidationService.parseDesignFindings()`)

### 2.4 Layer Mapping (Deterministic)

The `layerOf(FindingType)` switch in DiffEngine (lines 413–419) assigns layers mechanically:

| Layer | FindingTypes |
|-------|---|
| **L1** | `OPENAPI_PARSE_ERROR`, `UNRESOLVED_REF`, `MISSING_INFO_FIELD` |
| **L2** | `MISSING_ENDPOINT` |
| **L3** | `EXTRA_ENDPOINT`, `SPEC_DRIFT` |
| **L4** | All others (signature + constraints) |

L1–L4 are **mechanical** (pure syntax/structure), produced by DiffEngine. L5/L6 are **LLM-driven** — `DESIGN_QUALITY` and `TEST_BASIS_GAP` are created explicitly with layers L5/L6 by `ContractValidationService.parseDesignFindings()` (lines 407–430), not via `layerOf()`.

### 2.5 Severity Assignment (Deterministic)

The `severityOf(FindingType)` switch (lines 422–431):

| Severity | FindingTypes |
|----------|---|
| **CRITICAL** | `OPENAPI_PARSE_ERROR`, `MISSING_ENDPOINT`, `VERB_MISMATCH` |
| **MAJOR** | `EXTRA_ENDPOINT`, `PATH_VAR_NAME_MISMATCH`, `PARAM_MISSING`, `PARAM_TYPE_MISMATCH`, `PARAM_REQUIRED_MISMATCH`, `REQUEST_BODY_PRESENCE_MISMATCH`, `STATUS_CODE_MISSING`, `RESPONSE_SCHEMA_MISMATCH`, `SCHEMA_FIELD_MISSING`, `SCHEMA_FIELD_TYPE_MISMATCH`, `CONSTRAINT_GAP`, `SECURITY_MISMATCH`, `UNRESOLVED_REF`, `SPEC_DRIFT` |
| **MINOR** | All others (e.g., `STATUS_CODE_EXTRA`, `CONSUMES_PRODUCES_MISMATCH`, `MISSING_INFO_FIELD`, `PARAM_EXTRA`) |

### 2.6 Confidence Assignment

Confidence is assigned per finding type and evidence quality:

- **HIGH**: Endpoint-level structural differences (`MISSING_ENDPOINT`, `VERB_MISMATCH`, `PATH_VAR_NAME_MISMATCH`, `REQUEST_BODY_PRESENCE_MISMATCH`, `PARAM_MISSING`, `SECURITY_MISMATCH` when code enforces auth but spec doesn't, parser errors (`OPENAPI_PARSE_ERROR`, `UNRESOLVED_REF`), `SPEC_DRIFT`, `SCHEMA_FIELD_MISSING`)
- **MEDIUM**: Signature mismatches (`PARAM_TYPE_MISMATCH` with both sides declaring type, `PARAM_REQUIRED_MISMATCH`, `CONSTRAINT_GAP`, `RESPONSE_SCHEMA_MISMATCH`, `STATUS_CODE_MISSING`, `SCHEMA_FIELD_TYPE_MISMATCH`, `CONSUMES_PRODUCES_MISMATCH`, `EXTRA_ENDPOINT`, `PARAM_EXTRA`, `MISSING_INFO_FIELD`, `SECURITY_MISMATCH` when spec requires auth but code doesn't)
- **LOW**: Under-specification gaps (`PARAM_TYPE_MISMATCH` when spec omits type, `STATUS_CODE_EXTRA`, `SCHEMA_FIELD_EXTRA`)

### 2.7 Parameter Comparison (By Location + Name)

Parameters are matched using a composite key (`paramKey()`, lines 92–95):

```
key = location + ":" + name
```

Example: a query parameter named `id` never matches a path parameter named `id` — they are distinct param objects. The location enum is `ParamLocation` (PATH, QUERY, HEADER, COOKIE, FORM).

When both code and spec declare a parameter, comparison covers:
- **Type** (line 171): `cp.type()` vs `sp.type()`, flagging `PARAM_TYPE_MISMATCH`
- **Type-null handling** (lines 175–179): If spec omits type but code declares it, emit `PARAM_TYPE_MISMATCH` at LOW confidence (under-specification)
- **Required flag** (lines 181–184): `cp.required()` vs `sp.required()`, flagging `PARAM_REQUIRED_MISMATCH`
- **Constraints** (lines 186–197): Compare `cp.constraints()` vs `sp.constraints()` using `constraintMismatchDesc()`

### 2.8 Constraint Value-Level Comparison

Constraints are compared by `constraintMismatchDesc()` (lines 313–336). Each `ConstraintSet` carries:
- `minLength()`, `maxLength()`, `minimum()`, `maximum()`, `pattern()`, `enumValues()`

Comparison uses `Objects.equals()` for scalar values (null-safe), and for enums, uses `sameValueSet()` (lines 339–353) — a **case-insensitive, order-insensitive** set comparison. Example: enum values `["ACTIVE", "inactive"]` in code vs `["active", "INACTIVE"]` in spec are considered equivalent.

If a constraint value differs, emit `CONSTRAINT_GAP` at MEDIUM confidence (lines 192–195).

### 2.9 Consumes/Produces Media-Type Comparison

Media types are compared only when the **code side declares them** (noise suppression, lines 277–287). Each type is normalized by:
1. Lowercasing
2. Extracting the base type (stripping parameters after `;`, e.g., `application/json; charset=utf-8` → `application/json`)
3. Building a set for order-insensitive comparison

Both code and spec types are converted to sets via `mediaSet()` (lines 290–304). If the sets differ, emit `CONSUMES_PRODUCES_MISMATCH` at LOW confidence. If code declares nothing, no finding is raised (most endpoints default to JSON and don't explicitly declare).

### 2.10 Security Mismatch with Filter-Chain Suppression

Lines 235–248 handle authorization divergence. Two cases are detected:

1. **Code enforces authz but spec doesn't** (lines 238–241): Always emit `SECURITY_MISMATCH` at HIGH confidence.
2. **Spec requires authz but code doesn't** (lines 242–248): Emit at MEDIUM confidence **only if** `!centralizesSecurity(code)` (line 242). The `centralizesSecurity()` check (lines 307–310) inspects `code.blindSpots()` for strings containing `"SecurityFilterChain"` or `"HttpSecurity"`. If found, the finding is suppressed — the extractor flagged that auth is centralized in a bean and thus invisible to annotation analysis.

This is a **false-positive control**: endpoints protected by a global `HttpSecurity` config are not unsecured, even if no per-endpoint annotation is found.

### 2.11 Request Body & Status Code Comparison

- **Request body presence** (lines 210–215): `ce.requestBody() != null` vs `se.requestBody() != null`, emitting `REQUEST_BODY_PRESENCE_MISMATCH` at HIGH confidence.
- **Success status codes** (lines 217–224): Extracts the first 2xx status from code (via `successStatus()`, lines 476–480). If code returns 2xx but spec doesn't document it, emit `STATUS_CODE_MISSING` at MEDIUM confidence.
- **Extra status codes** (lines 227–233): If spec documents a 2xx but code never returns it, emit `STATUS_CODE_EXTRA` at LOW confidence (conservative — error responses aren't extracted yet).

### 2.12 Response Schema Matching

The success-response schema is matched by reference (`successSchemaRef()`, lines 265–271). Both sides extract the first 2xx response's `schemaRef`. References are normalized via `normRef()` (lines 273–275) — stripping `[]` array notation and lowercasing. If refs differ, emit `RESPONSE_SCHEMA_MISMATCH` at MEDIUM confidence (line 259).

### 2.13 Schema Field-Level Comparison

For each schema name present in both code and spec, `compareSchema()` (lines 355–393) performs field-level diff:

- **Missing field** (lines 360–367): Field in code but not in spec → `SCHEMA_FIELD_MISSING` at HIGH confidence
- **Type mismatch** (lines 368–373): Field type differs, unless one side is `"object"` (skip to avoid false positives on nested structures) → `SCHEMA_FIELD_TYPE_MISMATCH` at MEDIUM confidence
- **Constraint gap** (lines 374–384): Code has constraints but spec doesn't (or mismatch) → `CONSTRAINT_GAP` at MEDIUM confidence
- **Extra field** (lines 387–392): Field in spec but not in code → `SCHEMA_FIELD_EXTRA` at LOW confidence

### 2.14 Spec-vs-Spec Drift Detection

`diffSpecVsSpec()` (lines 111–129) compares two spec models (e.g., repo YAML vs Confluence YAML). It indexes both by endpoint key and flags any endpoint missing from either side as `SPEC_DRIFT` at HIGH confidence. This surfaces out-of-sync specs.

### 2.15 L1 Parser Messages

`l1FromMessages()` (lines 132–143) converts parser error messages into findings. Messages containing `"ref"` (case-insensitive) emit `UNRESOLVED_REF`; others emit `OPENAPI_PARSE_ERROR`. Both are L1, HIGH confidence.

### 2.16 Deduplication & False-Positive Controls

`dedup()` (lines 102–108) removes exact-duplicate findings by `findingId` (a stable hex hash of `type + endpoint + summary + specSource`, line 400). This prevents the same locus from being double-counted.

**Noise suppression**:
- **Media types**: Only compared if code declares them (lines 277–287), avoiding noise from endpoints that default to JSON without explicit declaration.
- **Consumes/produces confidence**: Set to LOW (line 285) to signal lower priority.
- **Status code extras**: LOW confidence for 2xx codes the spec claims but code doesn't return (line 232), because error responses aren't extracted yet.
- **Filter-chain security**: Suppressed if centralized auth is detected (line 242), avoiding false UNSECURED findings on every endpoint in a framework-protected app.

### 2.17 Fidelity Score Rollup

`FidelityScore.of()` (`src/main/java/ca/bnc/qe/veritas/report/FidelityScore.java`, lines 19–34) computes a **deterministic 0–100 score** from findings:

- Excludes findings that `isNeedsAttention()` (line 22): items of origin "LLM", LOW confidence, or type `DESIGN_QUALITY`/`TEST_BASIS_GAP`
- Applies penalties per severity on counted findings:
  - `BLOCKER`: −25 points
  - `CRITICAL`: −15 points
  - `MAJOR`: −8 points
  - `MINOR`: −3 points
- Returns `max(0, 100 - penalty)`

Only deterministic (HIGH/MEDIUM confidence) structural findings affect the score; LLM-driven design findings and low-confidence items are "needs attention" and excluded (lines 36–43). A score ≥ 90 passes the quality gate (line 17).

### 2.18 Citations & Standards Governance

After findings are generated, `ContractValidationService` (lines 213–215) attaches a deterministic citation via `StandardsReference.forType()` (`src/main/java/ca/bnc/qe/veritas/finding/StandardsReference.java`, lines 15–46):

- Structural findings (L1–L4): Cite **OpenAPI Specification**, **RFC 9110** (HTTP), or **JSON Schema**
- Design findings (L5): Cite **REST API design guidelines** (LLM judgment)
- Test-basis findings (L6): Cite **ISTQB CTFL** (the one standard fitting testing, line 45)

This ensures citations are mechanical and never fabricated.

## 3. Cost calculation — how LLM spend is estimated and tracked

### 3.1 Token estimation

Tokens are estimated using a heuristic of ~4 characters per token since GitHub Copilot's CLI does not reliably expose raw token counts. Both `CostEstimator.estimateTokens()` (d:\veritas\src\main\java\ca\bnc\qe\veritas\cost\CostEstimator.java:55-60) and `PromptComposer.estimateTokens()` (d:\veritas\src\main\java\ca\bnc\qe\veritas\llm\PromptComposer.java:106-110) implement the same formula:

```java
public static long estimateTokens(String text) {
    if (text == null || text.isEmpty()) {
        return 0;
    }
    return (text.length() + 3) / 4;  // ~4 chars/token
}
```

The estimated token counts are persisted to the ledger as `estTokensIn` and `estTokensOut` on every `CostEntry` (d:\veritas\src\main\java\ca\bnc\qe\veritas\persistence\CostEntry.java:27-28).

### 3.2 Two billing modes: USAGE_CREDITS vs. PER_REQUEST

The catalog supports two mutually exclusive billing modes, switchable via `models.yaml:10` (d:\veritas\src\main\resources\veritas\models.yaml:10-12):

**USAGE_CREDITS** (current, since 2026-06-01)
- Billed per token per model, converted to GitHub AI Credits where 1 credit = $0.01 USD
- Each model specifies `creditsPerMTokIn` and `creditsPerMTokOut` (credits per million input/output tokens)
- Formula (CostEstimator.java:39-44):
  ```
  credits = (tokensIn / 1,000,000) * creditsPerMTokIn + (tokensOut / 1,000,000) * creditsPerMTokOut
  estCostUsd = credits * creditUsd  (where creditUsd = 0.01 from models.yaml:12)
  ```
- Example from models.yaml:
  - `claude-opus-4.8`: 500 credits/MTok in, 2500 credits/MTok out → $5.00 per 1M input tokens, $25.00 per 1M output tokens
  - `gpt-5-mini`: 25 credits/MTok in, 200 credits/MTok out → $0.25 per 1M input tokens, $2.00 per 1M output tokens

**PER_REQUEST** (legacy annual Pro/Pro+ plans)
- Each call costs `max(1, requestMultiplier)` premium requests when the CLI floor applies (CostEstimator.java:49)
- The CLI's minimum is enforced when `cliCountsAsPremium: true` (models.yaml:13)
- Each premium request is billed at `pricePerRequestUsd` (models.yaml:11: 0.04 = $0.04 per request)
- Formula (CostEstimator.java:49-51):
  ```
  requests = cliCounts ? max(1.0, multiplier) : multiplier
  estCostUsd = requests * pricePerRequestUsd
  ```
- Example: `claude-opus-4.8` has `requestMultiplier: 27`, so one call costs 27 × $0.04 = $1.08
- Example: `gpt-5-mini` has `requestMultiplier: 0.33`, but with the CLI floor becomes `max(1, 0.33)` = 1 request = $0.04 per call

### 3.3 Live model multipliers and runtime refresh

When Copilot's `/models` endpoint returns a live `billing.multiplier` for a model, `LiveModelMultipliers` (d:\veritas\src\main\java\ca\bnc\qe\veritas\cost\LiveModelMultipliers.java) overrides the static catalog multiplier at cost estimation time. The `CostEstimator` (lines 47-48) prefers the live value:

```java
double staticMultiplier = spec != null ? spec.requestMultiplier() : 1.0;
double multiplier = live.multiplier(modelId).orElse(staticMultiplier);   // live /models wins
```

This ensures per-action cost always reflects real billing, even when GitHub updates model pricing dynamically.

### 3.4 Model selection: picking the cheapest enabled model at a tier

`ModelSelector` (d:\veritas\src\main\java\ca\bnc\qe\veritas\cost\ModelSelector.java:68-117) resolves a skill step's tier (ECONOMY, STANDARD, DEEP, or FRONTIER) to a concrete model using cost-aware ranking:

1. **Cost-aware tier resolution** (lines 68-88): Among all enabled catalog models tagged with the requested tier, pick the cheapest (by USAGE_CREDITS rate or PER_REQUEST multiplier, with live multiplier preferred).
2. **Availability fallback** (lines 79-84): If no catalog model carries the tier, use the policy's primary model, then fallbacks (from model-policy.yaml).
3. **Default fallback** (line 87): If nothing matches, fall back to the configured default (e.g., `claude-sonnet-4.6`).

The cost proxy (lines 112-117) ranks models:
- Under USAGE_CREDITS: by summing input and output rates (`creditsPerMTokIn + creditsPerMTokOut`)
- Under PER_REQUEST: by live multiplier (if available) or static `requestMultiplier`

Per-model context windows are also configured (lines 34-36, 55-58) so wider-context models receive larger prompt budgets; this is passed to `PromptComposer.compose(..., cap)` for token-aware prompt truncation.

### 3.5 The CostEntry ledger and CostRecorder integration

Every time a skill calls an LLM, `CostRecorder.record()` (d:\veritas\src\main\java\ca\bnc\qe\veritas\cost\CostRecorder.java:27-50) creates a durable ledger row. Each row captures:

- `skill`: the skill name (e.g., "validate-contract", "test-strategy", "create-test-cases")
- `action`: the step within the skill (e.g., "reconcile", "section:requirements", "generate")
- `model`: the resolved model ID
- `billingMode`: "USAGE_CREDITS" or "PER_REQUEST"
- `premiumRequests`: only filled under PER_REQUEST mode
- `estTokensIn`, `estTokensOut`: estimated tokens (from the 4-chars/token heuristic)
- `estCostUsd`: the estimated cost in USD
- `owner`: the user who triggered the action
- `refId`: optional link to the artifact (e.g., scanId, planId, test key)

Examples of callers (grep results d:\veritas\src):
- ContractValidationService: `costRecorder.record("validate-contract", "reconcile", ...)`
- TestStrategyService: `costRecorder.record("test-strategy", "section:" + s.key(), ...)`
- CreateTestCasesService: `costRecorder.record("create-test-cases", "generate", ...)`

### 3.6 Zero cost on PromptCache hit

When a prompt is cached, the cost is recorded as $0.00 instead of charging full estimated tokens. The mechanism:

1. **PromptCache** (d:\veritas\src\main\java\ca\bnc\qe\veritas\llm\PromptCache.java) stores responses keyed on `SHA-256(model + prompt)`. It is bounded LRU (default 500 entries, d:\veritas\src\main\java\ca\bnc\qe\veritas\llm\PromptCache.java:24).
2. **CachingLlmGateway** (d:\veritas\src\main\java\ca\bnc\qe\veritas\llm\CachingLlmGateway.java:40-51) transparently checks the cache on every `complete()` call, and if there is a hit, sets `callContext.markCached(true)`.
3. **LlmCallContext** (d:\veritas\src\main\java\ca\bnc\qe\veritas\llm\LlmCallContext.java:14-25) is a thread-local flag that carries the cache-hit state from the gateway to the cost recorder in the same call.
4. **CostRecorder** (d:\veritas\src\main\java\ca\bnc\qe\veritas\cost\CostRecorder.java:35-36) consumes this flag and substitutes `CostResult.zero(model)` instead of calling the estimator:
   ```java
   boolean cached = callContext.consumeCached();
   CostResult cost = cached ? CostResult.zero(model) : estimator.estimate(...);
   ```

This is critical for multi-section test-strategy generation and repeated scans: unchanged knowledge-pack prefixes in the prompt are cached hits and cost zero tokens.

### 3.7 REST cost API and dashboard summary

The `/api/v1/costs` endpoints (d:\veritas\src\main\java\ca\bnc\qe\veritas\web\CostController.java) expose:

- **`GET /api/v1/costs`** (lines 47-50): The full per-action ledger, most recent first.
- **`GET /api/v1/costs/summary`** (lines 53-66): Aggregate spend snapshot:
  ```json
  {
    "totalEstCostUsd": 123.45,
    "actions": 47,
    "bySkill": {
      "validate-contract": 12.34,
      "test-strategy": 56.78,
      "create-test-cases": 54.33
    }
  }
  ```

The dashboard reads these endpoints to display real-time cost tracking.

### 3.8 Worked example: realistic prompt using USAGE_CREDITS rates

**Scenario:** Generate test cases for a medium-spec (10 KB = ~2500 tokens of spec + context, picked model `claude-sonnet-4.6`).

- Prompt construction:
  - Vendored prompt template + spec + outputs contract ≈ 8000 tokens
  - Model cap (token budget) = 60000 tokens default (PromptComposer.java:35)
  - Final assembled prompt ≈ 8200 tokens

- Token estimation:
  - Input (prompt): 8200 tokens
  - Output (typical test case response): 2000 tokens

- Cost under USAGE_CREDITS (models.yaml:20):
  - Claude Sonnet 4.6: 300 credits/MTok in, 1500 credits/MTok out
  - Input cost: (8200 / 1,000,000) × 300 = 0.00246 credits → $0.0000246
  - Output cost: (2000 / 1,000,000) × 1500 = 0.003 credits → $0.00003
  - **Total: $0.0000546** (negligible)

- Cost under legacy PER_REQUEST (if enabled):
  - Sonnet 4.6: `requestMultiplier: 9`
  - Cost: 9 × $0.04 = **$0.36 per call**

### 3.9 Key files and line references

| Concept | File | Lines |
|---------|------|-------|
| Token estimation | CostEstimator.java | 55-60 |
| USAGE_CREDITS formula | CostEstimator.java | 39-44 |
| PER_REQUEST formula | CostEstimator.java | 49-51 |
| Live multiplier override | CostEstimator.java | 47-48 |
| CostEntry schema | CostEntry.java | 22-31 |
| CostRecorder integration | CostRecorder.java | 27-50 |
| Cache hit → zero cost | CostRecorder.java | 35-36 |
| PromptCache SHA-256 keying | PromptCache.java | 65-79 |
| CachingLlmGateway flow | CachingLlmGateway.java | 40-51 |
| LlmCallContext thread-local | LlmCallContext.java | 14-25 |
| ModelSelector cost ranking | ModelSelector.java | 68-117 |
| REST /costs endpoints | CostController.java | 47-66 |
| Model catalog (yaml) | models.yaml | 1-29 |
| Model policy (yaml) | model-policy.yaml | 1-7 |

## 4. How they connect

The end-to-end flow is a deterministic pipeline with the LLM bolted on only at the reconcile step:

1. **Clone** — the target repository is checked out into a local source root.
2. **Extract (AST)** — `JavaSpringExtractor` walks the cloned sources with JavaParser + JavaSymbolSolver and emits a canonical `ApiModel` (code IR), recording blind spots instead of guessing (Section 1).
3. **Parse spec** — `OpenApiModelExtractor` reads each OpenAPI/Swagger spec into the *identical* `ApiModel` shape (spec IR), preserving `$ref` component names (Section 1.11).
4. **Diff** — `DiffEngine` compares code IR vs spec IR (and spec vs spec) 100% deterministically, producing layered L1–L4 findings with mechanical layer, severity, and confidence assignments (Section 2).
5. **Optional LLM reconcile (cost-tracked)** — `ContractValidationService` may invoke the LLM for L5/L6 design and test-basis judgment; every call is metered through `CostRecorder` into the `CostEntry` ledger, with cache hits costing zero (Section 3).
6. **Fidelity score + report** — `FidelityScore.of()` rolls deterministic findings into a 0–100 score, and `StandardsReference` attaches mechanical citations; the report is rendered from these outputs.

The live progress stages (`ScanStages`) and the report renderer consume exactly these outputs — the IR, the findings, the score, and the cost ledger.
