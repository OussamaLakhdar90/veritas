import { describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { Dashboard } from '../pages/Dashboard'

// ── Fixtures ────────────────────────────────────────────────────────────────
const scan = (over: Record<string, unknown> = {}) => ({
  id: 'scan-1',
  serviceName: 'policies-api',
  status: 'COMPLETED',
  totalFindings: 3,
  totalEstCostUsd: 0.1234,
  startedAt: '2026-06-20T10:00:00Z',
  specSources: 'openapi',
  ...over,
})

const defect = (over: Record<string, unknown> = {}) => ({
  id: 'def-1',
  findingId: 'fnd-1',
  createdInJira: true,
  jiraStatusCategory: 'In Progress',
  ...over,
})

const cost = (over: Record<string, unknown> = {}) => ({
  totalEstCostUsd: 4.5,
  actions: 12,
  bySkill: {},
  ...over,
})

const check = (over: Record<string, unknown> = {}) => ({
  name: 'Bitbucket connection',
  status: 'OK',
  detail: '',
  remediation: '',
  ...over,
})

/** Register all four endpoints the Dashboard reads. Pass arrays/objects per slice; defaults are empty/healthy. */
function stub(opts: {
  scans?: unknown
  defects?: unknown
  costs?: unknown
  preflight?: unknown
} = {}) {
  server.use(
    http.get('*/api/v1/scans', () => HttpResponse.json(opts.scans ?? [])),
    http.get('*/api/v1/defects', () => HttpResponse.json(opts.defects ?? [])),
    http.get('*/api/v1/costs/summary', () => HttpResponse.json(opts.costs ?? cost())),
    http.get('*/api/v1/preflight', () => HttpResponse.json(opts.preflight ?? [])),
  )
}

function renderDashboard() {
  return renderPage(<Dashboard />, {
    extraRoutes: [
      { path: '/repos', element: <div>repos-page</div> },
      { path: '/settings', element: <div>settings-page</div> },
      { path: '/findings/:scanId', element: <div>findings-page</div> },
    ],
  })
}

/**
 * Find a KPI tile Card by its label and return its container for scoped value assertions.
 * "Findings" also appears as a table header, so we match only the tile's label <p> (its
 * uppercase-muted class), not arbitrary text, then walk up to the enclosing Card.
 */
function tile(label: string): HTMLElement {
  const labelEl = screen.getByText(label, { selector: 'p.uppercase' })
  return labelEl.closest('div.rounded-xl') as HTMLElement
}

describe('Dashboard', () => {
  it('shows skeletons while scans are loading (never-resolving fetch)', async () => {
    server.use(
      http.get('*/api/v1/scans', () => new Promise(() => {})), // hangs → isLoading stays true
      http.get('*/api/v1/defects', () => HttpResponse.json([])),
      http.get('*/api/v1/costs/summary', () => HttpResponse.json(cost())),
      http.get('*/api/v1/preflight', () => HttpResponse.json([])),
    )
    const { container } = renderDashboard()

    // The page header renders immediately; the KPI tiles + table show skeleton placeholders.
    expect(await screen.findByText('Overview')).toBeInTheDocument()
    expect(container.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)
    // No real tile labels yet while loading.
    expect(screen.queryByText('Services validated')).not.toBeInTheDocument()
  })

  it('renders the empty state when there are no scans', async () => {
    stub({ scans: [] })
    renderDashboard()

    expect(await screen.findByText('No validations yet')).toBeInTheDocument()
    expect(
      screen.getByText(/Validate a service to see its findings/i),
    ).toBeInTheDocument()
    // KPI tiles still render with zeroed values.
    expect(within(tile('Services validated')).getByText('0')).toBeInTheDocument()
    expect(within(tile('Est. analysis cost')).getByText('$4.50')).toBeInTheDocument()
  })

  it('computes the KPI tiles from scans + defects + costs', async () => {
    stub({
      scans: [
        scan({ id: 's1', serviceName: 'alpha', totalFindings: 2, totalEstCostUsd: 0.5 }),
        scan({ id: 's2', serviceName: 'beta', totalFindings: 5, totalEstCostUsd: 1.0 }),
        scan({ id: 's3', serviceName: 'alpha', totalFindings: 0, totalEstCostUsd: 0.25 }), // dup service
      ],
      defects: [
        defect({ id: 'd1', jiraStatusCategory: 'In Progress' }), // open
        defect({ id: 'd2', jiraStatusCategory: 'To Do' }),       // open
        defect({ id: 'd3', jiraStatusCategory: 'Done' }),        // resolved → excluded
      ],
      costs: cost({ totalEstCostUsd: 9.99, actions: 42 }),
    })
    renderDashboard()

    // Services = unique serviceName count (alpha, beta) = 2
    expect(within(await tileAsync('Services validated')).getByText('2')).toBeInTheDocument()
    expect(within(tile('Services validated')).getByText('3 validations total')).toBeInTheDocument()
    // Findings = 2 + 5 + 0 = 7
    expect(within(tile('Findings')).getByText('7')).toBeInTheDocument()
    // Open defects = 2 (Done excluded)
    expect(within(tile('Open defects')).getByText('2')).toBeInTheDocument()
    // Spend comes from costs/summary, formatted; sub shows the AI call count.
    expect(within(tile('Est. analysis cost')).getByText('$9.99')).toBeInTheDocument()
    expect(within(tile('Est. analysis cost')).getByText('42 AI calls')).toBeInTheDocument()
  })

  it('renders the recent validations table with status pill, findings and cost', async () => {
    stub({
      scans: [
        scan({ id: 'old', serviceName: 'older-svc', startedAt: '2026-06-01T08:00:00Z' }),
        scan({
          id: 'new',
          serviceName: 'newer-svc',
          status: 'FAILED',
          totalFindings: 9,
          totalEstCostUsd: 2.5,
          startedAt: '2026-06-25T08:00:00Z',
        }),
      ],
    })
    renderDashboard()

    const table = await screen.findByRole('table')
    expect(within(table).getByText('newer-svc')).toBeInTheDocument()
    expect(within(table).getByText('older-svc')).toBeInTheDocument()
    // FAILED → friendly "Failed" label; COMPLETED → "Completed".
    expect(within(table).getByText('Failed')).toBeInTheDocument()
    expect(within(table).getByText('Completed')).toBeInTheDocument()
    // Cost formatted to 4 dp in the table.
    expect(within(table).getByText('$2.5000')).toBeInTheDocument()

    // Most-recent scan sorts first.
    const firstRowCell = within(table).getAllByRole('row')[1].querySelector('td')
    expect(firstRowCell).toHaveTextContent('newer-svc')
  })

  it('shows the setup nudge only when preflight has MISSING checks, and links to Settings', async () => {
    stub({
      scans: [scan()],
      preflight: [
        check({ name: 'Jira token', status: 'MISSING' }),
        check({ name: 'Confluence token', status: 'MISSING' }),
        check({ name: 'Bitbucket connection', status: 'OK' }),
      ],
    })
    const user = userEvent.setup()
    renderDashboard()

    expect(await screen.findByText('Finish setup (2 items)')).toBeInTheDocument()
    expect(screen.getByText(/Jira token, Confluence token/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Open Settings/ }))
    expect(await screen.findByText('settings-page')).toBeInTheDocument()
  })

  it('hides the setup nudge when nothing is MISSING', async () => {
    stub({ scans: [scan()], preflight: [check({ status: 'OK' })] })
    renderDashboard()

    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(screen.queryByText(/Finish setup/)).not.toBeInTheDocument()
  })

  it('a row "View" link navigates to the findings page', async () => {
    stub({ scans: [scan({ id: 'scan-xyz', serviceName: 'nav-svc' })] })
    const user = userEvent.setup()
    renderDashboard()

    await screen.findByText('nav-svc')
    await user.click(screen.getByRole('link', { name: /View/ }))
    expect(await screen.findByText('findings-page')).toBeInTheDocument()
  })

  it('the header "Validate a service" CTA navigates to repos', async () => {
    stub({ scans: [] })
    const user = userEvent.setup()
    renderDashboard()

    await screen.findByText('No validations yet')
    await user.click(screen.getByRole('link', { name: /Validate a service/ }))
    expect(await screen.findByText('repos-page')).toBeInTheDocument()
  })

  it('falls back to summing scan costs when costs/summary errors', async () => {
    server.use(
      http.get('*/api/v1/scans', () =>
        HttpResponse.json([
          scan({ id: 'a', totalEstCostUsd: 0.5 }),
          scan({ id: 'b', serviceName: 'svc-b', totalEstCostUsd: 0.75 }),
        ]),
      ),
      http.get('*/api/v1/defects', () => HttpResponse.json([])),
      http.get('*/api/v1/costs/summary', () =>
        HttpResponse.json({ detail: 'boom', status: 500 }, { status: 500 }),
      ),
      http.get('*/api/v1/preflight', () => HttpResponse.json([])),
    )
    renderDashboard()

    // costQ.data is undefined → spend = sum of scan costs = 1.25; sub falls back to "this environment".
    expect(within(await tileAsync('Est. analysis cost')).getByText('$1.25')).toBeInTheDocument()
    expect(within(tile('Est. analysis cost')).getByText('this environment')).toBeInTheDocument()
  })
})

/** Await the tile label appearing (post-load), then return its Card container. */
async function tileAsync(label: string): Promise<HTMLElement> {
  const labelEl = await screen.findByText(label, { selector: 'p.uppercase' })
  return labelEl.closest('div.rounded-xl') as HTMLElement
}