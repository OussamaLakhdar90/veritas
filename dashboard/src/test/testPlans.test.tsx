import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { TestPlans } from '../pages/TestPlans'

const plan = (over: Record<string, unknown> = {}) => ({
  id: 'tp-1',
  serviceName: 'ciam-policies',
  kind: 'RELEASE',
  fixVersion: '8.2',
  status: 'PROPOSED',
  confidence: 87.4,
  riskCount: 5,
  estCostUsd: 0.1234,
  ...over,
})

function renderTestPlans() {
  return renderPage(<TestPlans />, {
    extraRoutes: [{ path: '/test-plans/:id', element: <div>plan-detail-page</div> }],
  })
}

describe('TestPlans page', () => {
  it('shows the empty state when there are no plans yet', async () => {
    server.use(http.get('*/api/v1/test-plans', () => HttpResponse.json([])))
    renderTestPlans()

    expect(await screen.findByText('No test plans yet')).toBeInTheDocument()
    // The trigger form is always present, regardless of the list state.
    expect(screen.getByRole('button', { name: /Generate plan/ })).toBeInTheDocument()
  })

  it('renders the plans list with service, fix version, status, confidence and cost', async () => {
    server.use(http.get('*/api/v1/test-plans', () => HttpResponse.json([plan()])))
    renderTestPlans()

    expect(await screen.findByText('ciam-policies')).toBeInTheDocument()
    expect(screen.getByText('Release')).toBeInTheDocument()       // planKind enum → humanized
    expect(screen.getByText('8.2')).toBeInTheDocument()
    expect(screen.getByText('Proposed')).toBeInTheDocument()      // unknown status → prettified, never raw
    expect(screen.getByText('87%')).toBeInTheDocument()       // confidence rounded
    expect(screen.getByText('5')).toBeInTheDocument()          // risk count
    expect(screen.getByText('$0.1234')).toBeInTheDocument()    // est cost, 4dp
    // Empty state must NOT show when there is data.
    expect(screen.queryByText('No test plans yet')).not.toBeInTheDocument()
  })

  it('surfaces an error state when the list fetch fails', async () => {
    server.use(http.get('*/api/v1/test-plans', () =>
      HttpResponse.json({ detail: 'boom', status: 500 }, { status: 500 })))
    renderTestPlans()

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByText('No test plans yet')).not.toBeInTheDocument()
  })

  it('validates required fields before firing the trigger POST', async () => {
    server.use(http.get('*/api/v1/test-plans', () => HttpResponse.json([])))
    const user = userEvent.setup()
    renderTestPlans()

    expect(await screen.findByText('No test plans yet')).toBeInTheDocument()
    // Click Generate with both fields blank → validation toast, no network call.
    await user.click(screen.getByRole('button', { name: /Generate plan/ }))
    expect(await screen.findByText('Service and fix version are required.')).toBeInTheDocument()
  })

  it('fills the form, fires the trigger POST and shows the success toast', async () => {
    let posted: unknown = null
    server.use(
      http.get('*/api/v1/test-plans', () => HttpResponse.json([])),
      http.post('*/api/v1/services/:service/release-test-plans', async ({ request }) => {
        posted = await request.json()
        return HttpResponse.json({ planId: 'tp-9', total: 10, matched: 7, gaps: 3, created: 2, orphans: 1 })
      }),
    )
    const user = userEvent.setup()
    renderTestPlans()

    expect(await screen.findByText('No test plans yet')).toBeInTheDocument()
    await user.type(screen.getByPlaceholderText('ciam-policies'), 'ciam-policies')
    await user.type(screen.getByPlaceholderText('8.2'), '8.2')
    await user.type(screen.getByPlaceholderText('CIAM'), 'CIAM')
    await user.click(screen.getByLabelText(/Create gap tests/))
    await user.click(screen.getByRole('button', { name: /Generate plan/ }))

    expect(await screen.findByText('Plan ready — 7 matched, 3 gaps, 2 created.')).toBeInTheDocument()
    expect(posted).toEqual({ fixVersion: '8.2', projectKey: 'CIAM', createGaps: true })
  })

  it('shows an error toast when the trigger POST fails', async () => {
    server.use(
      http.get('*/api/v1/test-plans', () => HttpResponse.json([])),
      http.post('*/api/v1/services/:service/release-test-plans', () =>
        HttpResponse.json({ detail: 'No issues matched that fix version.', status: 400 }, { status: 400 })),
    )
    const user = userEvent.setup()
    renderTestPlans()

    expect(await screen.findByText('No test plans yet')).toBeInTheDocument()
    await user.type(screen.getByPlaceholderText('ciam-policies'), 'ciam-policies')
    await user.type(screen.getByPlaceholderText('8.2'), '8.2')
    await user.click(screen.getByRole('button', { name: /Generate plan/ }))

    // The server's 4xx detail is trusted as-is and surfaced as an error toast (role=alert).
    expect(await screen.findByText('No issues matched that fix version.')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })
})
