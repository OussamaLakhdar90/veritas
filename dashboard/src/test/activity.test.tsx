import { describe, it, expect } from 'vitest'
import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { Activity, partitionActivity, activityKpis, dayBucket, groupHistoryByDay } from '../pages/Activity'
import type { ActivityItem } from '../api'

/** ISO instant `offsetMs` from now (LOCAL, no trailing Z) so day math is timezone-stable in CI. */
const iso = (offsetMs: number) => {
  const d = new Date(Date.now() + offsetMs)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function item(over: Partial<ActivityItem> & Pick<ActivityItem, 'id'>): ActivityItem {
  return {
    type: 'SCAN', label: `svc-${over.id}`, status: 'COMPLETED',
    needsAttention: false, link: `/findings/${over.id}`, acked: false, ...over,
  }
}

const stub = (items: ActivityItem[]) =>
  server.use(http.get('*/api/v1/activity', () => HttpResponse.json(items)))

const renderActivity = () =>
  renderPage(<Activity />, {
    path: '/activity', route: '/activity',
    extraRoutes: [
      { path: '/repos', element: <div>Validate page</div> },
      { path: '/snyk', element: <div>Snyk page</div> },
    ],
  })

describe('partitionActivity', () => {
  it('splits by priority and drops a dismissed failure to history', () => {
    const items = [
      item({ id: 'run', status: 'RUNNING', startedAt: iso(-60_000) }),
      item({ id: 'wait', type: 'FIX_TRAIN', status: 'WAITING_FOR_YOU', needsAttention: true, startedAt: iso(-600_000), link: '/snyk' }),
      item({ id: 'failU', status: 'FAILED', needsAttention: true, startedAt: iso(-300_000), finishedAt: iso(-120_000) }),
      item({ id: 'failA', status: 'FAILED', needsAttention: true, acked: true, startedAt: iso(-400_000), finishedAt: iso(-200_000) }),
      item({ id: 'done', status: 'COMPLETED', startedAt: iso(-500_000), finishedAt: iso(-100_000) }),
    ]
    const g = partitionActivity(items)
    // Waiting = needsAttention && !acked, oldest first.
    expect(g.waiting.map((i) => i.id)).toEqual(['wait', 'failU'])
    expect(g.inProgress.map((i) => i.id)).toEqual(['run'])
    // The acked failure and the completed run both land in history, newest finish first.
    expect(g.history.map((i) => i.id)).toEqual(['done', 'failA'])
  })
})

describe('activityKpis', () => {
  it('counts live, waiting-on-you (unacked), and finished', () => {
    const items = [
      item({ id: 'q', status: 'QUEUED' }),
      item({ id: 'r', status: 'RUNNING' }),
      item({ id: 'w', status: 'WAITING_FOR_YOU', needsAttention: true }),
      item({ id: 'fA', status: 'FAILED', needsAttention: true, acked: true, finishedAt: iso(-1000) }),
      item({ id: 'c', status: 'COMPLETED', finishedAt: iso(-1000) }),
    ]
    expect(activityKpis(items)).toEqual({ inProgress: 2, waitingOnYou: 1, finished7d: 2 })
  })
})

describe('dayBucket', () => {
  it('labels today, yesterday, older dates, and the unknown case', () => {
    const now = Date.parse('2026-06-30T12:00:00')
    expect(dayBucket('2026-06-30T09:00:00', now)).toBe('today')
    expect(dayBucket('2026-06-29T23:00:00', now)).toBe('yesterday')
    expect(dayBucket('2026-06-27T10:00:00', now)).toBe('2026-06-27')
    expect(dayBucket(undefined, now)).toBe('earlier')
  })
})

describe('groupHistoryByDay', () => {
  it('groups rows under one key per day, preserving order', () => {
    const now = Date.parse('2026-06-30T12:00:00')
    const items = [
      item({ id: 'a', finishedAt: '2026-06-30T10:00:00' }),
      item({ id: 'b', finishedAt: '2026-06-30T09:00:00' }),
      item({ id: 'c', finishedAt: '2026-06-29T10:00:00' }),
    ]
    const groups = groupHistoryByDay(items, now)
    expect(groups.map((g) => g.key)).toEqual(['today', 'yesterday'])
    expect(groups[0].items.map((i) => i.id)).toEqual(['a', 'b'])
    expect(groups[1].items.map((i) => i.id)).toEqual(['c'])
  })
})

describe('Activity page', () => {
  it('shows the reassuring empty state with a Validate CTA when nothing is happening', async () => {
    stub([])
    renderActivity()
    expect(await screen.findByText('All quiet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Validate a service' })).toHaveAttribute('href', '/repos')
    // A quiet feed shows no KPI markup.
    expect(screen.queryByText('Finished (7 days)')).toBeNull()
  })

  it('sorts a live scan and a waiting fix train into their sections, each a whole-row link', async () => {
    stub([
      item({ id: 's1', type: 'SCAN', label: 'ciam-policies', status: 'RUNNING', stage: 'RECONCILING', startedAt: iso(-60_000), link: '/findings/s1' }),
      item({ id: 't1', type: 'FIX_TRAIN', label: 'org.foo:bar', status: 'WAITING_FOR_YOU', needsAttention: true, startedAt: iso(-600_000), link: '/snyk' }),
    ])
    renderActivity()

    const waiting = await screen.findByRole('region', { name: 'Waiting on you' })
    expect(within(waiting).getByText('org.foo:bar')).toBeInTheDocument()
    expect(within(waiting).getByText('Review')).toBeInTheDocument()
    expect(within(waiting).getByRole('link')).toHaveAttribute('href', '/snyk')

    const inProgress = screen.getByRole('region', { name: 'In progress' })
    expect(within(inProgress).getByText('ciam-policies')).toBeInTheDocument()
    // The stage code is routed through plain language, never shown raw.
    expect(within(inProgress).getByText('AI reviewing the differences')).toBeInTheDocument()
    expect(within(inProgress).getByRole('link')).toHaveAttribute('href', '/findings/s1')
  })

  it('places a completed run in history and never leaks an unknown stage code', async () => {
    stub([
      item({ id: 'c1', type: 'CODEGEN', label: 'payments', status: 'COMPLETED', stage: 'MYSTERY_CODE', startedAt: iso(-200_000), finishedAt: iso(-100_000), link: '/generate-tests' }),
    ])
    renderActivity()
    const history = await screen.findByRole('region', { name: 'Recent history (7 days)' })
    expect(within(history).getByText('payments')).toBeInTheDocument()
    expect(screen.queryByText(/MYSTERY_CODE/)).toBeNull()
  })

  it('shows an error state when the very first load fails', async () => {
    server.use(http.get('*/api/v1/activity', () => HttpResponse.json({ detail: 'boom' }, { status: 500 })))
    renderActivity()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})
