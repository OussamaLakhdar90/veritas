import { describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { TestCases } from '../pages/TestCases'

const tc = (over: Record<string, unknown> = {}) => ({
  id: 'tc-1',
  serviceName: 'ciam-policies',
  title: 'Create policy returns 201 with the persisted body',
  technique: 'Equivalence partitioning',
  status: 'CREATED',
  xrayKey: undefined,
  ...over,
})

function renderTestCases() {
  return renderPage(<TestCases />, { path: '/test-cases', route: '/test-cases' })
}

/** Type a service name into the Service field and click Load to fire the test-cases query. */
async function loadService(user: ReturnType<typeof userEvent.setup>, name = 'ciam-policies') {
  await user.type(screen.getByPlaceholderText('ciam-policies'), name)
  await user.click(screen.getByRole('button', { name: /Load/ }))
}

describe('TestCases (RTM workspace)', () => {
  it('shows the empty load prompt before any service is queried', () => {
    renderTestCases()
    expect(screen.getByText('Load a service')).toBeInTheDocument()
    expect(screen.getByText(/Enter a service name above to review/)).toBeInTheDocument()
    // Load is disabled until a service is typed.
    expect(screen.getByRole('button', { name: /Load/ })).toBeDisabled()
  })

  it('renders the test-case rows after loading a service', async () => {
    server.use(
      http.get('*/api/v1/services/:service/test-cases', () =>
        HttpResponse.json([tc(), tc({ id: 'tc-2', title: 'Fetch missing policy 404', technique: 'Boundary value analysis', status: 'APPROVED', xrayKey: 'CIAM-42' })])),
    )
    const user = userEvent.setup()
    renderTestCases()

    await loadService(user)

    expect(await screen.findByText('Create policy returns 201 with the persisted body')).toBeInTheDocument()
    expect(screen.getByText('Fetch missing policy 404')).toBeInTheDocument()
    expect(screen.getByText('Equivalence partitioning')).toBeInTheDocument()
    expect(screen.getByText('Boundary value analysis')).toBeInTheDocument()
    expect(screen.getByText('CIAM-42')).toBeInTheDocument()       // the pushed Xray key column
    expect(screen.getByText('Created')).toBeInTheDocument()         // status badge (humanized, never raw)
  })

  it('shows the per-service empty state when the service has no cases', async () => {
    server.use(http.get('*/api/v1/services/:service/test-cases', () => HttpResponse.json([])))
    const user = userEvent.setup()
    renderTestCases()

    await loadService(user, 'empty-svc')

    expect(await screen.findByText('No cases for "empty-svc"')).toBeInTheDocument()
    expect(screen.getByText(/Generate test cases for this service/)).toBeInTheDocument()
  })

  it('surfaces a query error (no rows, no false empty state)', async () => {
    server.use(http.get('*/api/v1/services/:service/test-cases', () =>
      HttpResponse.json({ detail: 'boom', status: 500 }, { status: 500 })))
    const user = userEvent.setup()
    renderTestCases()

    await loadService(user)

    // The query failed: the data branch never renders, so the title never appears.
    await screen.findByText('Test cases')   // page still mounted
    expect(screen.queryByText('Create policy returns 201 with the persisted body')).not.toBeInTheDocument()
    expect(screen.queryByText('Load a service')).not.toBeInTheDocument()   // we did query, so we left the initial state
  })

  it('approves a row (PATCH) and shows the success toast', async () => {
    let patched: { id: string; body: unknown } | null = null
    server.use(
      http.get('*/api/v1/services/:service/test-cases', () => HttpResponse.json([tc()])),
      http.patch('*/api/v1/test-cases/:id', async ({ params, request }) => {
        patched = { id: params.id as string, body: await request.json() }
        return HttpResponse.json(tc({ status: 'APPROVED' }))
      }),
    )
    const user = userEvent.setup()
    renderTestCases()

    await loadService(user)
    const row = (await screen.findByText('Create policy returns 201 with the persisted body')).closest('tr')!
    await user.click(within(row).getByRole('button', { name: /Approve/ }))

    expect(await screen.findByText('Updated.')).toBeInTheDocument()
    expect(patched).toEqual({ id: 'tc-1', body: { status: 'APPROVED', actor: 'dashboard' } })
  })

  it('disables Push to Xray until a project key is entered, then pushes', async () => {
    let pushed: { id: string; body: unknown } | null = null
    server.use(
      http.get('*/api/v1/services/:service/test-cases', () => HttpResponse.json([tc()])),
      http.post('*/api/v1/test-cases/:id/push', async ({ params, request }) => {
        pushed = { id: params.id as string, body: await request.json() }
        return HttpResponse.json(tc({ status: 'ATTACHED', xrayKey: 'CIAM-7' }))
      }),
    )
    const user = userEvent.setup()
    renderTestCases()

    await loadService(user)
    const row = (await screen.findByText('Create policy returns 201 with the persisted body')).closest('tr')!
    expect(within(row).getByRole('button', { name: /Push to Xray/ })).toBeDisabled()

    await user.type(screen.getByPlaceholderText('CIAM'), 'ciam')   // lower-cased input is upper-cased by the field
    expect(within(row).getByRole('button', { name: /Push to Xray/ })).toBeEnabled()

    await user.click(within(row).getByRole('button', { name: /Push to Xray/ }))
    expect(await screen.findByText('Updated.')).toBeInTheDocument()
    expect(pushed).toEqual({ id: 'tc-1', body: { projectKey: 'CIAM' } })
  })

  it('surfaces the lifecycle 409 from a push as an error toast', async () => {
    server.use(
      http.get('*/api/v1/services/:service/test-cases', () => HttpResponse.json([tc()])),
      http.post('*/api/v1/test-cases/:id/push', () =>
        HttpResponse.json({ detail: 'Test case must be APPROVED before it can be pushed to Xray.', status: 409 }, { status: 409 })),
    )
    const user = userEvent.setup()
    renderTestCases()

    await loadService(user)
    const row = (await screen.findByText('Create policy returns 201 with the persisted body')).closest('tr')!
    await user.type(screen.getByPlaceholderText('CIAM'), 'CIAM')
    await user.click(within(row).getByRole('button', { name: /Push to Xray/ }))

    // The 409 detail is human-readable, so the api layer trusts it as-is and the row action surfaces an error toast.
    expect(await screen.findByRole('alert')).toHaveTextContent('Test case must be APPROVED before it can be pushed to Xray.')
    expect(screen.queryByText('Updated.')).not.toBeInTheDocument()
  })

  it('validates the generate form before calling the endpoint', async () => {
    const user = userEvent.setup()
    renderTestCases()

    // No service / basis yet → clicking Generate raises a validation toast and never hits the network.
    await user.click(screen.getByRole('button', { name: /Generate/ }))
    expect(await screen.findByText('Service and basis are required.')).toBeInTheDocument()
  })

  it('generates cases (POST), then auto-loads them into the table', async () => {
    let generated: { service: string; body: unknown } | null = null
    server.use(
      http.post('*/api/v1/services/:service/test-cases', async ({ params, request }) => {
        generated = { service: params.service as string, body: await request.json() }
        return HttpResponse.json([tc({ id: 'tc-9', title: 'Generated: reject blank name' })])
      }),
      http.get('*/api/v1/services/:service/test-cases', () =>
        HttpResponse.json([tc({ id: 'tc-9', title: 'Generated: reject blank name' })])),
    )
    const user = userEvent.setup()
    renderTestCases()

    await user.type(screen.getByPlaceholderText('ciam-policies'), 'ciam-policies')
    await user.type(screen.getByPlaceholderText(/POST \/policies/), 'POST /policies — create')
    await user.click(screen.getByRole('button', { name: /Generate/ }))

    expect(await screen.findByText('Test cases generated.')).toBeInTheDocument()
    expect(await screen.findByText('Generated: reject blank name')).toBeInTheDocument()
    expect(generated).toEqual({ service: 'ciam-policies', body: { basis: 'POST /policies — create' } })
  })
})
