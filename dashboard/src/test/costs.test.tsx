import { describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { Costs } from '../pages/Costs'

// The page only calls api.costSummary() → GET /costs/summary. We also stub /costs defensively so no
// real network leaks if the page ever fans out; MSW matches the '*/api/v1/...' wildcard regardless of origin.
const summary = (over: Record<string, unknown> = {}) => ({
  totalEstCostUsd: 1.2345,
  actions: 17,
  bySkill: {
    'reconcile': 0.8,
    'strategy': 0.3,
    'codegen': 0.1345,
  },
  ...over,
})

function stubSummary(body: unknown, init?: { status?: number }) {
  server.use(
    http.get('*/api/v1/costs/summary', () =>
      init?.status
        ? HttpResponse.json(body as Record<string, unknown>, { status: init.status })
        : HttpResponse.json(body as Record<string, unknown>)),
    http.get('*/api/v1/costs', () => HttpResponse.json([])),
  )
}

function renderCosts() {
  return renderPage(<Costs />, { path: '/costs', route: '/costs' })
}

describe('Costs page', () => {
  it('renders the page header and section titles', async () => {
    stubSummary(summary())
    renderCosts()

    expect(await screen.findByRole('heading', { name: 'LLM cost' })).toBeInTheDocument()
    expect(screen.getByText(/Estimated Copilot spend in this environment/)).toBeInTheDocument()
    expect(screen.getByText('By skill')).toBeInTheDocument()
    expect(screen.getByText('Where the spend goes.')).toBeInTheDocument()
  })

  it('shows the summary KPIs from /costs/summary (formatted to 4 dp)', async () => {
    stubSummary(summary({ totalEstCostUsd: 1.2345, actions: 17 }))
    renderCosts()

    // Total est. cost → "$1.2345" (toFixed(4)); LLM actions → 17.
    expect(await screen.findByText('$1.2345')).toBeInTheDocument()
    expect(screen.getByText('Total est. cost')).toBeInTheDocument()
    expect(screen.getByText('LLM actions')).toBeInTheDocument()
    expect(screen.getByText('17')).toBeInTheDocument()
  })

  it('renders the by-skill ledger sorted by descending cost, each cost as $x.xxxx', async () => {
    stubSummary(summary())
    renderCosts()

    // Wait for the table to populate.
    expect(await screen.findByText('reconcile')).toBeInTheDocument()

    const table = screen.getByRole('table')
    // Header columns are present.
    expect(within(table).getByText('Skill')).toBeInTheDocument()
    expect(within(table).getByText('Share')).toBeInTheDocument()
    expect(within(table).getByText('Est. cost (USD)')).toBeInTheDocument()

    // Skills appear in descending-cost order: reconcile (0.8), strategy (0.3), codegen (0.1345).
    const rows = within(table).getAllByRole('row')
    const bodyRows = rows.slice(1) // drop the header row
    const skillOrder = bodyRows.map((r) => within(r).getAllByRole('cell')[0].textContent)
    expect(skillOrder).toEqual(['reconcile', 'strategy', 'codegen'])

    // Each cost is formatted to 4 dp.
    expect(within(table).getByText('$0.8000')).toBeInTheDocument()
    expect(within(table).getByText('$0.3000')).toBeInTheDocument()
    expect(within(table).getByText('$0.1345')).toBeInTheDocument()
  })

  it('shows the empty state when there is no spend yet', async () => {
    stubSummary(summary({ totalEstCostUsd: 0, actions: 0, bySkill: {} }))
    renderCosts()

    expect(await screen.findByText('No spend yet')).toBeInTheDocument()
    expect(
      screen.getByText('Run a skill that calls the LLM and its cost will be tracked here.'),
    ).toBeInTheDocument()
    // KPIs still render their zeroed values, not the table.
    expect(screen.getByText('$0.0000')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('shows the error state when /costs/summary fails', async () => {
    stubSummary({ detail: 'The database is unavailable', status: 500 }, { status: 500 })
    renderCosts()

    // friendlyMessage rewrites 5xx → "The server hit a problem…"; ErrorState wraps it in a role="alert".
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText(/Couldn’t load this data/)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })
})
