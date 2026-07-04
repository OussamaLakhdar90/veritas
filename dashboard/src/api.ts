import i18n from './i18n'

const BASE = '/api/v1'

/**
 * Global hook the Copilot auth-gate registers: when any API call fails with the RFC-7807
 * `code: "copilot-auth-required"`, we invoke this so the UI can pop the sign-in flow instead of just
 * showing a red toast. Set by CopilotAuthProvider; null otherwise.
 */
let copilotAuthHandler: (() => void) | null = null
export function onCopilotAuthRequired(fn: (() => void) | null) { copilotAuthHandler = fn }

/** Error carrying the raw server detail/code alongside the friendly message (for debugging / special handling). */
export interface ApiError extends Error { raw?: string; code?: string; status?: number }

/**
 * Translate a technical/HTTP failure into one plain sentence a non-engineer can act on.
 * The server's RFC-7807 `detail` is trusted for 4xx (those messages are written for humans) and
 * always preserved on the thrown error as `.raw`; generic HTTP/network failures get a friendlier rewrite.
 */
function friendlyMessage(detail: string, code: string | undefined, status: number): string {
  // The app defaults to French — a mid-demo failure must not suddenly toast in English.
  if (code === 'copilot-auth-required') return i18n.t('errors.copilotAuth')
  if (status === 0) return i18n.t('errors.network')
  if (status === 401 || status === 403) return i18n.t('errors.denied')
  if (status === 404) return i18n.t('errors.notFound')
  if (status === 408 || status === 504) return i18n.t('errors.timeout')
  if (status >= 500) return i18n.t('errors.server')
  // 4xx with a server-provided, human-readable detail: trust it as-is.
  return detail
}

/** Turn a non-OK response into a friendly Error, and fire the Copilot sign-in hook if that's the cause. */
async function fail(res: Response): Promise<never> {
  let detail = `${res.status} ${res.statusText}`
  let code: string | undefined
  try {
    const j = await res.json()
    if (j) { detail = j.detail || j.message || detail; code = j.code }
  } catch { /* non-JSON body */ }
  if (code === 'copilot-auth-required' && copilotAuthHandler) copilotAuthHandler()
  const err: ApiError = new Error(friendlyMessage(detail, code, res.status))
  err.raw = detail; err.code = code; err.status = res.status
  throw err
}

/** fetch wrapper that converts a network-level rejection (server down, offline, DNS) into a friendly ApiError. */
async function doFetch(path: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(BASE + path, init)
  } catch {
    const err: ApiError = new Error(friendlyMessage('', undefined, 0))
    err.status = 0
    throw err
  }
}

async function get<T>(path: string): Promise<T> {
  const res = await doFetch(path)
  if (!res.ok) return fail(res)
  return res.json() as Promise<T>
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await doFetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) return fail(res)
  return res.json() as Promise<T>
}

/** Method-flexible sender that tolerates 204 and surfaces an RFC-7807 `detail`/`message` on error (friendly toasts). */
async function send<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await doFetch(path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) return fail(res)
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

// ── Settings types (Part A backend) ──────────────────────────────────────────
export type SecretStatus = Record<string, boolean>
export interface EndpointCfg { baseUrl?: string; edition?: string; workspace?: string; authType?: string }
export interface ConnectionsCfg { bitbucket: EndpointCfg; jira: EndpointCfg; confluence: EndpointCfg; xray: EndpointCfg; snyk: EndpointCfg }
export interface UpdateConnectionsResult { applied: boolean; restartRequiredFields: string[] }
export interface ConnectionTestResult { service: string; reachable: boolean; authenticated: boolean; status: number; message: string }
export interface CopilotLoginStart { id: string; userCode: string; verificationUri: string; expiresIn: number }
export interface CopilotLoginStatus { state: 'PENDING' | 'AUTHORIZED' | 'EXPIRED' | 'ERROR'; message: string }

export interface Scan {
  id: string
  serviceName: string
  status: string
  /** Live progress step while RUNNING: QUEUED | CLONING | RESOLVING_SPEC | EXTRACTING | DIFFING | RECONCILING | REPORTING | DONE | FAILED. */
  stage?: string
  /** Live sub-step detail of the current stage (e.g. "Generating the corrected spec…"). */
  stageDetail?: string
  /** Copilot model resolved for the AI (reconcile) step. */
  model?: string
  /** The stage the scan was in when it failed (set only when status is FAILED). */
  failedStage?: string
  totalFindings: number
  totalEstCostUsd: number
  startedAt: string
  finishedAt?: string
  specSources: string
  errorMessage?: string
  /** Contract Fidelity Score /100 — null while RUNNING or on FAILED scans. */
  fidelityScore?: number | null
  previousFidelityScore?: number | null
  coverageGaps?: number | null
  confidence?: number | null
}

