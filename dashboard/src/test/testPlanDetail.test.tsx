import { describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse, delay } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { TestPlanDetail } from '../pages/TestPlanDetail'

// The deliverableJson the backend stores on the plan: the full ISTQB consultant deliverable.
const deliverable = {
  executiveSummary: 'Release readiness for the policies service is on track with two open risks.',
  scope: {
    objectives: ['Validate the new GET /policies contract', 'Confirm backward compatibility'],
    inScope: ['Policy retrieval', 'Policy creation'],
    outOfScope: ['Billing integration'],
    assumptions: ['Staging mirrors production'],
  },
  riskRegister: [
    { id: 'R1', description: 'Pagination not covered by automation', qualityCharacteristic: 'Functional suitability',
      likelihood: 'H', impact: 'M', level: 'HIGH', mitigation: 'Add boundary cases', citation: 'JIRA-12' },
    { id: 'R2', description: 'Auth token expiry untested', level: 'LOW' },
  ],
  testApproach: {
    levels: ['System', 'Integration'],
    types: ['Functional', 'Security'],
    techniques: [
      { name: 'Boundary value analysis', rationale: 'Pagination edges', citation: 'ISTQB-4.2' },
      { name: 'Exploratory' },
    ],
    entryCriteria: ['Build deployed to staging', 'Test data seeded'],
  },
  exitCriteria: [
    { criterion: 'All BLOCKER findings closed', metric: '0 open blockers', smart: true, citation: 'EXIT-1' },
    { criterion: 'Coverage above threshold', smart: false },
  ],
  estimation: { technique: 'Three-point', effortDays: 5, basis: 'historical velocity', citation: 'EST-1' },
  selfReview: {
    confidence: 82,
    rubricChecks: [
      { check: 'Every requirement traced', pass: true, note: 'RTM complete' },
      { check: 'Negative paths covered', pass: false },
    ],
    blindSpots: ['Performance under load not modelled'],
  },
}

const plan = {
  id: 'p1',
  serviceName: 'policies-service',
  kind: 'RELEASE_TEST_PLAN',
  fixVersion: '2.4.0',
  status: 'DRAFT',
  estCostUsd: 0.1234,
  deliverableJson: JSON.stringify(deliverable),
}

const coverage = [
  { requirementKey: 'REQ-1', requiredCaseRef: 'Retrieve policy by id', dimension: 'Functional',
    matchStatus: 'MATCHED', matchedTestKey: 'CIAM-T100' },
  { requirementKey: 'REQ-2', requiredCaseRef: 'Reject expired token', dimension: 'Security',
    matchStatus: 'GAP' },
]

function stubPlan(body: Record<string, unknown> | null, init?: { status?: number }) {
  server.use(
    http.get('*/api/v1/test-plans/:id', () =>
      init?.status && init.status >= 400
        ? HttpResponse.json({ detail: 'Plan p1 was not found', status: init.status }, { status: init.status })
        : HttpResponse.json(body)),
  )
}

function renderDetail() {
  return renderPage(<TestPlanDetail />, { path: '/test-plans/:id', route: '/test-plans/p1' })
}

