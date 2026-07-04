import { describe, expect, it } from 'vitest'
import { screen, within, waitFor } from '@testing-library/react'
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
  finishedAt: '2026-06-20T10:03:42Z',
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
      { path: '/snyk', element: <div>snyk-page</div> },
    ],
  })
}

/** One unseen Snyk alert row (the shape ActivityBell + the Overview banner read). */
const alert = (over: Record<string, unknown> = {}) => ({
  id: 'al-1', watchId: 'w1', orgSlug: 'app7576', repoSlug: 'application-tests',
  severity: 'high', message: 'New high vulnerability', seen: false, ...over,
})

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
  it('shows the scorecard-by-service panel from the service catalog', async () => {
    stub({ scans: [] })
    server.use(http.get('*/api/v1/services', () => HttpResponse.json([
      { name: 'ciam-policies', strategies: 1, conditions: 9, cases: 12, plans: 0, scans: 5, codegenRuns: 4 },
    ])))
    renderDashboard()

    expect(await screen.findByText('Scorecard by service')).toBeInTheDocument()
    const panel = screen.getByText('Scorecard by service').closest('div.rounded-xl') as HTMLElement
    expect(within(panel).getByText('ciam-policies')).toBeInTheDocument()
    expect(within(panel).getByText('9 conditions')).toBeInTheDocument()
    expect(within(panel).getByText('12 cases')).toBeInTheDocument()
  })

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
    // The Findings tile became the breaking-changes KPI (from /summary/executive — 0 in the default stub).
    expect(within(tile('Breaking changes caught')).getByText('0')).toBeInTheDocument()
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
          finishedAt: '2026-06-25T08:03:42Z',
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
    // The audit punchline: duration · localized 2-dp cost.
    expect(within(table).getByText('3 min 42 s · $2.50')).toBeInTheDocument()

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

  it('shows an error banner when the scans fetch fails (no misleading zeros alone)', async () => {
    server.use(
      http.get('*/api/v1/scans', () => HttpResponse.json({ detail: 'The database is unavailable', status: 500 }, { status: 500 })),
      http.get('*/api/v1/defects', () => HttpResponse.json([])),
      http.get('*/api/v1/costs/summary', () => HttpResponse.json(cost())),
      http.get('*/api/v1/preflight', () => HttpResponse.json([])),
    )
    renderDashboard()

    expect(await screen.findByText(/Couldn’t load this data/)).toBeInTheDocument()
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  it('renders the fidelity score-history chart from the /summary/fidelity-trend series', async () => {
    stub({ scans: [scan({ id: 's1', serviceName: 'alpha', fidelityScore: 60 })] })
    // A proper daily-bucket series from the backend (not replayed client-side): a rising portfolio mean.
    server.use(http.get('*/api/v1/summary/fidelity-trend', () => HttpResponse.json([
      { date: '2026-06-24', value: 70 },
      { date: '2026-06-25', value: 82 },
    ])))
    renderDashboard()

    // The chart card only draws once the backend returns ≥2 points; its title + subtitle confirm the wiring.
    expect(await screen.findByText('Portfolio fidelity over time')).toBeInTheDocument()
  })

  it('hides the fidelity score-history chart when the trend series is too short', async () => {
    stub({ scans: [scan({ id: 's1', serviceName: 'alpha', fidelityScore: 60 })] })
    server.use(http.get('*/api/v1/summary/fidelity-trend', () => HttpResponse.json([
      { date: '2026-06-25', value: 82 },
    ])))
    renderDashboard()

    await screen.findByRole('table')
    expect(screen.queryByText('Portfolio fidelity over time')).not.toBeInTheDocument()
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

  it('renders the Overview security banner (critical styling + count + link to /snyk) when unseen alerts exist', async () => {
    stub({ scans: [scan()] })
    server.use(http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([
      alert({ id: 'c1', severity: 'critical', message: 'New critical vulnerability' }),
      alert({ id: 'h1', severity: 'high' }),
    ])))
    const user = userEvent.setup()
    renderDashboard()

    // Pluralized count (2 unseen), rendered as a role=alert banner with the Critical (danger) accent.
    const banner = await screen.findByText('2 unseen vulnerability alerts')
    const row = banner.closest('[role="alert"]') as HTMLElement
    expect(row).toBeInTheDocument()
    expect(row.className).toMatch(/border-l-danger/)
    expect(row.className).toMatch(/animate-pulse/)

    // The CTA jumps to the Snyk page.
    await user.click(within(row).getByRole('link', { name: /Review in Snyk/ }))
    expect(await screen.findByText('snyk-page')).toBeInTheDocument()
  })

  it('uses the calm (amber, no pulse) banner when the unseen alerts are non-critical', async () => {
    stub({ scans: [scan()] })
    server.use(http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([alert({ severity: 'high' })])))
    renderDashboard()

    const banner = await screen.findByText('1 unseen vulnerability alert')
    const row = banner.closest('[role="alert"]') as HTMLElement
    expect(row.className).toMatch(/border-l-warning/)
    expect(row.className).not.toMatch(/animate-pulse/)
  })

  it('renders NO security banner when there are zero unseen alerts', async () => {
    stub({ scans: [scan()] })
    server.use(http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([])))
    renderDashboard()

    await screen.findByRole('table')
    expect(screen.queryByText(/unseen vulnerability alert/)).not.toBeInTheDocument()
  })

  it('dismissing the banner acks every unseen alert (marks them seen) and clears the banner', async () => {
    const seen: string[] = []
    let alertsRead = 0
    stub({ scans: [scan()] })
    server.use(
      // First read returns two unseen alerts; after they're acked, the refetch returns none.
      http.get('*/api/v1/snyk/alerts', () => {
        alertsRead += 1
        return HttpResponse.json(seen.length > 0 ? [] : [alert({ id: 'a1' }), alert({ id: 'a2' })])
      }),
      http.post('*/api/v1/snyk/alerts/:id/seen', ({ params }) => {
        seen.push(params.id as string)
        return new HttpResponse(null, { status: 200 })
      }),
    )
    const user = userEvent.setup()
    renderDashboard()

    const banner = await screen.findByText('2 unseen vulnerability alerts')
    const row = banner.closest('[role="alert"]') as HTMLElement
    await user.click(within(row).getByRole('button', { name: /Dismiss/ }))

    // Both alerts were marked seen, and the invalidated refetch drops the banner.
    await waitFor(() => expect(seen.sort()).toEqual(['a1', 'a2']))
    await waitFor(() => expect(screen.queryByText(/unseen vulnerability alert/)).not.toBeInTheDocument())
    expect(alertsRead).toBeGreaterThan(1)
  })
})

/** Await the tile label appearing (post-load), then return its Card container. */
async function tileAsync(label: string): Promise<HTMLElement> {
  const labelEl = await screen.findByText(label, { selector: 'p.uppercase' })
  return labelEl.closest('div.rounded-xl') as HTMLElement
}