/** Executive rollup — the same ReleaseVerdict the HTML report renders (backend guarantees agreement). */
export interface ExecutiveServiceSummary {
  service: string
  fidelity?: number | null
  delta?: number | null
  breakingCount: number
  blockingCount: number
  releaseSafe: 'PASS' | 'WARN' | 'FAIL'
  latestScanId: string
}
export interface ExecutiveSummary {
  totals: { breakingFindingsCaught: number; blockingOpen: number; disputedByAi: number }
  perService: ExecutiveServiceSummary[]
  dispositions: { reviewed: number; accepted: number; rejected: number; jiraCreated: number; open: number; aiDisputed: number }
}

export interface Finding {
  id: string
  type: string
  layer: string
  severity: string
  confidence: string
  endpoint: string
  specSource: string
  summary: string
  explanation?: string
  codeFile?: string
  codeStartLine?: number
  /** Bitbucket deep link to the code evidence (computed server-side; absent when not resolvable). */
  codeUrl?: string
  status?: string
  reviewedBy?: string
  reviewedAt?: string
  reviewNote?: string
}

export interface Repo {
  slug: string
  name: string
  description: string
  defaultBranch: string
  projectKey: string
}

export interface DefectResult { jiraKey: string; jiraUrl: string }
/** 202 body from POST /scans — the scan runs in the background; poll {@link api.scan} for stage/status. */
export interface ScanAccepted { scanId: string; status: string }

export interface TestPlan {
  id: string
  serviceName: string
  kind: string
  fixVersion?: string
  description?: string
  status?: string
  confidence?: number
  riskCount?: number
  estCostUsd?: number
  deliverableJson?: string
  createdAt?: string
}

export interface CoverageItem {
  requirementKey?: string
  requiredCaseRef: string
  dimension: string
  matchStatus: string
  matchedTestKey?: string
  confidence?: string
}

export interface TestPlanDetail { plan: TestPlan; coverage: CoverageItem[] }

export interface CostSummary { totalEstCostUsd: number; actions: number; bySkill: Record<string, number> }
/** One day of LLM spend (zero-filled series from /costs/trend). */
export interface CostTrendPoint { date: string; totalUsd: number; actions: number }
/** One day of scan activity (zero-filled series from /scans/trend). */
export interface ScanTrendPoint { date: string; scans: number; findings: number }
export interface PreflightCheck { name: string; status: string; detail: string; remediation: string }

export interface DefectLink {
  id: string
  findingId: string
  scanId?: string
  serviceName?: string
  severity?: string
  jiraKey?: string
  jiraUrl?: string
  jiraStatus?: string
  jiraStatusCategory?: string
  createdInJira: boolean
  createdBy?: string
  lastSyncedAt?: string
  createdAt?: string
}

/** Test-completion summary: designed vs executed (read back from Xray), with deviations to explain. */
export interface ExecutionSummary {
  serviceName?: string
  jql: string
  total: number
  passed: number
  failed: number
  blocked: number
  notRun: number
  deviations: { testKey: string; rawStatus?: string; outcome: string }[]
  verdict: string
}

/** Aggregate defect metrics: totals, open/closed, and distributions by severity / status / service. */
export interface DefectMetrics {
  total: number
  open: number
  closed: number
  bySeverity: Record<string, number>
  byStatusCategory: Record<string, number>
  byService: Record<string, number>
}

export interface GateDecision {
  id: string
  runId?: string
  action?: string
  status?: string
  approver?: string
  decidedAt?: string
  note?: string
  createdAt?: string
}

export interface CodegenRun {
  id: string
  serviceName: string
  templateSource?: string
  outputRepo?: string
  jiraKey?: string
  branch?: string
  prUrl?: string
  buildStatus?: string
  filesWritten?: string
  todos?: string
  approvedBy?: string
  estCostUsd?: number
  createdAt?: string
}

/** One service the platform holds work for, with per-stage counts — backs the service picker / recent-work. */
export interface ServiceSummary {
  name: string
  strategies: number
  conditions: number
  cases: number
  plans: number
  scans: number
  codegenRuns: number
}

/** A Jira issue surfaced by the ticket picker. */
export interface JiraIssueRef {
  key: string
  summary?: string
}

/** One line of the test-generation reconciliation plan (GAP | CURRENT | ORPHAN). */
export interface TestGenPlanItem {
  status: 'GAP' | 'CURRENT' | 'ORPHAN' | 'STALE'
  method?: string
  path?: string
  signature?: string
  existingRef?: string
  reason?: string
}

