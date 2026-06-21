# Reference: BNC `contract-validator` app — how it really talks to Copilot

Captured from 89 phone photos of the **`contract-validator`** app
(`C:\TestAuto\7731\initiative-ia\contract-validator`, app `contract-validator-app`, branch `develop`) —
a **Tauri** (Rust + TypeScript/React) desktop tool that wraps GitHub Copilot to validate API contracts.
This is the real-world Copilot integration BNC uses; Veritas currently assumes the `copilot` CLI instead.

Legend: **[code]** = transcribed from on-screen source · **[chat]** = from the IDE's AI summary (file body
not shown) · **[inferred]** = reasonable inference, verify before relying.

## Copilot integration — direct HTTP API (NOT the `copilot` CLI)

### Auth = GitHub OAuth Device Flow → Copilot session token (`src/services/copilotAuth.ts`) [code]
- Client id: `const COPILOT_CLIENT_ID = 'Iv1.b507a08c87ecfe98'` (the public copilot.vim/Neovim id).
1. **Device code:** `POST https://github.com/login/device/code`
   - headers `Accept: application/json`, content-type `application/x-www-form-urlencoded`
   - body `client_id=<id>&scope=copilot`
   - → `DeviceCodeResponse { device_code, user_code, verification_uri, expires_in, interval }`
   - callbacks `onCode(user_code)` / `onUrl(verification_uri)` drive the sign-in UI.
2. **Poll for token:** `POST https://github.com/login/oauth/access_token`
   - headers `Accept: application/json`, form-urlencoded
   - body `client_id=<id>&device_code=<device_code>&grant_type=urn:ietf:params:oauth:grant-type:device_code` (grant_type [inferred])
   - poll interval `max((interval||5)*1000, 5000)`, `maxAttempts = 120` (~10 min)
   - handles `authorization_pending` (continue), `slow_down` (extra wait), `expired_token` (abort)
   - on success: `StoredOAuthToken { access_token, token_type, scope }`.
3. **Persist OAuth token:** Tauri secure store file `copilot-auth.json`, key `oauth_token`
   (`saveCopilotOAuthToken` / `getStoredOAuthToken`).
4. **Exchange for session token:** `GET https://api.github.com/copilot_internal/v2/token`
   - header `Authorization: token <oauth.access_token>`, `Accept: application/json`
   - → `{ token, expires_at }`; cached in a session store; **refresh 5 min before expiry**; default ~25 min
     if `expires_at` absent (`new Date(data.expires_at * 1000).toISOString()`).
   - on failure throws "Copilot token exchange failed. Your session may have expired."

### Models + billing (`copilotAuth.ts`) [code]
- `GET https://api.githubcopilot.com/models`
  - headers: `Authorization: Bearer <session.token>`, `Accept: application/json`,
    `X-GitHub-Api-Version: 2025-04-01`, `editor-version: vscode/1.100.0`, `Copilot-Integration-Id: vscode-chat`
- Each model exposes `billing.multiplier` (**GitHub's authoritative premium-request multiplier — the ONLY
  programmatic source; there is no separate pricing API**), `is_premium`, `restricted_to`, optional
  `pricing`/`cost`, plus `capabilities`, `supported_endpoints`, `model_picker_category`, `vendor`, `version`.
- `parseBilling()` defensively normalizes the multiplier (may be number OR numeric string; shape varies by
  API version). Cost estimation prefers this live multiplier over any static table.

### Identity [code]
- `GET https://api.github.com/user` with `Authorization: token <oauth.access_token>` → login/name/avatar/email;
  `isCopilotAuthenticated()` returns `result.ok` of this call.

### Chat / completions + streaming (`src/services/gh.ts`) [chat] ⚠️ body NOT directly captured
- Builds the chat payload, optionally compresses/trims context, writes a temp JSON + PowerShell script,
  spawns PowerShell to `POST https://api.githubcopilot.com/chat/completions` with
  `Authorization: Bearer <session.token>`.
- `streamCopilotChat` parses **SSE** lines and forwards chunks to the UI.
- Also uses `gh.exe` (`ghExec`) for some GitHub ops.
- **GAP:** the exact request body (model id, `messages[]`, `stream`, `temperature`, `max_tokens`, and which
  editor headers are sent on chat) was not on screen — only the auth/models file was. Needs a few photos of
  `gh.ts` to complete (see "What's still needed").

### HTTP transport with PowerShell fallback (`gh.ts`) [code]
- `httpRequest(method, url, headers, body?, contentType?)`: tries Tauri's HTTP plugin (`fetch`, Rust-side, no
  CORS) **first**; on failure falls back to `httpRequestViaPowerShell` →
  `Command.create('powershell', ['-NoProfile','-Command', script])` running `Invoke-RestMethod`, with
  `[Console]::OutputEncoding=[Text.Encoding]::UTF8` and `$ProgressPreference='SilentlyContinue'`.
- Rationale (verbatim doc comment): *"Falls back to PowerShell if the Tauri plugin fails (e.g. corporate
  firewall blocks the Rust process but allows powershell.exe)."* PowerShell inherits system proxy/TLS/firewall.
- **Relevance to Veritas:** Veritas uses the JVM `RestClient` — same corporate-proxy risk; a PowerShell (or
  proxy-aware) fallback may be needed in the BNC network.