describe('TestPlanDetail (RTM)', () => {
  it('shows a loading state while the plan request is in flight', async () => {
    server.use(
      http.get('*/api/v1/test-plans/:id', async () => {
        await delay('infinite')
        return HttpResponse.json({ plan, coverage })
      }),
    )
    renderDetail()
    expect(await screen.findByText(/Loading/)).toBeInTheDocument()
  })

  it('surfaces a friendly error when the plan cannot be loaded', async () => {
    stubPlan(null, { status: 404 })
    renderDetail()
    expect(await screen.findByText(/Could not load plan/)).toBeInTheDocument()
    // The friendly 404 message from api.ts is appended after the colon.
    expect(screen.getByText(/couldn't find that/i)).toBeInTheDocument()
  })

  it('renders the header, fix version, cost and self-review confidence', async () => {
    stubPlan({ plan, coverage })
    renderDetail()

    expect(await screen.findByRole('heading', {
      name: /policies-service — Release Test Plan \(2\.4\.0\)/,
    })).toBeInTheDocument()
    // subtitle: humanized kind · status · localized est. cost (raw enums never reach the screen)
    expect(screen.getByText(/Release test plan · Draft · est\. \$0\.1234/)).toBeInTheDocument()
    // confidence comes from deliverable.selfReview.confidence (82) → rounded
    expect(screen.getByText('82%')).toBeInTheDocument()
    expect(screen.getByText('self-review confidence')).toBeInTheDocument()
  })

  it('links the HTML and PDF reports to the report endpoint with the right format', async () => {
    stubPlan({ plan, coverage })
    renderDetail()

    const html = await screen.findByRole('link', { name: /HTML/ })
    const pdf = screen.getByRole('link', { name: /PDF/ })
    expect(html).toHaveAttribute('href', '/api/v1/test-plans/p1/report?format=html')
    expect(pdf).toHaveAttribute('href', '/api/v1/test-plans/p1/report?format=pdf')
    expect(html).toHaveAttribute('target', '_blank')
  })

  it('renders the RTM coverage rows with requirement, dimension, status and matched test', async () => {
    stubPlan({ plan, coverage })
    renderDetail()

    // Section title carries the coverage count.
    expect(await screen.findByText('Requirements Traceability Matrix (2)')).toBeInTheDocument()

    // A MATCHED row exposes its requirement key + matched Xray test.
    const matchedRow = screen.getByText('REQ-1').closest('tr')!
    expect(within(matchedRow).getByText('Retrieve policy by id')).toBeInTheDocument()
    expect(within(matchedRow).getByText('Functional')).toBeInTheDocument()
    expect(within(matchedRow).getByText('Covered')).toBeInTheDocument()
    expect(within(matchedRow).getByText('CIAM-T100')).toBeInTheDocument()

    // A GAP row has no matched test → falls back to the em-dash.
    const gapRow = screen.getByText('REQ-2').closest('tr')!
    expect(within(gapRow).getByText('Gap')).toBeInTheDocument()
    expect(within(gapRow).getByText('—')).toBeInTheDocument()
  })

  it('renders the executive summary, scope, risk register, approach, exit criteria, estimation and self-review', async () => {
    stubPlan({ plan, coverage })
    renderDetail()

    expect(await screen.findByText(deliverable.executiveSummary)).toBeInTheDocument()

    // Scope objectives + in/out of scope + assumptions
    expect(screen.getByText('Validate the new GET /policies contract')).toBeInTheDocument()
    expect(screen.getByText('Policy retrieval')).toBeInTheDocument()
    expect(screen.getByText('Billing integration')).toBeInTheDocument()
    expect(screen.getByText(/Assumptions: Staging mirrors production/)).toBeInTheDocument()

    // Risk register section title + a risk row
    expect(screen.getByText('Risk register (2)')).toBeInTheDocument()
    const riskRow = screen.getByText('Pagination not covered by automation').closest('tr')!
    expect(within(riskRow).getByText('R1')).toBeInTheDocument()
    expect(within(riskRow).getByText('HIGH')).toBeInTheDocument()
    expect(within(riskRow).getByText('Add boundary cases')).toBeInTheDocument()

    // Test approach: levels/types + technique row
    expect(screen.getByText(/System, Integration/)).toBeInTheDocument()
    expect(screen.getByText('Boundary value analysis')).toBeInTheDocument()
    expect(screen.getByText(/Entry: Build deployed to staging/)).toBeInTheDocument()

    // Exit criteria (S.M.A.R.T.)
    expect(screen.getByText('All BLOCKER findings closed')).toBeInTheDocument()
    expect(screen.getByText('0 open blockers')).toBeInTheDocument()

    // Estimation
    expect(screen.getByText(/Three-point · 5 person-days/)).toBeInTheDocument()

    // Self-review rubric + blind spots
    expect(screen.getByText(/Every requirement traced/)).toBeInTheDocument()
    expect(screen.getByText('Performance under load not modelled')).toBeInTheDocument()
  })

  it('lazily reads execution status on demand: prefilled JQL, segmented bar and verdict', async () => {
    stubPlan({ plan, coverage })
    let called = false
    server.use(
      http.get('*/api/v1/execution/completion', ({ request }) => {
        called = true
        // The JQL is prefilled from the plan's fixVersion.
        expect(new URL(request.url).searchParams.get('jql')).toContain('2.4.0')
        return HttpResponse.json({
          jql: 'fixVersion = "2.4.0"', total: 10, passed: 6, failed: 2, blocked: 1, notRun: 1,
          deviations: [{ testKey: 'CIAM-T9', rawStatus: 'ABORTED', outcome: 'failed' }], verdict: 'FAIL',
        })
      }),
    )
    const user = userEvent.setup()
    renderDetail()

    // The card renders but does NOT fetch until the button is clicked (lazy).
    expect(await screen.findByText('Execution status')).toBeInTheDocument()
    expect(called).toBe(false)

    await user.click(screen.getByRole('button', { name: /Check execution/ }))

    expect(await screen.findByText('10 tests')).toBeInTheDocument()
    expect(screen.getByText('Fail')).toBeInTheDocument()          // verdict badge (humanized)
    expect(screen.getByText('CIAM-T9')).toBeInTheDocument()       // a deviation row
    expect(called).toBe(true)
  })

  it('handles a minimal plan: empty coverage, no deliverable, confidence from the plan field', async () => {
    const bare = {
      plan: {
        id: 'p2', serviceName: 'bare-service', kind: 'RELEASE_TEST_PLAN', status: 'NEW',
        confidence: 60, deliverableJson: undefined,
      },
      coverage: [],
    }
    stubPlan(bare)
    renderDetail()

    // No fixVersion → no parenthetical in the title.
    expect(await screen.findByRole('heading', { name: /bare-service — Release Test Plan$/ })).toBeInTheDocument()
    // Empty RTM still renders its section header with a (0) count.
    expect(screen.getByText('Requirements Traceability Matrix (0)')).toBeInTheDocument()
    // Confidence falls back to plan.confidence (60) and renders as a warning (< 70).
    expect(screen.getByText('60%')).toBeInTheDocument()
    // The optional deliverable sections are absent.
    expect(screen.queryByText('Executive summary')).not.toBeInTheDocument()
    expect(screen.queryByText(/Risk register/)).not.toBeInTheDocument()
  })

  it('tolerates malformed deliverableJson without crashing the page', async () => {
    const broken = {
      plan: { id: 'p3', serviceName: 'broken-service', kind: 'RELEASE_TEST_PLAN', status: 'DRAFT',
        estCostUsd: 0, deliverableJson: '{not valid json' },
      coverage,
    }
    stubPlan(broken)
    renderDetail()

    // Header still renders; the RTM still shows even though the deliverable failed to parse.
    expect(await screen.findByRole('heading', { name: /broken-service/ })).toBeInTheDocument()
    expect(screen.getByText('Requirements Traceability Matrix (2)')).toBeInTheDocument()
    // No confidence card (neither deliverable nor plan.confidence present).
    expect(screen.queryByText('self-review confidence')).not.toBeInTheDocument()
  })
})