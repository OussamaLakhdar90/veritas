import { afterEach, describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { ToastProvider } from '../components/Toast'
import { App } from '../App'

function renderApp() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <App />
      </ToastProvider>
    </QueryClientProvider>,
  )
}

function stubDashboard() {
  server.use(
    http.get('*/api/v1/scans', () => HttpResponse.json([])),
    http.get('*/api/v1/preflight', () => HttpResponse.json([])),
    http.get('*/api/v1/costs/summary', () => HttpResponse.json({ totalEstCostUsd: 0, actions: 0, bySkill: {} })),
    http.get('*/api/v1/defects', () => HttpResponse.json([])),
  )
}

afterEach(() => { window.location.hash = '' })

describe('Command palette', () => {
  it('opens from the header Search button and jumps to a page on Enter', async () => {
    stubDashboard()
    window.location.hash = '#/'
    const user = userEvent.setup()
    renderApp()

    await screen.findByRole('heading', { name: 'Overview' })
    await user.click(screen.getByRole('button', { name: /Search/ }))

    const dialog = await screen.findByRole('dialog', { name: 'Command palette' })
    await user.type(within(dialog).getByPlaceholderText('Search pages and services…'), 'cost')
    await user.keyboard('{Enter}')

    expect(await screen.findByRole('heading', { name: 'LLM cost' })).toBeInTheDocument()
  })

  it('lists services from the catalog as jump targets', async () => {
    stubDashboard()
    server.use(http.get('*/api/v1/services', () => HttpResponse.json([
      { name: 'payments-api', strategies: 0, conditions: 0, cases: 0, plans: 0, scans: 0, codegenRuns: 0 },
    ])))
    window.location.hash = '#/'
    const user = userEvent.setup()
    renderApp()

    await screen.findByRole('heading', { name: 'Overview' })
    await user.click(screen.getByRole('button', { name: /Search/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Command palette' })

    expect(await within(dialog).findByText('payments-api')).toBeInTheDocument()
  })
})
