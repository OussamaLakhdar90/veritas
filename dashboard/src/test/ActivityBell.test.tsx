import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { ActivityBell } from '../components/ActivityBell'

const alert = (id: string, severity: string) => ({
  id, watchId: 'w1', orgSlug: 'app7576', repoSlug: 'application-tests',
  severity, message: `${severity} alert`, seen: false, createdAt: '2026-01-01T00:00:00Z',
})

const activityItem = (over: Record<string, unknown> = {}) => ({
  id: 'act-1', type: 'FIX_TRAIN', label: 'log4j-core fix train', status: 'WAITING_FOR_YOU',
  stage: 'AWAITING_CONFIRM', detail: '', needsAttention: true, startedAt: '2026-01-01T00:00:00Z',
  finishedAt: null, link: '/snyk', acked: false, ...over,
})

/** The popover lives inside the bell's positioning root — scope queries there so the dock card doesn't match. */
function bellRoot(): HTMLElement {
  return screen.getByRole('button', { name: 'Notifications' }).parentElement as HTMLElement
}

describe('Activity bell', () => {
  it('shows a red count when an unseen alert is Critical and the Security row jumps to Snyk', async () => {
    server.use(http.get('*/api/v1/snyk/alerts', () =>
      HttpResponse.json([alert('a1', 'critical'), alert('a2', 'high')])))
    const user = userEvent.setup()
    renderPage(<ActivityBell />, { extraRoutes: [{ path: '/snyk', element: <div>Snyk page</div> }] })

    const badge = await screen.findByText('2')
    expect(badge).toHaveClass('bg-danger')   // red because one alert is Critical
    await user.click(screen.getByRole('button', { name: 'Notifications' }))
    await user.click(screen.getByRole('link', { name: '2 unseen vulnerability alert(s)' }))
    expect(await screen.findByText('Snyk page')).toBeInTheDocument()
  })

  it('uses the brand colour (not red) when no alert is Critical', async () => {
    server.use(http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([alert('a1', 'high')])))
    renderPage(<ActivityBell />)
    const badge = await screen.findByText('1')
    expect(badge).toHaveClass('bg-brand')
    expect(badge).not.toHaveClass('bg-danger')
  })

  it('renders no count badge and an empty popover line when nothing needs attention', async () => {
    // base handlers return [] for alerts and activity — the bell is silent.
    const user = userEvent.setup()
    renderPage(<ActivityBell />)
    expect(await screen.findByRole('button', { name: 'Notifications' })).toBeInTheDocument()
    expect(screen.queryByText(/^\d+$/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Notifications' }))
    expect(await screen.findByText('Nothing needs your attention.')).toBeInTheDocument()
  })

  it('adds attention activity items to the badge count and lists them in the popover', async () => {
    server.use(
      http.get('*/api/v1/snyk/alerts', () => HttpResponse.json([alert('a1', 'high')])),
      http.get('*/api/v1/activity', () => HttpResponse.json([activityItem()])),
    )
    const user = userEvent.setup()
    renderPage(<ActivityBell />)

    // 1 unseen alert + 1 attention activity item = 2 (and no Critical → brand colour).
    const badge = await screen.findByText('2')
    expect(badge).toHaveClass('bg-brand')

    await user.click(screen.getByRole('button', { name: 'Notifications' }))
    const popover = bellRoot()
    expect(within(popover).getByText('Activity')).toBeInTheDocument()
    expect(within(popover).getByRole('link', { name: 'log4j-core fix train' })).toBeInTheDocument()
    expect(within(popover).getByText('Security')).toBeInTheDocument()
    expect(within(popover).getByRole('link', { name: '1 unseen vulnerability alert(s)' })).toBeInTheDocument()
  })

  it('clicking an attention item acks it, closes the popover and navigates to its link', async () => {
    let ackedIds: string[] = []
    server.use(
      http.get('*/api/v1/activity', () => HttpResponse.json([activityItem({ link: '/gates' })])),
      http.post('*/api/v1/activity/ack', async ({ request }) => {
        ackedIds = ((await request.json()) as { ids: string[] }).ids
        return new HttpResponse(null, { status: 200 })
      }),
    )
    const user = userEvent.setup()
    renderPage(<ActivityBell />, { extraRoutes: [{ path: '/gates', element: <div>Gates page</div> }] })

    await screen.findByText('1')
    await user.click(screen.getByRole('button', { name: 'Notifications' }))
    await user.click(within(bellRoot()).getByRole('link', { name: 'log4j-core fix train' }))
    expect(await screen.findByText('Gates page')).toBeInTheDocument()
    await waitFor(() => expect(ackedIds).toEqual(['act-1']))
  })

  it('Escape closes the popover', async () => {
    const user = userEvent.setup()
    renderPage(<ActivityBell />)
    await user.click(await screen.findByRole('button', { name: 'Notifications' }))
    expect(await screen.findByText('Nothing needs your attention.')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByText('Nothing needs your attention.')).not.toBeInTheDocument())
  })
})