/** What we'd do for a service before generating: add / leave / flag, plus scratch-vs-refactor mode. */
export interface TestGenPlan {
  serviceName: string
  mode: 'SCRATCH' | 'REFACTOR'
  items: TestGenPlanItem[]
  filesScanned: number
}

/** One OAuth scope: enum-constant name (READ/WRITE/DELETE) → the Okta scope string the API requires. */
export interface Scope {
  name: string
  value: string
}

/**
 * One token group / API group — its own Okta token source (BNC lsist framework: token via private-key JWT). Becomes a
 * {Name}TokenHelper + WorldKey.{NAME}_TOKEN; endpoints under its pathPrefixes use its token. Stores only the token URL,
 * client id, the private-key FIELD name, scope strings and path prefixes — never the private key itself.
 */
export interface ServiceAuthGroup {
  name: string
  tokenUrl?: string
  clientId?: string
  privateKeyField?: string
  credentialsFile?: string
  scopes: Scope[]
  pathPrefixes: string[]
}

/** A service's auth declaration: 0 groups = public (no token), N groups = one Okta token per API group. */
export interface ServiceAuthSpec {
  groups: ServiceAuthGroup[]
}

export interface TestCase {
  id: string
  serviceName?: string
  title: string
  technique?: string
  priority?: string
  type?: string
  level?: string
  automation?: string
  status?: string
  xrayKey?: string
  linkedRequirement?: string
  rationale?: string
  confidence?: number
  approvedBy?: string
}

export interface TestStrategy {
  id: string
  serviceName?: string
  status?: string
  source?: string          // CODE | JIRA_CONFLUENCE | multi-source
  confidence?: number
  deliverableJson?: string
  scorecardJson?: string
  contentMarkdown?: string
  createdAt?: string
}

/** A test condition (ISTQB test analysis) — the work product between the strategy and the cases. */
export interface TestCondition {
  id: string
  serviceName?: string
  conditionRef?: string
  description?: string
  sourceBasisItem?: string
  priority?: string
  riskRef?: string
  qualityCharacteristic?: string
  technique?: string
  automation?: string          // MANUAL | AUTOMATED | CANDIDATE
  automationRationale?: string
  status?: string
  testStrategyId?: string
  confidence?: number
  createdAt?: string
}

/** Request to the multi-source strategy endpoints — any subset of code + Jira + Confluence. */
export interface MultiSourceStrategyRequest {
  code?: { appId?: string; repoSlug?: string; branch?: string; repoPath?: string }
  jira?: { jql?: string; maxResults?: number; epicKey?: string }
  confluence?: { pageIds?: string[]; rootPageId?: string }
}

/** The §6 preview: the persisted, editable feature index — what the pipeline extracted + clustered, before synthesis. */
export interface StrategyPreview {
  snapshotId: string
  features: Array<{ featureId: string; displayName: string; status: string; pinned: boolean;
    units: Array<{ id: string; source: string; type: string; title: string }> }>
  gaps: Array<{ kind: string; feature: string; message: string }>
  mix: { code: boolean; jira: boolean; confluence: boolean }
  redactionCount: number
  fetchFailures: string[]
  hardFail: boolean
  estimatedCostUsd: number
  /** On a "re-run keeping my edits" preview: reviewer edits that couldn't be re-applied (their features vanished). */
  carryForwardNotes: string[]
  /** Async-generate poll fields: set on success (navigate target) / while in flight / on failure (show + retry). */
  generatedStrategyId?: string
  generationStartedAt?: string
  generationError?: string
}

/** 202 body from kicking off an async multi-source generate — the snapshot id to poll + a status to show meanwhile. */
export interface StrategyAccepted {
  snapshotId: string
  status: string
}

/** An Xray test a JQL selects — shown for selection before reviewing. */
export interface ReviewCandidate {
  key: string
  summary?: string
  testType?: string
  steps: number
}

export interface ReviewResult {
  id: string
  targetKey?: string
  verdict?: string
  score?: number
  confidence?: number
  gapsJson?: string
  correctedJson?: string
  deliverableJson?: string
  createdAt?: string
}

/** Parsed structure of ReviewResult.deliverableJson — the ISTQB C1–C6 rubric review the backend returns. */
export interface ReviewDeliverable {
  score?: number
  verdict?: string
  gaps?: Array<{ criterion?: string; severity?: string; issue?: string; citation?: string }>
  rubric?: Array<{ criterion: string; score?: number; note?: string }>
  correctedSteps?: Array<{ action?: string; data?: string; expected?: string }>
  selfReview?: { confidence?: number; blindSpots?: string[] }
}

/** Parsed structure of TestStrategy.scorecardJson — the deterministic multi-source quality scorecard. */
export interface StrategyScorecard {
  verdict: string            // OK | DEGRADED
  confidence: number
  checks: Array<{ name: string; passed: boolean; detail: string }>
  featuresCovered: number
  droppedSections: number
}

