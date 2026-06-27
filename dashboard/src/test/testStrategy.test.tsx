import { describe, expect, it } from 'vitest'
import { screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { TestStrategy } from '../pages/TestStrategy'

const strategy = (over: Record<string, unknown> = {}) => ({
  id: 'st-1',
  serviceName: 'ciam-policies',
  status: 'APPROVED',
  source: 'CODE',
  confidence: 87.4,
  createdAt: '2026-06-26T10:00:00Z',
  ...over,
})

/** Drive the New-strategy form to a valid state and click Generate. */
async function generate(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText('ciam-policies'), 'ciam-policies')
  await user.type(screen.getByPlaceholderText(/POST \/policies/), 'POST /policies — create a policy')
  await user.click(screen.getByRole('button', { name: /Generate strategy/ }))
}

describe('TestStrategy page', () => {
  it('renders the form and the "No strategy yet" empty state before anything is generated', () => {
    renderPage(<TestStrategy />)

    expect(screen.getByText('Test strategy')).toBeInTheDocument()
    expect(screen.getByText('New strategy')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('ciam-policies')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Generate strategy/ })).toBeInTheDocument()
    // The list query is disabled until a strategy is generated → only the empty state shows.
    expect(screen.getByText('No strategy yet')).toBeInTheDocument()
    expect(screen.getByText(/its versions and rationale appear here/)).toBeInTheDocument()
  })

  it('validates that service + basis are required (no POST, shows an error toast)', async () => {
    let posted = false
    server.use(
      http.post('*/api/v1/services/:s/strategies', () => {
        posted = true
        return HttpResponse.json(strategy())
      }),
    )
    const user = userEvent.setup()
    renderPage(<TestStrategy />)

    // Click Generate with both fields empty → guard fires, no request.
    await user.click(screen.getByRole('button', { name: /Generate strategy/ }))
    expect(await screen.findByText('Service and basis are required.')).toBeInTheDocument()
    expect(posted).toBe(false)
    // Still on the empty state.
    expect(screen.getByText('No strategy yet')).toBeInTheDocument()
  })

  it('generates a strategy, shows the success toast, then lists the returned rows', async () => {
    server.use(
      http.post('*/api/v1/services/:s/strategies', () => HttpResponse.json(strategy())),
      http.get('*/api/v1/services/:s/strategies', () =>
        HttpResponse.json([strategy(), strategy({ id: 'st-2', status: 'DRAFT', confidence: undefined, source: 'CODE' })])),
    )
    const user = userEvent.setup()
    renderPage(<TestStrategy />)

    await generate(user)

    expect(await screen.findByText('Strategy generated.')).toBeInTheDocument()
    // The list loads for the service we just generated against.
    expect(await screen.findByText('Strategies — ciam-policies')).toBeInTheDocument()
    expect(screen.getByText('APPROVED')).toBeInTheDocument()
    expect(screen.getByText('DRAFT')).toBeInTheDocument()
    // Confidence rounds; missing confidence renders an em dash.
    expect(screen.getByText('87%')).toBeInTheDocument()
    // Every row exposes a Rationale link (href from api.strategyRationaleUrl).
    const rationale = screen.getAllByRole('link', { name: /Rationale/ })
    expect(rationale).toHaveLength(2)
    expect(rationale[0]).toHaveAttribute('href', '/api/v1/strategies/st-1/rationale')
  })

  it('shows the per-service empty state when the generated service has no stored strategies', async () => {
    server.use(
      http.post('*/api/v1/services/:s/strategies', () => HttpResponse.json(strategy())),
      http.get('*/api/v1/services/:s/strategies', () => HttpResponse.json([])),
    )
    const user = userEvent.setup()
    renderPage(<TestStrategy />)

    await generate(user)

    expect(await screen.findByText('No strategies for "ciam-policies"')).toBeInTheDocument()
  })

  it('shows only the Evidence why-doc link for multi-source strategies', async () => {
    server.use(
      http.post('*/api/v1/services/:s/strategies', () => HttpResponse.json(strategy())),
      http.get('*/api/v1/services/:s/strategies', () =>
        HttpResponse.json([strategy({ id: 'ms-1', source: 'multi-source' })])),
    )
    const user = userEvent.setup()
    renderPage(<TestStrategy />)

    await generate(user)

    const evidence = await screen.findByRole('link', { name: /Evidence/ })
    expect(evidence).toHaveAttribute('href', '/api/v1/strategies/ms-1/why-doc')
    // A CODE-sourced strategy (first test) has no Evidence link; here it's present because source is multi-source.
    expect(screen.getByRole('link', { name: /Rationale/ })).toHaveAttribute('href', '/api/v1/strategies/ms-1/rationale')
  })

  it('surfaces an error toast when generation fails on the server', async () => {
    server.use(
      http.post('*/api/v1/services/:s/strategies', () =>
        HttpResponse.json({ detail: 'The model timed out', status: 500 }, { status: 500 })),
    )
    const user = userEvent.setup()
    renderPage(<TestStrategy />)

    await generate(user)

    // 500 → friendlyMessage rewrite, raised as an error toast (role=alert), not a false success.
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByText('Strategy generated.')).not.toBeInTheDocument()
    // No list appears since loaded was never set.
    expect(screen.getByText('No strategy yet')).toBeInTheDocument()
  })

  it('offers existing services in the picker datalist (find-your-work)', async () => {
    server.use(http.get('*/api/v1/services', () => HttpResponse.json([
      { name: 'ciam-policies', strategies: 1, conditions: 9, cases: 12, plans: 0, scans: 5, codegenRuns: 4 },
      { name: 'auth-svc', strategies: 0, conditions: 0, cases: 0, plans: 0, scans: 2, codegenRuns: 0 },
    ])))
    renderPage(<TestStrategy />)
    // The datalist behind the Service field is populated from GET /services so users browse instead of guessing.
    await waitFor(() => {
      expect(document.querySelector('datalist#veritas-services option[value="ciam-policies"]')).toBeTruthy()
      expect(document.querySelector('datalist#veritas-services option[value="auth-svc"]')).toBeTruthy()
    })
  })

  it('lets you pick a non-default basis source before generating', async () => {
    let receivedSource: unknown = null
    server.use(
      http.post('*/api/v1/services/:s/strategies', async ({ request }) => {
        receivedSource = ((await request.json()) as { source?: string }).source
        return HttpResponse.json(strategy())
      }),
      http.get('*/api/v1/services/:s/strategies', () => HttpResponse.json([strategy()])),
    )
    const user = userEvent.setup()
    renderPage(<TestStrategy />)

    await user.type(screen.getByPlaceholderText('ciam-policies'), 'ciam-policies')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Basis source' }), 'JIRA')
    await user.type(screen.getByPlaceholderText(/POST \/policies/), 'As a user I can create a policy')
    await user.click(screen.getByRole('button', { name: /Generate strategy/ }))

    expect(await screen.findByText('Strategy generated.')).toBeInTheDocument()
    expect(receivedSource).toBe('JIRA')
    // Sanity: the row table rendered with the single returned strategy.
    const table = await screen.findByRole('table')
    expect(within(table).getByText('APPROVED')).toBeInTheDocument()
  })
})
