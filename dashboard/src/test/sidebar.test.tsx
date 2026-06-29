import { afterEach, describe, expect, it } from 'vitest'
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

afterEach(() => { window.location.hash = '' })

describe('Sidebar', () => {
  it('collapses + expands and persists the choice', async () => {
    stubDashboard()
    window.location.hash = '#/'
    const user = userEvent.setup()
    renderApp()

    await screen.findByRole('heading', { name: 'Overview' })
    await user.click(screen.getByRole('button', { name: 'Collapse sidebar' }))
    expect(localStorage.getItem('veritas-sidebar-collapsed')).toBe('1')
    expect(screen.getByRole('button', { name: 'Expand sidebar' })).toBeInTheDocument()
  })

  it('exposes a mobile menu button', async () => {
    stubDashboard()
    window.location.hash = '#/'
    renderApp()
    expect(await screen.findByRole('button', { name: 'Open menu' })).toBeInTheDocument()
  })
})