/** Parsed structure of TestPlan.deliverableJson — the consultant-grade ISTQB deliverable. */
export interface Deliverable {
  executiveSummary?: string
  scope?: { inScope?: string[]; outOfScope?: string[]; objectives?: string[]; assumptions?: string[] }
  riskRegister?: Array<{ id: string; description: string; category?: string; qualityCharacteristic?: string; likelihood?: string; impact?: string; level: string; mitigation?: string; citation?: string }>
  testApproach?: { levels?: string[]; types?: string[]; techniques?: Array<{ name: string; rationale?: string; citation?: string }>; entryCriteria?: string[] }
  exitCriteria?: Array<{ criterion: string; metric?: string; smart?: boolean; citation?: string }>
  estimation?: { technique?: string; effortDays?: number; basis?: string; citation?: string }
  selfReview?: { confidence?: number; rubricChecks?: Array<{ check: string; pass?: boolean; note?: string }>; blindSpots?: string[] }
  markdown?: string
}

// ── Snyk dependency-security module ───────────────────────────────────────────
/** A Snyk org — one per BNC app-id (slug e.g. app7576). */
export interface SnykOrg { id: string; slug: string; name: string }
/** A Snyk target — a source repo under an org (the repo to watch). */
export interface SnykTarget { id: string; displayName: string }
/** A watched repo with its latest severity counts. */
export interface SnykWatchView {
  id: string; orgId: string; orgSlug: string; orgName: string; targetId: string; repoSlug: string;
  enabled: boolean; critical: number; high: number; medium: number; low: number;
  fixable: number; projectCount: number; lastPolled?: string;
}
/** One vulnerability; `fixedIn` is the safe version, or null when Snyk has no supported fix (tracked only). */
export interface SnykIssueView {
  projectName: string; issueId: string; severity: string; title: string; pkgName: string; pkgVersion: string;
  cve: string; cwe: string; cvss: number; riskScore: number; fixable: boolean; fixedIn?: string | null;
}
/** A dashboard notification raised when a watched repo's status worsens. */
export interface SnykAlert {
  id: string; watchId: string; orgSlug: string; repoSlug: string; severity: string; message: string;
  seen: boolean; createdAt?: string;
}
/** Background-refresh state: `running` while the async poll of watched repos is in flight (the button reflects it). */
export interface SnykRefreshStatus { running: boolean; lastRefreshedAt?: string }
/** Managerial roll-up — what's open (found) vs what Veritas has done about it (fixed). */
export interface SnykSummary {
  watchedApps: number; projects: number;
  critical: number; high: number; medium: number; low: number; fixable: number; unseenAlerts: number;
  fixesStarted: number; fixesInProgress: number; fixesMerged: number; fixesBreaking: number;
  prsOpened: number; llmChecks: number; llmCostUsd: number;
}

/** The advisory breaking-change verdict (unavailable when Copilot is off — the reactor build is the real gate). */
export interface BreakingVerdict {
  available: boolean; breaking: boolean; confidence: number; reasons: string[]; migrationNotes?: string;
}
/** One repo's PR in a fix train. */
export interface SnykFixStepView {
  order: number; moduleLabel: string; bitbucketProject: string; repoSlug: string; branch: string;
  pomPath: string; diffPreview: string; newModuleVersion?: string; prUrl?: string; prOpenedBy?: string;
  status: string; manual: boolean; reason?: string; reviewers: string[];
}
/** A release-cascade fix train: the issue, the Jira + verdict, the reactor result, and every PR. */
export interface SnykFixTrainView {
  id: string; coordinate: string; oldVersion: string; fixedIn: string; severity: string; appIds: string;
  jiraKey?: string; status: string; stageDetail?: string; breaking: boolean; reactorPassed?: boolean;
  reactorFailingLabel?: string; reactorOutputTail?: string; verdict?: BreakingVerdict; startedAt?: string;
  createdAt?: string; finishedAt?: string; watchId?: string;
  steps: SnykFixStepView[];
}

// ── Activity Center ───────────────────────────────────────────────────────────
/**
 * One row of the unified server-truth activity feed (scans, fix trains, codegen runs). `status` is one of the
 * FIVE plain states the whole app shares; `link` is the dashboard route that shows the task; `acked` means the
 * user dismissed the item (persisted server-side, so a reload or another browser agrees).
 */
export interface ActivityItem {
  id: string
  type: 'SCAN' | 'FIX_TRAIN' | 'CODEGEN'
  label: string
  status: 'QUEUED' | 'RUNNING' | 'WAITING_FOR_YOU' | 'COMPLETED' | 'FAILED'
  stage?: string
  detail?: string
  needsAttention: boolean
  startedAt?: string
  finishedAt?: string
  link: string
  acked: boolean
}

