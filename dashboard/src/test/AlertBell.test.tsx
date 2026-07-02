import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { AlertBell } from '../components/AlertBell'

const alert = (id: string, severity: string) => ({
  id, watchId: 'w1', orgSlug: 'app7576', repoSlug: 'application-tests',
  severity, message: `${severity} alert`, seen: false, createdAt: '2026-01-01T00:00:00Z',
})

describe('Snyk alert bell', () => {
  it('shows a red count when an unseen alert is Critical and jumps to Snyk on click', async () => {
    server.use(http.get('*/api/v1/snyk/alerts', () =>
      HttpResponse.json([alert('a1', 'critical'), alert('a2', 'high')])))
    const user = userEvent.setup()
    renderPage(<AlertBell />, { extraRoutes: [{ path: '/snyk', element: <div>Snyk page</div> }] })

    const badge = await screen.findByText('2')
    expect(badge).toHaveClass('bg-danger')   // red because one alert is Critical
    await user.click(screen.getByRole('button'))
    expect(await screen.findByText('Snyk page')).toBeInTheDocument()
  })

  it('uses the brand colour (not red) when no alert is Critical', async () => {
    server.use(http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([alert('a1', 'high')])))
    renderPage(<AlertBell />)
    const badge = await screen.findByText('1')
    expect(badge).toHaveClass('bg-brand')
    expect(badge).not.toHaveClass('bg-danger')
  })

  it('renders no count badge when there are no alerts', async () => {
    // base handler returns [] — the bell is silent.
    renderPage(<AlertBell />)
    expect(await screen.findByRole('button')).toBeInTheDocument()
    expect(screen.queryByText(/^\d+$/)).not.toBeInTheDocument()
  })
})
