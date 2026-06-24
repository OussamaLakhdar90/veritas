const BASE = '/api/v1'

/**
 * Global hook the Copilot auth-gate registers: when any API call fails with the RFC-7807
 * `code: "copilot-auth-required"`, we invoke this so the UI can pop the sign-in flow instead of just
 * showing a red toast. Set by CopilotAuthProvider; null otherwise.
 */
let copilotAuthHandler: (() => void) | null = null
export function onCopilotAuthRequired(fn: (() => void) | null) { copilotAuthHandler = fn }

/** Turn a non-OK response into a friendly Error, and fire the Copilot sign-in hook if that's the cause. */
async function fail(res: Response): Promise<never> {
  let detail = `${res.status} ${res.statusText}`
  let code: string | undefined
  try {
    const j = await res.json()
    if (j) { detail = j.detail || j.message || detail; code = j.code }
  } catch { /* non-JSON body */ }
  if (code === 'copilot-auth-required' && copilotAuthHandler) copilotAuthHandler()
  throw new Error(detail)
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(BASE + path)
  if (!res.ok) return fail(res)
  return res.json() as Promise<T>
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) return fail(res)
  return res.json() as Promise<T>
}

/** Method-flexible sender that tolerates 204 and surfaces an RFC-7807 `detail`/`message` on error (friendly toasts). */
async function send<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(BASE + path, {
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
export interface ConnectionsCfg { bitbucket: EndpointCfg; jira: EndpointCfg; confluence: EndpointCfg; xray: EndpointCfg }
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
  specSources: string
  errorMessage?: string
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
export interface PreflightCheck { name: string; status: string; detail: string; remediation: string }

export interface DefectLink {
  id: string
  findingId: string
  scanId?: string
  jiraKey?: string
  jiraUrl?: string
  jiraStatus?: string
  jiraStatusCategory?: string
  createdInJira: boolean
  createdBy?: string
  lastSyncedAt?: string
  createdAt?: string
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
  branch?: string
  prUrl?: string
  buildStatus?: string
  filesWritten?: string
  todos?: string
  approvedBy?: string
  estCostUsd?: number
  createdAt?: string
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
  confidence?: number
  deliverableJson?: string
  contentMarkdown?: string
  createdAt?: string
}

export interface ReviewResult {
  id: string
  targetKey?: string
  verdict?: string
  score?: number
  confidence?: number
  deliverableJson?: string
  createdAt?: string
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
  repos: (appId: string) => get<Repo[]>(`/repos?appId=${encodeURIComponent(appId)}`),
  branches: (appId: string, slug: string) =>
    get<string[]>(`/repos/${encodeURIComponent(slug)}/branches?appId=${encodeURIComponent(appId)}`),
  createDefect: (findingId: string, projectKey: string) =>
    post<DefectResult>(`/findings/${findingId}/defect`, { projectKey, issueType: 'Bug' }),
  triggerScan: (body: { serviceName?: string; appId?: string; repoSlug?: string; branch?: string; repoPath?: string;
    specPaths?: string[]; specSources?: { kind: string; ref: string }[]; llmEnabled?: boolean }) =>
    post<ScanAccepted>('/scans', body),
  reportUrl: (scanId: string) => `${BASE}/scans/${scanId}/report`,
  testPlans: () => get<TestPlan[]>('/test-plans'),
  testPlan: (id: string) => get<TestPlanDetail>(`/test-plans/${id}`),
  costSummary: () => get<CostSummary>('/costs/summary'),
  preflight: () => get<PreflightCheck[]>('/preflight'),
  defects: () => get<DefectLink[]>('/defects'),
  syncDefects: () => post<{ updated: number }>('/defects/sync', {}),
  gates: (status = 'PENDING') => get<GateDecision[]>(`/gates?status=${encodeURIComponent(status)}`),
  approveGate: (id: string, approver = 'dashboard') => post<GateDecision>(`/gates/${id}/approve`, { approver }),
  rejectGate: (id: string, approver = 'dashboard', note = '') => post<GateDecision>(`/gates/${id}/reject`, { approver, note }),

  // Codegen (Generate-Tests workspace)
  codegenRuns: () => get<CodegenRun[]>('/codegen-runs'),
  codegenRun: (id: string) => get<CodegenRun>(`/codegen-runs/${id}`),
  publishCodegen: (id: string, repoSlug: string, targetBranch = 'main', allowFailedBuild = false) =>
    post<CodegenRun>(`/codegen-runs/${id}/publish?repoSlug=${encodeURIComponent(repoSlug)}&targetBranch=${encodeURIComponent(targetBranch)}&allowFailedBuild=${allowFailedBuild}`, {}),

  // Release test plan trigger + RTM workspace
  triggerReleasePlan: (service: string, body: { fixVersion: string; issuesJql?: string; testsJql?: string; projectKey?: string; createGaps?: boolean }) =>
    post<{ planId: string; total: number; matched: number; gaps: number; created: number; orphans: number; confidence?: number; risks?: number }>(`/services/${encodeURIComponent(service)}/release-test-plans`, body),
  testPlanReportUrl: (id: string, format = 'html') => `${BASE}/test-plans/${id}/report?format=${format}`,

  // Test cases (RTM per-row actions + create-approved)
  testCases: (service: string) => get<TestCase[]>(`/services/${encodeURIComponent(service)}/test-cases`),
  patchTestCase: (id: string, body: { status?: string; title?: string; actor?: string }) =>
    fetch(`${BASE}/test-cases/${id}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then((r) => r.json() as Promise<TestCase>),
  pushTestCase: (id: string, projectKey: string) => post<TestCase>(`/test-cases/${id}/push`, { projectKey }),

  // Findings disposition (accept/reject/triage) — server records who/when (+ optional why note).
  // Routes through send() so a 4xx/5xx surfaces a real error (and the Copilot auth gate) instead of a false success.
  patchFinding: (id: string, status: string, note?: string) =>
    send<Finding>('PATCH', `/findings/${id}`, { status, note }),

  // Strategies / Reviews
  strategies: (service: string) => get<TestStrategy[]>(`/services/${encodeURIComponent(service)}/strategies`),
  generateStrategy: (service: string, body: { basis: string; source?: string; owner?: string }) =>
    send<TestStrategy>('POST', `/services/${encodeURIComponent(service)}/strategies`, body),
  strategyRationaleUrl: (id: string) => `${BASE}/strategies/${id}/rationale`,
  reviews: (targetKey: string) => get<ReviewResult[]>(`/reviews?targetKey=${encodeURIComponent(targetKey)}`),
  runReview: (body: { jql: string; apply: boolean; owner?: string }) => send<ReviewResult[]>('POST', '/reviews', body),
  generateTestCases: (service: string, body: { basis: string; owner?: string }) =>
    send<TestCase[]>('POST', `/services/${encodeURIComponent(service)}/test-cases`, body),

  // ── Settings: secrets, connections, test-connection, Copilot sign-in ──
  secretsStatus: () => get<SecretStatus>('/settings/secrets'),
  setSecret: (key: string, value: string) => send<void>('POST', '/settings/secrets', { key, value }),
  connections: () => get<ConnectionsCfg>('/settings/connections'),
  saveConnections: (cfg: ConnectionsCfg) => send<UpdateConnectionsResult>('PUT', '/settings/connections', cfg),
  testConnection: (service: string) => send<ConnectionTestResult>('POST', `/settings/connections/${encodeURIComponent(service)}/test`),
  llmSettings: () => get<{ active: string; desired: string; simulated: boolean; model: string }>('/settings/llm'),
  saveLlmSettings: (mode: string) =>
    send<{ applied: boolean; restartRequiredFields: string[] }>('PUT', '/settings/llm', { mode }),
  copilotStatus: () => get<{ authenticated: boolean; connected?: boolean }>('/settings/copilot/status'),
  copilotLoginStart: () => send<CopilotLoginStart>('POST', '/settings/copilot/login/start'),
  copilotLoginStatus: (id: string) => get<CopilotLoginStatus>(`/settings/copilot/login/status?id=${encodeURIComponent(id)}`),
  copilotSignout: () => send<void>('POST', '/settings/copilot/signout'),
}
