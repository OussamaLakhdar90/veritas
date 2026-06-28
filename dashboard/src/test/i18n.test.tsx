import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
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

describe('i18n — bilingual shell', () => {
  it('renders English by default in tests and switches the whole shell to French on the FR toggle', async () => {
    stubDashboard()
    window.location.hash = '#/'
    const user = userEvent.setup()
    renderApp()

    // setup pins tests to English — the source labels are shown.
    expect(await screen.findByText('API Quality Platform')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Test Strategy' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'FR' }))

    // Every label flips to Québec French, and the choice is persisted + reflected on <html lang>.
    expect(await screen.findByText('Plateforme de qualité des API')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Stratégie de test' })).toBeInTheDocument()
    expect(localStorage.getItem('veritas-lang')).toBe('fr')
    expect(document.documentElement.lang).toBe('fr')

    window.location.hash = ''
  })
})
