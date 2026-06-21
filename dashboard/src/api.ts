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
}
