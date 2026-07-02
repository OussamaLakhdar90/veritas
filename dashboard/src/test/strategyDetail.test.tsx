import { describe, expect, it, vi } from 'vitest'
import { screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse, delay } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { StrategyDetail } from '../pages/StrategyDetail'

const deliverable = {
  summary: 'A risk-based strategy for the policies service covering create, fetch and update flows.',
  scope: { objectives: ['Cover the create-policy contract'], inScope: ['Policy retrieval'], outOfScope: ['Billing'] },
  riskRegister: [
    { id: 'R1', description: 'Pagination untested', level: 'HIGH', mitigation: 'Add boundary cases' },
    { id: 'R2', description: 'Token expiry', level: 'LOW' },
  ],
  testApproach: { levels: ['System'], types: ['Functional'], techniques: [{ name: 'Boundary value analysis', rationale: 'edges' }] },
  exitCriteria: [{ criterion: 'All blockers closed', metric: '0 open', smart: true }],
  selfReview: { confidence: 78, rubricChecks: [{ check: 'Requirements traced', pass: true }], blindSpots: ['Load not modelled'] },
}

const scorecard = {
  verdict: 'DEGRADED', confidence: 72, featuresCovered: 4, droppedSections: 1,
  checks: [{ name: 'All features have a risk', passed: true, detail: '' }, { name: 'No empty sections', passed: false, detail: '1 section dropped' }],
}

const strategy = {
  id: 's1', serviceName: 'ciam-policies', status: 'DRAFT', source: 'multi-source', confidence: 78,
  version: 2, deliverableJson: JSON.stringify(deliverable), scorecardJson: JSON.stringify(scorecard), createdAt: '2026-06-26T10:00:00Z',
}

const versions = [
  { id: 's1', serviceName: 'ciam-policies', status: 'DRAFT', version: 2, revisedBy: 'alice', createdAt: '2026-06-26T10:00:00Z' },
  { id: 's0', serviceName: 'ciam-policies', status: 'DRAFT', version: 1, revisedBy: 'api', createdAt: '2026-06-25T10:00:00Z' },
]

function stub(over: { strategy?: Record<string, unknown> | null; status?: number } = {}) {
  server.use(
    http.get('*/api/v1/strategies/:id', () =>
      over.status && over.status >= 400
        ? HttpResponse.json({ detail: 'not found', status: over.status }, { status: over.status })
        : HttpResponse.json(over.strategy ?? strategy)),
    http.get('*/api/v1/strategies/:id/versions', () => HttpResponse.json(versions)),
  )
}

function renderDetail(id = 's1') {
  return renderPage(<StrategyDetail />, { path: '/test-strategy/:id', route: `/test-strategy/${id}` })
}

describe('StrategyDetail', () => {
  it('shows a loading state while the strategy loads', async () => {
    server.use(http.get('*/api/v1/strategies/:id', async () => { await delay('infinite'); return HttpResponse.json(strategy) }))
    server.use(http.get('*/api/v1/strategies/:id/versions', () => HttpResponse.json(versions)))
    renderDetail()
    expect(await screen.findByText(/Loading/)).toBeInTheDocument()
  })

  it('surfaces a friendly error when the strategy cannot be loaded', async () => {
    stub({ status: 404 })
    renderDetail()
    expect(await screen.findByText(/Could not load strategy/)).toBeInTheDocument()
  })

  it('renders the header, the six sections and the scorecard', async () => {
    stub()
    renderDetail()
    expect(await screen.findByRole('heading', { name: /ciam-policies — Test Strategy/ })).toBeInTheDocument()
    expect(screen.getByText(/v2 · DRAFT · 78% confidence/)).toBeInTheDocument()
    // Sections
    expect(screen.getByText(/risk-based strategy for the policies service/)).toBeInTheDocument()
    expect(screen.getByText('Cover the create-policy contract')).toBeInTheDocument()
    const riskRow = screen.getByText('Pagination untested').closest('tr')!
    expect(within(riskRow).getByText('R1')).toBeInTheDocument()
    expect(within(riskRow).getByText('HIGH')).toBeInTheDocument()
    expect(screen.getByText('Boundary value analysis')).toBeInTheDocument()
    expect(screen.getByText(/All blockers closed/)).toBeInTheDocument()
    expect(screen.getByText('Load not modelled')).toBeInTheDocument()
    // Scorecard banner
    expect(screen.getByText('DEGRADED')).toBeInTheDocument()
    // "1 section dropped" appears in both the scorecard banner and the rubric-check detail.
    expect(screen.getAllByText(/1 section dropped/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('No empty sections')).toBeInTheDocument()
  })

  it('lists the version history', async () => {
    stub()
    renderDetail()
    expect(await screen.findByText('Version history')).toBeInTheDocument()
    expect(screen.getByText('v1')).toBeInTheDocument()
    expect(screen.getByText('alice')).toBeInTheDocument()
    // The current version is marked, the other is an Open link.
    expect(screen.getByText('current')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open' })).toHaveAttribute('href', '/test-strategy/s0')
  })

  it('approves a DRAFT strategy', async () => {
    stub()
    let approved = false
    server.use(http.post('*/api/v1/strategies/:id/approve', async () => {
      approved = true
      return HttpResponse.json({ ...strategy, status: 'APPROVED' })
    }))
    renderDetail()
    const btn = await screen.findByRole('button', { name: /Approve/ })
    await userEvent.click(btn)
    await waitFor(() => expect(approved).toBe(true))
  })

  it('hides Approve and shows the locked banner when already APPROVED', async () => {
    stub({ strategy: { ...strategy, status: 'APPROVED' } })
    renderDetail()
    expect(await screen.findByText(/This version is/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Approve$/ })).not.toBeInTheDocument()
  })

  it('saves an edited section as a new version', async () => {
    stub()
    let patched: { content?: string; key?: string } | null = null
    server.use(http.patch('*/api/v1/strategies/:id/sections/:key', async ({ request, params }) => {
      patched = { ...(await request.json() as { content?: string }), key: params.key as string }
      return HttpResponse.json(strategy)
    }))
    renderDetail()
    // Open the Summary section editor (the first "Edit" button).
    const editButtons = await screen.findAllByRole('button', { name: 'Edit' })
    await userEvent.click(editButtons[0])
    const box = await screen.findByLabelText(/Section content/)
    await userEvent.clear(box)
    await userEvent.type(box, 'Revised summary')
    await userEvent.click(screen.getByRole('button', { name: /Save version/ }))
    await waitFor(() => expect(patched).toMatchObject({ content: 'Revised summary', key: 'summary' }))
  })

  it('regenerates a section with the assistant', async () => {
    stub()
    let regenerated = false
    server.use(http.post('*/api/v1/strategies/:id/sections/:key/regenerate', async () => {
      regenerated = true
      return HttpResponse.json(strategy)
    }))
    renderDetail()
    const editButtons = await screen.findAllByRole('button', { name: 'Edit' })
    await userEvent.click(editButtons[0])
    await userEvent.click(await screen.findByRole('button', { name: /Regenerate/ }))
    await waitFor(() => expect(regenerated).toBe(true))
  })
})