export const api = {
  scans: (service?: string) => get<Scan[]>(`/scans${service ? `?service=${encodeURIComponent(service)}` : ''}`),
  scan: (id: string) => get<Scan>(`/scans/${encodeURIComponent(id)}`),
  findings: (scanId: string, facets?: { severity?: string; layer?: string; status?: string }) => {
    const qs = new URLSearchParams()
    if (facets?.severity) qs.set('severity', facets.severity)
    if (facets?.layer) qs.set('layer', facets.layer)
    if (facets?.status) qs.set('status', facets.status)
    const q = qs.toString()
    return get<Finding[]>(`/scans/${scanId}/findings${q ? `?${q}` : ''}`)
  },
  // Every service the platform holds work for, with per-stage counts — backs the service picker / recent-work.
  services: () => get<ServiceSummary[]>('/services'),
  repos: (appId: string) => get<Repo[]>(`/repos?appId=${encodeURIComponent(appId)}`),
  branches: (appId: string, slug: string) =>
    get<string[]>(`/repos/${encodeURIComponent(slug)}/branches?appId=${encodeURIComponent(appId)}`),
  createDefect: (findingId: string, projectKey: string) =>
    post<DefectResult>(`/findings/${findingId}/defect`, { projectKey, issueType: 'Bug' }),
  triggerScan: (body: { serviceName?: string; appId?: string; repoSlug?: string; branch?: string; repoPath?: string;
    specPaths?: string[]; specSources?: { kind: string; ref: string }[]; llmEnabled?: boolean;
    thoroughness?: string }) =>
    post<ScanAccepted>('/scans', body),
  reportUrl: (scanId: string) => `${BASE}/scans/${scanId}/report`,
  reportDownloadUrl: (scanId: string) => `${BASE}/scans/${scanId}/report?download=true`,
  testPlans: () => get<TestPlan[]>('/test-plans'),
  testPlan: (id: string) => get<TestPlanDetail>(`/test-plans/${id}`),
  costSummary: () => get<CostSummary>('/costs/summary'),
  costTrend: (days = 30) => get<CostTrendPoint[]>(`/costs/trend?days=${days}`),
  scansTrend: (days = 30) => get<ScanTrendPoint[]>(`/scans/trend?days=${days}`),
  executiveSummary: () => get<ExecutiveSummary>('/summary/executive'),
  fidelityTrend: (days = 30) => get<{ date: string; value: number }[]>(`/summary/fidelity-trend?days=${days}`),
  preflight: () => get<PreflightCheck[]>('/preflight'),
  defects: () => get<DefectLink[]>('/defects'),
  defectMetrics: () => get<DefectMetrics>('/defects/metrics'),
  // Read-only test-completion: latest execution status of a JQL's tests, read back from Xray.
  executionCompletion: (jql: string, service?: string) =>
    get<ExecutionSummary>(`/execution/completion?jql=${encodeURIComponent(jql)}${service ? `&service=${encodeURIComponent(service)}` : ''}`),
  syncDefects: () => post<{ updated: number }>('/defects/sync', {}),
  gates: (status = 'PENDING') => get<GateDecision[]>(`/gates?status=${encodeURIComponent(status)}`),
  approveGate: (id: string, approver = 'dashboard') => post<GateDecision>(`/gates/${id}/approve`, { approver }),
  rejectGate: (id: string, approver = 'dashboard', note = '') => post<GateDecision>(`/gates/${id}/reject`, { approver, note }),

  // Codegen (Generate-Tests workspace)
  codegenRuns: () => get<CodegenRun[]>('/codegen-runs'),
  codegenRun: (id: string) => get<CodegenRun>(`/codegen-runs/${id}`),
  publishCodegen: (id: string, repoSlug: string, targetBranch = 'main', allowFailedBuild = false) =>
    post<CodegenRun>(`/codegen-runs/${id}/publish?repoSlug=${encodeURIComponent(repoSlug)}&targetBranch=${encodeURIComponent(targetBranch)}&allowFailedBuild=${allowFailedBuild}`, {}),
  // Kick off template-driven test generation for a service (202 → a CodegenRun to inspect/publish).
  // endpoints (optional): scope generation to these "METHOD /path" signatures (the wizard's selected gaps).
  implementTests: (service: string, body: { serviceRepo: string; templatePath?: string; outputDir: string; owner?: string; endpoints?: string[] }) =>
    send<CodegenRun>('POST', `/services/${encodeURIComponent(service)}/implement-tests`, body),
  // Preflight for the test-gen wizard: reconcile the API against the existing tests (no LLM, no writes).
  // Omit testRepoSlug for a from-scratch plan. Both repos share appId; *RepoPath are local-dev overrides.
  testGenPlan: (service: string, body: { appId?: string; serviceRepoSlug?: string; serviceBranch?: string;
    serviceRepoPath?: string; testRepoSlug?: string; testBranch?: string; testRepoPath?: string }) =>
    send<TestGenPlan>('POST', `/services/${encodeURIComponent(service)}/test-gen/plan`, body),
  // Generate the selected tests into a clone of the output repo (202 → a CodegenRun). Does NOT push — opening the PR
  // is the separate, user-clicked publishCodegen step. endpoints scopes generation; omit for the whole service.
  // jiraKey is required: the branch/commit/PR reference it so the PR links to the work item.
  testGenGenerate: (service: string, body: { appId?: string; serviceRepoSlug?: string; serviceBranch?: string;
    serviceRepoPath?: string; outputRepoSlug?: string; outputBranch?: string; outputRepoPath?: string;
    endpoints?: string[]; owner?: string; jiraKey?: string; serviceAuth?: ServiceAuthSpec }) =>
    send<CodegenRun>('POST', `/services/${encodeURIComponent(service)}/test-gen/generate`, body),
  // The service's saved auth-token profile (declared token groups), for the wizard's Auth-step pre-fill. Names only.
  authProfile: (service: string, appId: string, serviceRepoSlug: string) =>
    get<ServiceAuthSpec>(`/services/${encodeURIComponent(service)}/test-gen/auth-profile`
      + `?appId=${encodeURIComponent(appId)}&serviceRepoSlug=${encodeURIComponent(serviceRepoSlug)}`),
  // Search Jira for the ticket picker: a text query → summary search; a pasted key/URL → exact lookup.
  jiraSearch: (q: string) => get<JiraIssueRef[]>(`/jira/search?q=${encodeURIComponent(q)}`),

  // Release test plan trigger + RTM workspace
  triggerReleasePlan: (service: string, body: { fixVersion: string; issuesJql?: string; testsJql?: string; projectKey?: string; createGaps?: boolean }) =>
    post<{ planId: string; total: number; matched: number; gaps: number; created: number; orphans: number; confidence?: number; risks?: number }>(`/services/${encodeURIComponent(service)}/release-test-plans`, body),
  testPlanReportUrl: (id: string, format = 'html') => `${BASE}/test-plans/${id}/report?format=${format}`,

  // Test cases (RTM per-row actions + create-approved)
  testCases: (service: string) => get<TestCase[]>(`/services/${encodeURIComponent(service)}/test-cases`),
  // Routes through send() so a 4xx/5xx surfaces a real error instead of silently parsing the error body as a TestCase.
  patchTestCase: (id: string, body: { status?: string; title?: string; actor?: string }) =>
    send<TestCase>('PATCH', `/test-cases/${id}`, body),
  pushTestCase: (id: string, projectKey: string) => post<TestCase>(`/test-cases/${id}/push`, { projectKey }),

  // Findings disposition (accept/reject/triage) — server records who/when (+ optional why note).
  // Routes through send() so a 4xx/5xx surfaces a real error (and the Copilot auth gate) instead of a false success.
  patchFinding: (id: string, status: string, note?: string) =>
    send<Finding>('PATCH', `/findings/${id}`, { status, note }),

  // Strategies / Reviews
  strategies: (service: string) => get<TestStrategy[]>(`/services/${encodeURIComponent(service)}/strategies`),
  generateStrategy: (service: string, body: { basis: string; source?: string; owner?: string }) =>
    send<TestStrategy>('POST', `/services/${encodeURIComponent(service)}/strategies`, body),
  // Strategy detail / revision workflow (each edit/regenerate stores a new immutable version; approve locks it).
  strategy: (id: string) => get<TestStrategy>(`/strategies/${encodeURIComponent(id)}`),
  strategyVersions: (id: string) => get<TestStrategy[]>(`/strategies/${encodeURIComponent(id)}/versions`),
  reviseStrategySection: (id: string, key: string, content: string, actor = 'dashboard') =>
    send<TestStrategy>('PATCH', `/strategies/${encodeURIComponent(id)}/sections/${encodeURIComponent(key)}`, { content, actor }),
  regenerateStrategySection: (id: string, key: string, guidance?: string, actor = 'dashboard') =>
    send<TestStrategy>('POST', `/strategies/${encodeURIComponent(id)}/sections/${encodeURIComponent(key)}/regenerate`, { guidance, actor }),
  approveStrategy: (id: string, actor = 'dashboard') =>
    send<TestStrategy>('POST', `/strategies/${encodeURIComponent(id)}/approve`, { actor }),
  strategyRationaleUrl: (id: string) => `${BASE}/strategies/${id}/rationale`,
  strategyWhyDocUrl: (id: string) => `${BASE}/strategies/${id}/why-doc`,
  // Pass carryForwardFrom (a prior snapshot id) to re-extract the same sources and carry the reviewer's edits forward.
  previewMultiSourceStrategy: (service: string, body: MultiSourceStrategyRequest, carryForwardFrom?: string) =>
    send<StrategyPreview>('POST', `/services/${encodeURIComponent(service)}/multi-source-strategy/preview${
      carryForwardFrom ? `?carryForwardFrom=${encodeURIComponent(carryForwardFrom)}` : ''}`, body),
  generateMultiSourceStrategy: (service: string, body: MultiSourceStrategyRequest) =>
    send<TestStrategy>('POST', `/services/${encodeURIComponent(service)}/multi-source-strategy`, body),
  // §6 edit-then-generate over the persisted snapshot (no second pipeline run).
  renameFeature: (snapshotId: string, featureId: string, name: string) =>
    send<StrategyPreview>('PATCH', `/multi-source-strategy/snapshots/${snapshotId}/rename`, { featureId, name }),
  mergeFeatures: (snapshotId: string, featureIds: string[], name?: string) =>
    send<StrategyPreview>('PATCH', `/multi-source-strategy/snapshots/${snapshotId}/merge`, { featureIds, name }),
  pinFeature: (snapshotId: string, featureId: string, pinned: boolean) =>
    send<StrategyPreview>('PATCH', `/multi-source-strategy/snapshots/${snapshotId}/pin`, { featureId, pinned }),
  // Kicks off generation async → 202 {snapshotId,status}; poll getStrategySnapshot until generatedStrategyId/generationError.
  generateStrategyFromSnapshot: (snapshotId: string) =>
    send<StrategyAccepted>('POST', `/multi-source-strategy/snapshots/${snapshotId}/strategy`),
  getStrategySnapshot: (snapshotId: string) =>
    get<StrategyPreview>(`/multi-source-strategy/snapshots/${encodeURIComponent(snapshotId)}`),
  reviews: (targetKey: string) => get<ReviewResult[]>(`/reviews?targetKey=${encodeURIComponent(targetKey)}`),
  // Recent reviews across all targets — so prior verdicts are reopenable from the Reviews page, not lost.
  recentReviews: () => get<ReviewResult[]>('/reviews/recent'),
  // List the Xray tests a JQL selects (no review yet) so the user can pick which to review.
  reviewCandidates: (jql: string) =>
    get<ReviewCandidate[]>(`/reviews/candidates?jql=${encodeURIComponent(jql)}`),
  // testKeys (optional): review only the selected subset; omit to review every JQL match.
  runReview: (body: { jql: string; apply: boolean; owner?: string; testKeys?: string[] }) =>
    send<ReviewResult[]>('POST', '/reviews', body),
  generateTestCases: (service: string, body: { basis: string; owner?: string }) =>
    send<TestCase[]>('POST', `/services/${encodeURIComponent(service)}/test-cases`, body),

  // Test conditions (ISTQB test analysis) — derived from the strategy; the auto/manual split is decided per condition.
  testConditions: (strategyId: string) =>
    get<TestCondition[]>(`/strategies/${encodeURIComponent(strategyId)}/test-conditions`),
  analyzeConditions: (service: string, body: { basis: string; source?: string; owner?: string }) =>
    send<TestCondition[]>('POST', `/services/${encodeURIComponent(service)}/test-conditions`, body),
  patchCondition: (id: string, body: { automation?: string; status?: string; priority?: string }) =>
    send<TestCondition>('PATCH', `/test-conditions/${encodeURIComponent(id)}`, body),
  testConditionsReportUrl: (strategyId: string) => `${BASE}/strategies/${strategyId}/test-conditions/report`,
  // Tag-driven routing: which conditions feed automation (implement-tests) vs manual design (create-test-cases).
  conditionRouting: (strategyId: string) =>
    get<{ automated: string[]; manual: string[]; candidate: string[] }>(
      `/strategies/${encodeURIComponent(strategyId)}/test-conditions/routing`),

  // ── Settings: secrets, connections, test-connection, Copilot sign-in ──
  secretsStatus: () => get<SecretStatus>('/settings/secrets'),
  setSecret: (key: string, value: string) => send<void>('POST', '/settings/secrets', { key, value }),
  connections: () => get<ConnectionsCfg>('/settings/connections'),
  saveConnections: (cfg: ConnectionsCfg) => send<UpdateConnectionsResult>('PUT', '/settings/connections', cfg),
  testConnection: (service: string) => send<ConnectionTestResult>('POST', `/settings/connections/${encodeURIComponent(service)}/test`),
  llmSettings: () => get<{ active: string; desired: string; simulated: boolean; model: string; seeded?: boolean }>('/settings/llm'),
  saveLlmSettings: (mode: string) =>
    send<{ applied: boolean; restartRequiredFields: string[] }>('PUT', '/settings/llm', { mode }),
  copilotStatus: () => get<{ authenticated: boolean; connected?: boolean }>('/settings/copilot/status'),
  copilotLoginStart: () => send<CopilotLoginStart>('POST', '/settings/copilot/login/start'),
  copilotLoginStatus: (id: string) => get<CopilotLoginStatus>(`/settings/copilot/login/status?id=${encodeURIComponent(id)}`),
  copilotSignout: () => send<void>('POST', '/settings/copilot/signout'),

  // ── Snyk dependency-security module ──
  snykSummary: () => get<SnykSummary>('/snyk/summary'),
  snykOrgs: () => get<SnykOrg[]>('/snyk/orgs'),
  snykRepos: (orgId: string) => get<SnykTarget[]>(`/snyk/orgs/${encodeURIComponent(orgId)}/repos`),
  snykWatches: () => get<SnykWatchView[]>('/snyk/watches'),
  addSnykWatch: (body: { orgId: string; orgSlug: string; orgName: string; targetId: string; repoSlug: string }) =>
    send<SnykWatchView>('POST', '/snyk/watches', body),
  // App-id-centric: watch an app-id — the backend auto-targets its application-tests repo.
  addSnykWatchByApp: (body: { orgId: string; orgSlug: string; orgName: string }) =>
    send<SnykWatchView>('POST', '/snyk/watches/by-app', body),
  removeSnykWatch: (id: string) => send<void>('DELETE', `/snyk/watches/${encodeURIComponent(id)}`),
  snykIssues: (watchId: string) => get<SnykIssueView[]>(`/snyk/watches/${encodeURIComponent(watchId)}/issues`),
  // Kicks off the poll on the backend and returns 202 immediately (the slow Snyk REST calls run in the background);
  // poll snykRefreshStatus to know when it completes.
  snykRefresh: () => send<{ polled: number }>('POST', '/snyk/refresh'),
  refreshSnykWatch: (id: string) => send<void>('POST', `/snyk/watches/${encodeURIComponent(id)}/refresh`),
  snykRefreshStatus: () => get<SnykRefreshStatus>('/snyk/refresh/status'),
  snykAlerts: (unseenOnly = false) => get<SnykAlert[]>(`/snyk/alerts${unseenOnly ? '?unseenOnly=true' : ''}`),
  markSnykAlertSeen: (id: string) => send<void>('POST', `/snyk/alerts/${encodeURIComponent(id)}/seen`),

  // ── Snyk auto-fix release train ──
  startSnykFix: (body: { watchId?: string; issueId: string; coordinate: string; oldVersion: string;
    fixedIn: string; severity: string; appIds: string[]; jiraKey?: string; jiraProject?: string;
    jiraIssueType?: string; reviewers?: string[]; owner?: string; autoConfirm?: boolean }) =>
    post<{ trainId: string }>('/snyk/fixes', body),
  snykFixes: () => get<SnykFixTrainView[]>('/snyk/fixes'),
  snykFix: (id: string) => get<SnykFixTrainView>(`/snyk/fixes/${encodeURIComponent(id)}`),
  // Confirm a paused (AWAITING_CONFIRM) train with per-module version + per-step-order reviewer edits.
  confirmSnykFix: (id: string, versionOverrides: Record<string, string>,
    reviewerOverrides: Record<number, string[]>) =>
    send<SnykFixTrainView>('POST', `/snyk/fixes/${encodeURIComponent(id)}/confirm`,
      { versionOverrides, reviewerOverrides }),
  openSnykFixPrs: (id: string) => send<SnykFixTrainView>('POST', `/snyk/fixes/${encodeURIComponent(id)}/open-prs`),
  recordSnykFixPr: (id: string, order: number, prUrl: string) =>
    send<SnykFixTrainView>('POST', `/snyk/fixes/${encodeURIComponent(id)}/steps/${order}/pr`, { prUrl }),
  markSnykFixMerged: (id: string) =>
    send<SnykFixTrainView>('POST', `/snyk/fixes/${encodeURIComponent(id)}/mark-merged`),

  // ── Activity Center ──
  activity: () => get<ActivityItem[]>('/activity'),
  // Routes through send() because the controller returns a bodyless 200 — post() would choke parsing it as JSON.
  ackActivity: (ids: string[]) => send<void>('POST', '/activity/ack', { ids }),
}
