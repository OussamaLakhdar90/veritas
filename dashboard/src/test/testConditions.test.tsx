import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse, delay } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { TestConditions } from '../pages/TestConditions'

const strategy = { id: 's1', serviceName: 'ciam-policies', status: 'APPROVED', version: 2 }

const conditions = [
  { id: 'c1', conditionRef: 'TCD-001', description: 'Create policy rejects invalid payloads', sourceBasisItem: 'POST /policies',
    priority: 'P1', riskRef: 'R1', qualityCharacteristic: 'Functional suitability', technique: 'Boundary Value Analysis',
    automation: 'AUTOMATED', status: 'PROPOSED', testStrategyId: 's1' },
  { id: 'c2', conditionRef: 'TCD-002', description: 'Exploratory clarity check', sourceBasisItem: 'POST /policies',
    priority: 'P3', riskRef: 'R1', qualityCharacteristic: 'Usability', technique: 'Exploratory',
    automation: 'MANUAL', status: 'PROPOSED', testStrategyId: 's1' },
]

function stub(over: { conditions?: unknown; status?: number } = {}) {
  server.use(
    http.get('*/api/v1/strategies/:id/test-conditions', () =>
      over.status && over.status >= 400
        ? HttpResponse.json({ detail: 'boom', status: over.status }, { status: over.status })
        : HttpResponse.json(over.conditions ?? conditions)),
    http.get('*/api/v1/strategies/:id', () => HttpResponse.json(strategy)),
  )
}

function renderConditions(id = 's1') {
  return renderPage(<TestConditions />, { path: '/test-conditions/:id', route: `/test-conditions/${id}` })
}

describe('TestConditions', () => {
  it('shows a loading state while conditions load', async () => {
    server.use(http.get('*/api/v1/strategies/:id/test-conditions',
      async () => { await delay('infinite'); return HttpResponse.json(conditions) }))
    server.use(http.get('*/api/v1/strategies/:id', () => HttpResponse.json(strategy)))
    renderConditions()
    expect(await screen.findByText(/Loading/)).toBeInTheDocument()
  })

  it('renders the condition list, the automation split, and the report link', async () => {
    stub()
    renderConditions()
    expect(await screen.findByRole('heading', { name: /ciam-policies — Test Conditions/ })).toBeInTheDocument()
    expect(await screen.findByText('TCD-001')).toBeInTheDocument()
    expect(screen.getByText('Create policy rejects invalid payloads')).toBeInTheDocument()
    expect(screen.getByText('1 automated')).toBeInTheDocument()
    expect(screen.getByText('1 manual')).toBeInTheDocument()
    // the Test Condition List document link points at the report endpoint
    const link = screen.getByRole('link', { name: /Condition List/ })
    expect(link).toHaveAttribute('href', expect.stringContaining('/strategies/s1/test-conditions/report'))
  })

  it('changes a condition automation via the per-row select (PATCH)', async () => {
    stub()
    let patched: { id?: string; body?: { automation?: string } } | null = null
    server.use(http.patch('*/api/v1/test-conditions/:id', async ({ request, params }) => {
      patched = { id: params.id as string, body: await request.json() as { automation?: string } }
      return HttpResponse.json({ ...conditions[0], automation: 'MANUAL' })
    }))
    renderConditions()
    const select = await screen.findByLabelText('Automation for TCD-001')
    await userEvent.selectOptions(select, 'MANUAL')
    await waitFor(() => expect(patched).toMatchObject({ id: 'c1', body: { automation: 'MANUAL' } }))
  })

  it('shows an empty state when no conditions exist', async () => {
    stub({ conditions: [] })
    renderConditions()
    expect(await screen.findByText(/No test conditions yet/)).toBeInTheDocument()
  })

  it('surfaces a friendly error when conditions cannot be loaded', async () => {
    stub({ status: 500 })
    renderConditions()
    expect(await screen.findByText(/Could not load test conditions/)).toBeInTheDocument()
  })
})
