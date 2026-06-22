const BASE = '/api/v1'

async function get<T>(path: string): Promise<T> {
  const res = await fetch(BASE + path)
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText}`)
  }
  return res.json() as Promise<T>
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText}`)
  }
  return res.json() as Promise<T>
}

/** Method-flexible sender that tolerates 204 and surfaces an RFC-7807 `detail`/`message` on error (friendly toasts). */
async function send<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(BASE + path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`
    try { const j = await res.json(); if (j && (j.detail || j.message)) detail = j.detail || j.message } catch { /* non-JSON */ }
    throw new Error(detail)
  }
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
  totalFindings: number
  totalEstCostUsd: number
  startedAt: string
  specSources: string
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
}

export interface Repo {
  slug: string
  name: string
  description: string
  defaultBranch: string
  projectKey: string
}

export interface DefectResult { jiraKey: string; jiraUrl: string }
export interface ScanResult { scanId: string; status: string; totalFindings: number }

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
  scans: () => get<Scan[]>('/scans'),
  findings: (scanId: string) => get<Finding[]>(`/scans/${scanId}/findings`),
  repos: (appId: string) => get<Repo[]>(`/repos?appId=${encodeURIComponent(appId)}`),
  createDefect: (findingId: string, projectKey: string) =>
    post<DefectResult>(`/findings/${findingId}/defect`, { projectKey, issueType: 'Bug' }),
  triggerScan: (body: { appId?: string; repoSlug?: string; branch?: string; repoPath?: string; specPaths: string[] }) =>
    post<ScanResult>('/scans', body),
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

  // Findings triage
  patchFinding: (id: string, status: string) =>
    fetch(`${BASE}/findings/${id}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status }) }).then((r) => r.json() as Promise<Finding>),

  // Strategies / Reviews
  strategies: (service: string) => get<TestStrategy[]>(`/services/${encodeURIComponent(service)}/strategies`),
  reviews: (targetKey: string) => get<ReviewResult[]>(`/reviews?targetKey=${encodeURIComponent(targetKey)}`),

  // ── Settings: secrets, connections, test-connection, Copilot sign-in ──
  secretsStatus: () => get<SecretStatus>('/settings/secrets'),
  setSecret: (key: string, value: string) => send<void>('POST', '/settings/secrets', { key, value }),
  connections: () => get<ConnectionsCfg>('/settings/connections'),
  saveConnections: (cfg: ConnectionsCfg) => send<UpdateConnectionsResult>('PUT', '/settings/connections', cfg),
  testConnection: (service: string) => send<ConnectionTestResult>('POST', `/settings/connections/${encodeURIComponent(service)}/test`),
  copilotStatus: () => get<{ authenticated: boolean }>('/settings/copilot/status'),
  copilotLoginStart: () => send<CopilotLoginStart>('POST', '/settings/copilot/login/start'),
  copilotLoginStatus: (id: string) => get<CopilotLoginStatus>(`/settings/copilot/login/status?id=${encodeURIComponent(id)}`),
  copilotSignout: () => send<void>('POST', '/settings/copilot/signout'),
}