## Jira integration (`src/services/jiraService.ts`) — all via PowerShell `Invoke-RestMethod` [code]
- Endpoints (`https://{host}` Jira Server/DC, `/rest/api/2`):
  - `GET /rest/api/2/project` — list/search projects (`searchJiraProjects`)
  - `GET /rest/api/2/project/{projectKey}` — issue types (`fetchIssueTypes`)
  - `GET /rest/api/2/issue/createmeta?projectKeys={projectKey}&...` — allowed fields per project/issuetype
    (`fetchCreateMeta` → `CreateMetaResult { allowedFields, ... }`)
  - `GET /rest/api/2/search?jql={jql}&fields=status&maxResults=...` — **batched** status refresh
  - Epic discovery via JQL (`project=.. AND issuetype=Epic AND status..`); label validation via suggest API
  - create issue, attach file, move-to-sprint, add comment (POST routes)
- Auth: Jira token, **falls back to the wiki token** for backward compat.
- `PS_UTF8` prefix prevents "invalid utf-8 sequence" from Tauri shell on non-ASCII (French accents).
- Defect payload is **deterministic (no AI)**: wiki-markup description with Business Impact, Actual Result,
  Current YAML diff, **Code Evidence (file/line + java snippet)**, Expected Result, **Proposed Fix
  (`{code:yaml}` corrected YAML)**, References, "Generated by Contract Validation Agent".
  Severity→priority: all created at **Medium** (`JIRA_PRIORITY_ID = '3'`), PO re-prioritizes.
  Labels: `contract-validation` + service name + finding code. Custom-field auto-discovery: Epic Link
  (`/epic\s*link/i`) and Team (`/^team$/i`) by scanning create-meta field display names.
- `createDefectsForFindings` runs **sequentially** (rate-limit safe) with `onProgress`.
- Persistence: `{doc_dir}/jira/{serviceName}_jira_defects.json`; **dedupe by issueKey**, **status
  carry-forward**, idempotency guard (`lastCorrectionCommentAt`/`lastStatusCheck`), posts
  correction-notification comments when a finding's fix changes.

## Full service inventory (file tree) [code]
`copilotAuth.ts` (+test) · `gh.ts` (+test) · `copilotPricing.ts` (+test) · `modelSelector.ts` (+test) ·
`headroomService.ts` (premium-request quota/headroom) · `ConsensusPipeline.ts` · `ContractComparator.ts`
(+test) · `CorrectedYaml`/`correctionYaml` (+tests) · `CorrectionDetector.ts` · `DuplicateDetector.ts`
(+test) · `FailureCategorizer.ts` (+test) · `FindingsPostProcessor.ts` (+test) · `ExtractionCache.ts`
(+test) · `correctionCache` (+test) · `VerificationCache.ts` · `ConfigManager.ts` ·
`AutomatedTestOrchestrator.ts` · `mavenRunner.ts` (+test) · `planOrchestrator.ts`/`planParser.ts` (+tests) ·
`jiraService.ts` · `jiraTestCreator.ts` · `jiraReleaseFetcher.ts` · `BoardroomService.ts` ·
`ReleaseGatekeeper.ts` · `JiraProjectPicker` (React) · `logger.ts`.

## Notable design choices vs Veritas
- **Contract extraction by LLM multi-model CONSENSUS** (`ConsensusPipeline` + `autoSelectExtractionModels`
  pick several cheap/mid models and consolidate) — opposite of Veritas's deterministic-AST-first principle.
  Hence the heavy caching (extraction/correction/verification) + cheap-model machinery to control cost.
- **Live cost** from `billing.multiplier` + **headroom/quota** tracking — more accurate than Veritas's static
  `ModelCatalog`.
- **Tauri desktop**, per-user **Tauri secure store**, findings/defects as **JSON files** — vs Veritas's
  Spring Boot web+CLI + SQLite.
- Features Veritas lacks: `ReleaseGatekeeper` (release quality gate), `BoardroomService` (multi-agent review),
  Jira createmeta/custom-field auto-discovery, correction-notification comments.

## Implemented in Veritas (from this reference)
- **Copilot HTTP gateway** (`veritas.llm.mode=http`): `CopilotAuthService` (device flow + session token),
  `CopilotModelsClient` (live `billing.multiplier` → `LiveModelMultipliers` → `CostEstimator`),
  `CopilotHttpGateway` (OpenAI-compatible `chat/completions`), `veritas copilot-login` CLI.
- **Jira Server/DC v2 client** (`jira.edition=SERVER_DC`, default): `JiraServerClient` — `/rest/api/2`,
  wiki-markup descriptions, Bearer PAT, `addComment`, `attachFile` (corrected YAML), `createMeta`
  field/custom-field discovery; `DefectComposer` emits `{code:yaml}`/`{code:java}` wiki blocks; `DefectService`
  attaches the corrected YAML on create.
- **Corp-proxy transport** `CorpHttp` (RestClient + `veritas.http.powershell-fallback` → PowerShell
  `Invoke-RestMethod`), used by the Jira v2 client and all Copilot HTTP calls.
All verified via WireMock (`CopilotHttpWireMockTest`, `JiraServerClientWireMockTest`, `CorpHttpTest`,
`DefectComposerWikiTest`). Live validation (a real `copilot-login` + Jira create) needs the BNC workspace.

## What's still needed (to fully replicate the Copilot client)
1. **`gh.ts` chat code** — the `/chat/completions` request body (model param, `messages[]`, `stream`,
   `temperature`, `max_tokens`) and the SSE parse loop in `streamCopilotChat`. A few photos of `gh.ts`
   (the chat payload builder + streaming) would complete this.
2. **`modelSelector.ts` / `copilotPricing.ts`** — the exact cheap/mid/premium classification thresholds and
   the static fallback pricing table.
3. **`headroomService.ts`** — how premium-request quota/headroom is read (endpoint or response header).
