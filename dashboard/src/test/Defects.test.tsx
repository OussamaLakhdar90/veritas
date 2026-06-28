import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from './msw/server'
import { renderPage } from './render'
import { Defects } from '../pages/Defects'
import type { DefectLink } from '../api'

const defect = (over: Partial<DefectLink> = {}): DefectLink => ({
  id: 'def-1',
  findingId: 'fnd-1',
  scanId: 'scan-1',
  jiraKey: 'CIAM-42',
  jiraUrl: 'https://jira.example.com/browse/CIAM-42',
  jiraStatus: 'In Progress',
  jiraStatusCategory: 'indeterminate',
  createdInJira: true,
  createdBy: 'alice',
  lastSyncedAt: '2026-06-20T12:00:00.000Z',
  createdAt: '2026-06-19T09:00:00.000Z',
  ...over,
})

function renderDefects() {
  return renderPage(<Defects />, { path: '/defects', route: '/defects' })
}

describe('Defects page', () => {
  it('shows the defect-metrics strip (totals + severity distribution)', async () => {
    server.use(
      http.get('*/api/v1/defects', () => HttpResponse.json([])),
      http.get('*/api/v1/defects/metrics', () => HttpResponse.json({
        total: 5, open: 3, closed: 2, bySeverity: { HIGH: 4, MEDIUM: 1 },
        byStatusCategory: { done: 2, 'in progress': 3 }, byService: { 'ciam-policies': 5 },
      })),
    )
    renderDefects()

    expect(await screen.findByText('Total defects')).toBeInTheDocument()
    expect(screen.getByText('By severity')).toBeInTheDocument()
    expect(screen.getByText('HIGH')).toBeInTheDocument()
  })

  it('renders the page header while the list is loading', async () => {
    // Never-resolving GET keeps the query in its loading branch.
    server.use(http.get('*/api/v1/defects', () => new Promise(() => {})))
    renderDefects()

    expect(await screen.findByText('Defects')).toBeInTheDocument()
    expect(screen.getByText(/Jira defects raised from contract findings/)).toBeInTheDocument()
    expect(screen.getByText(/Loading/)).toBeInTheDocument()
  })

  it('shows the empty state when there are no defects', async () => {
    server.use(http.get('*/api/v1/defects', () => HttpResponse.json([])))
    renderDefects()

    expect(await screen.findByText('No defects yet')).toBeInTheDocument()
    expect(
      screen.getByText(/Defects you raise from findings will appear here/),
    ).toBeInTheDocument()
  })

  it('surfaces an error message when the list fails to load', async () => {
    server.use(
      http.get('*/api/v1/defects', () =>
        HttpResponse.json({ detail: 'The database is unavailable', status: 500 }, { status: 500 })),
    )
    renderDefects()

    expect(await screen.findByText(/Could not load defects:/)).toBeInTheDocument()
  })

  it('renders the defect rows with a linked Jira key, status badge, author and synced time', async () => {
    server.use(
      http.get('*/api/v1/defects', () =>
        HttpResponse.json([
          defect(),
          defect({
            id: 'def-2',
            jiraKey: 'CIAM-7',
            jiraUrl: undefined,
            jiraStatus: undefined,
            jiraStatusCategory: undefined,
            createdInJira: false,
            createdBy: undefined,
            lastSyncedAt: undefined,
          }),
        ])),
    )
    renderDefects()

    // Column headers.
    expect(await screen.findByText('Jira')).toBeInTheDocument()
    expect(screen.getByText('Status')).toBeInTheDocument()
    expect(screen.getByText('Created by')).toBeInTheDocument()
    expect(screen.getByText('Last synced')).toBeInTheDocument()

    // Row 1: a real Jira link with status badge + author.
    const link = screen.getByRole('link', { name: /CIAM-42/ })
    expect(link).toHaveAttribute('href', 'https://jira.example.com/browse/CIAM-42')
    expect(screen.getByText('In Progress')).toBeInTheDocument()
    expect(screen.getByText('alice')).toBeInTheDocument()

    // Row 2: no Jira URL → plain key, never created → "Not created" badge.
    expect(screen.getByText('CIAM-7')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /CIAM-7/ })).not.toBeInTheDocument()
    expect(screen.getByText('Not created')).toBeInTheDocument()
  })

  it('syncs statuses (POST /defects/sync), refetches, and shows the success toast', async () => {
    let getCalls = 0
    server.use(
      http.get('*/api/v1/defects', () => {
        getCalls += 1
        // First load: stale status. After sync invalidates the query, return the fresh one.
        return HttpResponse.json([
          defect({ jiraStatus: getCalls === 1 ? 'To Do' : 'Done', jiraStatusCategory: getCalls === 1 ? 'new' : 'done' }),
        ])
      }),
      http.post('*/api/v1/defects/sync', () => HttpResponse.json({ updated: 3 })),
    )
    const user = userEvent.setup()
    renderDefects()

    expect(await screen.findByText('To Do')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Refresh statuses/ }))

    // Success toast carries the server-reported updated count.
    expect(await screen.findByText('Synced — 3 updated.')).toBeInTheDocument()
    // Query was invalidated → refetch surfaced the new status.
    expect(await screen.findByText('Done')).toBeInTheDocument()
  })

  it('shows an error toast when the sync request fails', async () => {
    server.use(
      http.get('*/api/v1/defects', () => HttpResponse.json([defect()])),
      http.post('*/api/v1/defects/sync', () =>
        HttpResponse.json({ detail: 'Jira is unreachable', status: 502 }, { status: 502 })),
    )
    const user = userEvent.setup()
    renderDefects()

    expect(await screen.findByRole('link', { name: /CIAM-42/ })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Refresh statuses/ }))

    // An error toast (role=alert), not a false "Synced" success.
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText(/Sync failed:/)).toBeInTheDocument()
  })

  it('sorts the table when a column header is clicked', async () => {
    server.use(
      http.get('*/api/v1/defects', () =>
        HttpResponse.json([
          defect({ id: 'a', jiraKey: 'CIAM-100', jiraUrl: undefined }),
          defect({ id: 'b', jiraKey: 'CIAM-1', jiraUrl: undefined }),
        ])),
    )
    const user = userEvent.setup()
    renderDefects()

    expect(await screen.findByText('CIAM-100')).toBeInTheDocument()

    // Default sort is ascending by jiraKey: CIAM-1 before CIAM-100.
    let cells = screen.getAllByText(/^CIAM-/).map((el) => el.textContent)
    expect(cells).toEqual(['CIAM-1', 'CIAM-100'])

    // Click the Jira header → toggles to descending.
    await user.click(screen.getByRole('button', { name: /Jira/ }))
    cells = screen.getAllByText(/^CIAM-/).map((el) => el.textContent)
    expect(cells).toEqual(['CIAM-100', 'CIAM-1'])
  })
})